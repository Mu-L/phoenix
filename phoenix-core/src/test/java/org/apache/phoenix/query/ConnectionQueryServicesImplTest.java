/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.query;

import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SYSTEM_MUTEX_FAMILY_NAME_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SYSTEM_MUTEX_NAME;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SYSTEM_MUTEX_TABLE_NAME;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SYSTEM_SCHEMA_NAME;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.TTL_FOR_MUTEX;
import static org.apache.phoenix.query.QueryServices.CQSI_THREAD_POOL_ALLOW_CORE_THREAD_TIMEOUT;
import static org.apache.phoenix.query.QueryServices.CQSI_THREAD_POOL_CORE_POOL_SIZE;
import static org.apache.phoenix.query.QueryServices.CQSI_THREAD_POOL_ENABLED;
import static org.apache.phoenix.query.QueryServices.CQSI_THREAD_POOL_KEEP_ALIVE_SECONDS;
import static org.apache.phoenix.query.QueryServices.CQSI_THREAD_POOL_MAX_QUEUE;
import static org.apache.phoenix.query.QueryServices.CQSI_THREAD_POOL_MAX_THREADS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.TableNotEnabledException;
import org.apache.hadoop.hbase.TableNotFoundException;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.phoenix.SystemExitRule;
import org.apache.phoenix.exception.PhoenixIOException;
import org.apache.phoenix.jdbc.ConnectionInfo;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.monitoring.GlobalClientMetrics;
import org.apache.phoenix.util.ReadOnlyProps;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class ConnectionQueryServicesImplTest {
  private static final PhoenixIOException PHOENIX_IO_EXCEPTION =
    new PhoenixIOException(new Exception("Test exception"));
  private TableDescriptor sysMutexTableDescCorrectTTL = TableDescriptorBuilder
    .newBuilder(TableName.valueOf(SYSTEM_MUTEX_NAME)).setColumnFamily(ColumnFamilyDescriptorBuilder
      .newBuilder(SYSTEM_MUTEX_FAMILY_NAME_BYTES).setTimeToLive(TTL_FOR_MUTEX).build())
    .build();

  @ClassRule
  public static final SystemExitRule SYSTEM_EXIT_RULE = new SystemExitRule();

  @Mock
  private ConnectionQueryServicesImpl mockCqs;

  @Mock
  private Admin mockAdmin;

  @Mock
  private ReadOnlyProps readOnlyProps;

  @Mock
  private Connection mockConn;

  @Mock
  private Table mockTable;

  @Mock
  private GuidePostsCacheWrapper mockTableStatsCache;

  public static final TableDescriptorBuilder SYS_TASK_TDB =
    TableDescriptorBuilder.newBuilder(TableName.valueOf(PhoenixDatabaseMetaData.SYSTEM_TASK_NAME));
  public static final TableDescriptorBuilder SYS_TASK_TDB_SP =
    TableDescriptorBuilder.newBuilder(TableName.valueOf(PhoenixDatabaseMetaData.SYSTEM_TASK_NAME))
      .setRegionSplitPolicyClassName("abc");

  @Before
  public void setup()
    throws IOException, NoSuchFieldException, IllegalAccessException, SQLException {
    MockitoAnnotations.initMocks(this);
    Field props = ConnectionQueryServicesImpl.class.getDeclaredField("props");
    props.setAccessible(true);
    props.set(mockCqs, readOnlyProps);
    props = ConnectionQueryServicesImpl.class.getDeclaredField("connection");
    props.setAccessible(true);
    props.set(mockCqs, mockConn);
    props = ConnectionQueryServicesImpl.class.getDeclaredField("tableStatsCache");
    props.setAccessible(true);
    props.set(mockCqs, mockTableStatsCache);
    when(mockCqs.checkIfSysMutexExistsAndModifyTTLIfRequired(mockAdmin)).thenCallRealMethod();
    when(mockCqs.updateAndConfirmSplitPolicyForTask(SYS_TASK_TDB)).thenCallRealMethod();
    when(mockCqs.updateAndConfirmSplitPolicyForTask(SYS_TASK_TDB_SP)).thenCallRealMethod();
    when(mockCqs.getSysMutexTable()).thenCallRealMethod();
    when(mockCqs.getAdmin()).thenCallRealMethod();
    when(mockCqs.getTable(Mockito.any())).thenCallRealMethod();
    when(mockCqs.getTableIfExists(Mockito.any())).thenCallRealMethod();
    doCallRealMethod().when(mockCqs).dropTables(Mockito.any());
  }

  @Test
  public void testCQSIThreadPoolCreation()
    throws SQLException, NoSuchFieldException, IllegalAccessException {
    QueryServices mockQueryServices = Mockito.mock(QueryServices.class);
    ReadOnlyProps readOnlyProps = createCQSIThreadPoolReadOnlyProps();
    when(mockQueryServices.getProps()).thenReturn(readOnlyProps);
    ConnectionInfo mockConnectionInfo = Mockito.mock(ConnectionInfo.class);
    when(mockConnectionInfo.asProps()).thenReturn(readOnlyProps);
    Properties properties = new Properties();
    ConnectionQueryServicesImpl cqs =
      new ConnectionQueryServicesImpl(mockQueryServices, mockConnectionInfo, properties);
    Field props = cqs.getClass().getDeclaredField("threadPoolExecutor");
    props.setAccessible(true);
    ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) props.get(cqs);
    assertNotNull(threadPoolExecutor);
    assertEquals(readOnlyProps.getInt(CQSI_THREAD_POOL_CORE_POOL_SIZE, -1),
      threadPoolExecutor.getCorePoolSize());
    assertEquals(readOnlyProps.getInt(CQSI_THREAD_POOL_MAX_THREADS, -1),
      threadPoolExecutor.getMaximumPoolSize());
    assertEquals(LinkedBlockingQueue.class, threadPoolExecutor.getQueue().getClass());
    assertEquals(readOnlyProps.getInt(CQSI_THREAD_POOL_MAX_QUEUE, -1),
      threadPoolExecutor.getQueue().remainingCapacity());
    assertEquals(readOnlyProps.getInt(CQSI_THREAD_POOL_KEEP_ALIVE_SECONDS, -1),
      threadPoolExecutor.getKeepAliveTime(TimeUnit.SECONDS));
    assertTrue(threadPoolExecutor.allowsCoreThreadTimeOut());
  }

  private static ReadOnlyProps createCQSIThreadPoolReadOnlyProps() {
    Map<String, String> props = new HashMap<>();
    props.put(CQSI_THREAD_POOL_ENABLED, Boolean.toString(true));
    props.put(CQSI_THREAD_POOL_KEEP_ALIVE_SECONDS, Integer.toString(13));
    props.put(CQSI_THREAD_POOL_CORE_POOL_SIZE, Integer.toString(17));
    props.put(CQSI_THREAD_POOL_MAX_THREADS, Integer.toString(19));
    props.put(CQSI_THREAD_POOL_MAX_QUEUE, Integer.toString(23));
    props.put(CQSI_THREAD_POOL_ALLOW_CORE_THREAD_TIMEOUT, Boolean.toString(true));
    return new ReadOnlyProps(props);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testExceptionHandlingOnSystemNamespaceCreation() throws Exception {
    // Invoke the real methods for these two calls
    when(mockCqs.createSchema(any(List.class), anyString())).thenCallRealMethod();
    doCallRealMethod().when(mockCqs).ensureSystemTablesMigratedToSystemNamespace();
    // Do nothing for this method, just check that it was invoked later
    doNothing().when(mockCqs).createSysMutexTableIfNotExists(any(Admin.class));

    // Spoof out this call so that ensureSystemTablesUpgrade() will return-fast.
    when(mockCqs.getSystemTableNamesInDefaultNamespace(any(Admin.class)))
      .thenReturn(Collections.<TableName> emptyList());

    // Throw a special exception to check on later
    doThrow(PHOENIX_IO_EXCEPTION).when(mockCqs).ensureNamespaceCreated(anyString());

    // Make sure that ensureSystemTablesMigratedToSystemNamespace will try to migrate
    // the system tables.
    Map<String, String> props = new HashMap<>();
    props.put(QueryServices.IS_NAMESPACE_MAPPING_ENABLED, "true");
    when(mockCqs.getProps()).thenReturn(new ReadOnlyProps(props));
    mockCqs.ensureSystemTablesMigratedToSystemNamespace();

    // Should be called after upgradeSystemTables()
    // Proves that execution proceeded
    verify(mockCqs).getSystemTableNamesInDefaultNamespace(any());

    try {
      // Verifies that the exception is propagated back to the caller
      mockCqs.createSchema(Collections.<Mutation> emptyList(), "");
    } catch (PhoenixIOException e) {
      assertEquals(PHOENIX_IO_EXCEPTION, e);
    }
  }

  @Test
  public void testGetNextRegionStartKey() {
    RegionInfo mockHRegionInfo = org.mockito.Mockito.mock(RegionInfo.class);
    RegionInfo mockPrevHRegionInfo = org.mockito.Mockito.mock(RegionInfo.class);
    HRegionLocation mockRegionLocation = org.mockito.Mockito.mock(HRegionLocation.class);
    HRegionLocation mockPrevRegionLocation = org.mockito.Mockito.mock(HRegionLocation.class);
    ConnectionQueryServicesImpl mockCqsi = org.mockito.Mockito
      .mock(ConnectionQueryServicesImpl.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    byte[] corruptedStartAndEndKey = "0x3000".getBytes();
    byte[] corruptedDecreasingKey = "0x2999".getBytes();
    byte[] corruptedNewEndKey = "0x3001".getBytes();
    byte[] notCorruptedStartKey = "0x2999".getBytes();
    byte[] notCorruptedEndKey = "0x3000".getBytes();
    byte[] notCorruptedNewKey = "0x3001".getBytes();
    byte[] mockTableName = "dummyTable".getBytes();
    when(mockRegionLocation.getRegion()).thenReturn(mockHRegionInfo);
    when(mockHRegionInfo.getRegionName()).thenReturn(mockTableName);
    when(mockPrevRegionLocation.getRegion()).thenReturn(mockPrevHRegionInfo);
    when(mockPrevHRegionInfo.getRegionName()).thenReturn(mockTableName);

    // comparing the current regionInfo endKey is equal to the previous endKey
    // [0x3000, Ox3000) vs 0x3000
    GlobalClientMetrics.GLOBAL_HBASE_COUNTER_METADATA_INCONSISTENCY.getMetric().reset();
    when(mockHRegionInfo.getStartKey()).thenReturn(corruptedStartAndEndKey);
    when(mockHRegionInfo.getEndKey()).thenReturn(corruptedStartAndEndKey);
    when(mockPrevHRegionInfo.getEndKey()).thenReturn(corruptedStartAndEndKey);
    testGetNextRegionStartKey(mockCqsi, mockRegionLocation, corruptedStartAndEndKey, true,
      mockPrevRegionLocation);

    // comparing the current regionInfo endKey is less than previous endKey
    // [0x3000,0x2999) vs 0x3000
    GlobalClientMetrics.GLOBAL_HBASE_COUNTER_METADATA_INCONSISTENCY.getMetric().reset();
    when(mockHRegionInfo.getStartKey()).thenReturn(corruptedStartAndEndKey);
    when(mockHRegionInfo.getEndKey()).thenReturn(corruptedDecreasingKey);
    when(mockPrevHRegionInfo.getEndKey()).thenReturn(corruptedStartAndEndKey);
    testGetNextRegionStartKey(mockCqsi, mockRegionLocation, corruptedStartAndEndKey, true,
      mockPrevRegionLocation);

    // comparing the current regionInfo endKey is greater than the previous endKey
    // [0x2999,0x3001) vs 0x3000.
    GlobalClientMetrics.GLOBAL_HBASE_COUNTER_METADATA_INCONSISTENCY.getMetric().reset();
    when(mockHRegionInfo.getStartKey()).thenReturn(corruptedDecreasingKey);
    when(mockHRegionInfo.getEndKey()).thenReturn(corruptedNewEndKey);
    when(mockPrevHRegionInfo.getEndKey()).thenReturn(corruptedStartAndEndKey);
    testGetNextRegionStartKey(mockCqsi, mockRegionLocation, corruptedStartAndEndKey, true,
      mockPrevRegionLocation);

    // comparing the current regionInfo startKey is greater than the previous endKey leading to a
    // hole
    // [0x3000,0x3001) vs 0x2999
    GlobalClientMetrics.GLOBAL_HBASE_COUNTER_METADATA_INCONSISTENCY.getMetric().reset();
    when(mockHRegionInfo.getStartKey()).thenReturn(corruptedStartAndEndKey);
    when(mockHRegionInfo.getEndKey()).thenReturn(corruptedNewEndKey);
    when(mockPrevHRegionInfo.getEndKey()).thenReturn(corruptedDecreasingKey);
    testGetNextRegionStartKey(mockCqsi, mockRegionLocation, corruptedDecreasingKey, true,
      mockPrevRegionLocation);

    // comparing the current regionInfo startKey is less than the previous endKey leading to an
    // overlap
    // [0x2999,0x3001) vs 0x3000.
    GlobalClientMetrics.GLOBAL_HBASE_COUNTER_METADATA_INCONSISTENCY.getMetric().reset();
    when(mockHRegionInfo.getStartKey()).thenReturn(corruptedDecreasingKey);
    when(mockHRegionInfo.getEndKey()).thenReturn(corruptedNewEndKey);
    when(mockPrevHRegionInfo.getEndKey()).thenReturn(corruptedStartAndEndKey);
    testGetNextRegionStartKey(mockCqsi, mockRegionLocation, corruptedStartAndEndKey, true,
      mockPrevRegionLocation);

    // comparing the current regionInfo startKey is equal to the previous endKey
    // [0x3000,0x3001) vs 0x3000
    GlobalClientMetrics.GLOBAL_HBASE_COUNTER_METADATA_INCONSISTENCY.getMetric().reset();
    when(mockHRegionInfo.getStartKey()).thenReturn(corruptedStartAndEndKey);
    when(mockHRegionInfo.getEndKey()).thenReturn(notCorruptedNewKey);
    when(mockPrevHRegionInfo.getEndKey()).thenReturn(notCorruptedEndKey);
    testGetNextRegionStartKey(mockCqsi, mockRegionLocation, notCorruptedEndKey, false,
      mockPrevRegionLocation);

    // test EMPTY_START_ROW
    GlobalClientMetrics.GLOBAL_HBASE_COUNTER_METADATA_INCONSISTENCY.getMetric().reset();
    when(mockHRegionInfo.getStartKey()).thenReturn(HConstants.EMPTY_START_ROW);
    when(mockHRegionInfo.getEndKey()).thenReturn(notCorruptedEndKey);
    testGetNextRegionStartKey(mockCqsi, mockRegionLocation, HConstants.EMPTY_START_ROW, false,
      null);

    // test EMPTY_END_ROW
    GlobalClientMetrics.GLOBAL_HBASE_COUNTER_METADATA_INCONSISTENCY.getMetric().reset();
    when(mockHRegionInfo.getStartKey()).thenReturn(notCorruptedStartKey);
    when(mockHRegionInfo.getEndKey()).thenReturn(HConstants.EMPTY_END_ROW);
    testGetNextRegionStartKey(mockCqsi, mockRegionLocation, notCorruptedStartKey, false, null);
  }

  private void testGetNextRegionStartKey(ConnectionQueryServicesImpl mockCqsi,
    HRegionLocation mockRegionLocation, byte[] key, boolean isCorrupted,
    HRegionLocation mockPrevRegionLocation) {
    mockCqsi.getNextRegionStartKey(mockRegionLocation, key, mockPrevRegionLocation);

    assertEquals(isCorrupted ? 1 : 0,
      GlobalClientMetrics.GLOBAL_HBASE_COUNTER_METADATA_INCONSISTENCY.getMetric().getValue());
  }

  @Test
  public void testSysMutexCheckReturnsFalseWhenTableAbsent() throws Exception {
    // Override the getDescriptor() call to throw instead
    doThrow(new TableNotFoundException()).when(mockAdmin)
      .getDescriptor(TableName.valueOf(SYSTEM_MUTEX_NAME));
    doThrow(new TableNotFoundException()).when(mockAdmin)
      .getDescriptor(TableName.valueOf(SYSTEM_SCHEMA_NAME, SYSTEM_MUTEX_TABLE_NAME));
    assertFalse(mockCqs.checkIfSysMutexExistsAndModifyTTLIfRequired(mockAdmin));
  }

  @Test
  public void testSysMutexCheckModifiesTTLWhenWrong() throws Exception {
    // Set the wrong TTL
    TableDescriptor sysMutexTableDescWrongTTL =
      TableDescriptorBuilder.newBuilder(TableName.valueOf(SYSTEM_MUTEX_NAME))
        .setColumnFamily(ColumnFamilyDescriptorBuilder.newBuilder(SYSTEM_MUTEX_FAMILY_NAME_BYTES)
          .setTimeToLive(HConstants.FOREVER).build())
        .build();
    when(mockAdmin.getDescriptor(TableName.valueOf(SYSTEM_MUTEX_NAME)))
      .thenReturn(sysMutexTableDescWrongTTL);

    assertTrue(mockCqs.checkIfSysMutexExistsAndModifyTTLIfRequired(mockAdmin));
    verify(mockAdmin, Mockito.times(1)).modifyTable(sysMutexTableDescCorrectTTL);
  }

  @Test
  public void testSysMutexCheckDoesNotModifyTableDescWhenTTLCorrect() throws Exception {
    when(mockAdmin.getDescriptor(TableName.valueOf(SYSTEM_MUTEX_NAME)))
      .thenReturn(sysMutexTableDescCorrectTTL);

    assertTrue(mockCqs.checkIfSysMutexExistsAndModifyTTLIfRequired(mockAdmin));
    verify(mockAdmin, Mockito.times(0)).modifyTable(any(TableDescriptor.class));
  }

  @Test
  public void testSysTaskSplitPolicy() throws Exception {
    assertTrue(mockCqs.updateAndConfirmSplitPolicyForTask(SYS_TASK_TDB));
    assertFalse(mockCqs.updateAndConfirmSplitPolicyForTask(SYS_TASK_TDB));
  }

  @Test
  public void testSysTaskSplitPolicyWithError() {
    try {
      mockCqs.updateAndConfirmSplitPolicyForTask(SYS_TASK_TDB_SP);
      fail("Split policy for SYSTEM.TASK cannot be updated");
    } catch (SQLException e) {
      assertEquals("ERROR 908 (43M19): REGION SPLIT POLICY is incorrect."
        + " Region split policy for table TASK is expected to be "
        + "among: [null, org.apache.phoenix.schema.SystemTaskSplitPolicy]"
        + " , actual split policy: abc tableName=SYSTEM.TASK", e.getMessage());
    }
  }

  @Test
  public void testGetSysMutexTableWithName() throws Exception {
    when(mockAdmin.tableExists(any())).thenReturn(true);
    when(mockConn.getAdmin()).thenReturn(mockAdmin);
    when(mockConn.getTable(eq(TableName.valueOf("SYSTEM.MUTEX")))).thenReturn(mockTable);
    assertSame(mockCqs.getSysMutexTable(), mockTable);
    verify(mockAdmin, Mockito.times(1)).tableExists(any());
    verify(mockConn, Mockito.times(1)).getAdmin();
    verify(mockConn, Mockito.times(1)).getTable(eq(TableName.valueOf("SYSTEM.MUTEX")));
  }

  @Test
  public void testGetSysMutexTableWithNamespace() throws Exception {
    when(mockAdmin.tableExists(any())).thenReturn(false);
    when(mockConn.getAdmin()).thenReturn(mockAdmin);
    when(mockConn.getTable(eq(TableName.valueOf("SYSTEM:MUTEX")))).thenReturn(mockTable);
    assertSame(mockCqs.getSysMutexTable(), mockTable);
    verify(mockAdmin, Mockito.times(1)).tableExists(any());
    verify(mockConn, Mockito.times(1)).getAdmin();
    verify(mockConn, Mockito.times(1)).getTable(eq(TableName.valueOf("SYSTEM:MUTEX")));
  }

  @Test
  public void testDropTablesAlreadyDisabled() throws Exception {
    when(mockConn.getAdmin()).thenReturn(mockAdmin);
    doThrow(new TableNotEnabledException()).when(mockAdmin).disableTable(any());
    doNothing().when(mockAdmin).deleteTable(any());
    mockCqs.dropTables(Collections.singletonList("TEST_TABLE".getBytes(StandardCharsets.UTF_8)));
    verify(mockAdmin, Mockito.times(1)).disableTable(TableName.valueOf("TEST_TABLE"));
    verify(mockAdmin, Mockito.times(1)).deleteTable(TableName.valueOf("TEST_TABLE"));
    verify(mockConn).getAdmin();
  }

  @Test
  public void testDropTablesTableEnabled() throws Exception {
    when(mockConn.getAdmin()).thenReturn(mockAdmin);
    doNothing().when(mockAdmin).disableTable(any());
    doNothing().when(mockAdmin).deleteTable(any());
    doNothing().when(mockTableStatsCache).invalidateAll();
    mockCqs.dropTables(Collections.singletonList("TEST_TABLE".getBytes(StandardCharsets.UTF_8)));
    verify(mockAdmin, Mockito.times(1)).disableTable(TableName.valueOf("TEST_TABLE"));
    verify(mockAdmin, Mockito.times(1)).deleteTable(TableName.valueOf("TEST_TABLE"));
    verify(mockConn).getAdmin();
  }
}
