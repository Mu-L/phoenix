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
package org.apache.hadoop.hbase.regionserver.wal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptor;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.RegionInfoBuilder;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.regionserver.RegionServerAccounting;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.CommonFSUtils;
import org.apache.hadoop.hbase.wal.WAL;
import org.apache.hadoop.hbase.wal.WALFactory;
import org.apache.hadoop.hbase.wal.WALSplitter;
import org.apache.phoenix.end2end.NeedsOwnMiniClusterTest;
import org.apache.phoenix.hbase.index.IndexTableName;
import org.apache.phoenix.hbase.index.IndexTestingUtils;
import org.apache.phoenix.hbase.index.covered.ColumnGroup;
import org.apache.phoenix.hbase.index.covered.CoveredColumn;
import org.apache.phoenix.hbase.index.covered.CoveredColumnIndexSpecifierBuilder;
import org.apache.phoenix.hbase.index.util.TestIndexManagementUtil;
import org.apache.phoenix.query.BaseTest;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.util.ConfigUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * For pre-0.94.9 instances, this class tests correctly deserializing WALEdits w/o compression. Post
 * 0.94.9 we can support a custom {@link WALCellCodec} which handles reading/writing the compressed
 * edits.
 * <p>
 * Most of the underlying work (creating/splitting the WAL, etc) is from
 * org.apache.hadoop.hhbase.regionserver.wal.TestWALReplay, copied here for completeness and ease of
 * use.
 * <p>
 * This test should only have a single test - otherwise we will start/stop the minicluster multiple
 * times, which is probably not what you want to do (mostly because its so much effort).
 */
@Category(NeedsOwnMiniClusterTest.class)
@Ignore
public class WALReplayWithIndexWritesAndCompressedWALIT {

  public static final Logger LOGGER =
    LoggerFactory.getLogger(WALReplayWithIndexWritesAndCompressedWALIT.class);
  @Rule
  public IndexTableName table = new IndexTableName();
  private String INDEX_TABLE_NAME = table.getTableNameString() + "_INDEX";

  final HBaseTestingUtility UTIL = new HBaseTestingUtility();
  private Path hbaseRootDir = null;
  private Path oldLogDir;
  private Path logDir;
  private FileSystem fs;
  private Configuration conf;

  @Before
  public void setUp() throws Exception {
    setupCluster();
    this.conf = HBaseConfiguration.create(UTIL.getConfiguration());
    this.conf.setBoolean(QueryServices.INDEX_FAILURE_THROW_EXCEPTION_ATTRIB, false);
    this.fs = UTIL.getDFSCluster().getFileSystem();
    this.hbaseRootDir = new Path(this.conf.get(HConstants.HBASE_DIR));
    this.oldLogDir = new Path(this.hbaseRootDir, HConstants.HREGION_OLDLOGDIR_NAME);
    this.logDir = new Path(this.hbaseRootDir, HConstants.HREGION_LOGDIR_NAME);
  }

  private void setupCluster() throws Exception {
    configureCluster();
    startCluster();
  }

  protected void configureCluster() throws Exception {
    Configuration conf = UTIL.getConfiguration();
    setDefaults(conf);

    // enable WAL compression
    conf.setBoolean(HConstants.ENABLE_WAL_COMPRESSION, true);
    // set replication required parameter
    ConfigUtil.setReplicationConfigIfAbsent(conf);
  }

  protected final void setDefaults(Configuration conf) {
    // make sure writers fail quickly
    conf.setInt(HConstants.HBASE_CLIENT_RETRIES_NUMBER, 3);
    conf.setInt(HConstants.HBASE_CLIENT_PAUSE, 1000);
    conf.setInt("zookeeper.recovery.retry", 3);
    conf.setInt("zookeeper.recovery.retry.intervalmill", 100);
    conf.setInt(HConstants.ZK_SESSION_TIMEOUT, 30000);
    conf.setInt(HConstants.HBASE_RPC_TIMEOUT_KEY, 5000);
    // enable appends
    conf.setBoolean("dfs.support.append", true);
    IndexTestingUtils.setupConfig(conf);
  }

  protected void startCluster() throws Exception {
    UTIL.startMiniDFSCluster(3);
    UTIL.startMiniZKCluster();

    Path hbaseRootDir = UTIL.getDFSCluster().getFileSystem().makeQualified(new Path("/hbase"));
    LOGGER.info("hbase.rootdir=" + hbaseRootDir);
    UTIL.getConfiguration().set(HConstants.HBASE_DIR, hbaseRootDir.toString());
    UTIL.startMiniHBaseCluster(1, 1);
  }

  @After
  public void tearDown() throws Exception {
    boolean refCountLeaked = BaseTest.isAnyStoreRefCountLeaked(UTIL.getAdmin());
    UTIL.shutdownMiniHBaseCluster();
    UTIL.shutdownMiniDFSCluster();
    UTIL.shutdownMiniZKCluster();
    assertFalse("refCount leaked", refCountLeaked);
  }

  private void deleteDir(final Path p) throws IOException {
    if (this.fs.exists(p)) {
      if (!this.fs.delete(p, true)) {
        throw new IOException("Failed remove of " + p);
      }
    }
  }

  /**
   * Test writing edits into an region, closing it, splitting logs, opening Region again. Verify
   * seqids.
   * @throws Exception on failure
   */
  @Test
  public void testReplayEditsWrittenViaHRegion() throws Exception {
    final String tableNameStr = "testReplayEditsWrittenViaHRegion";
    final RegionInfo hri =
      RegionInfoBuilder.newBuilder(TableName.valueOf(tableNameStr)).setSplit(false).build();
    final Path basedir = CommonFSUtils.getTableDir(hbaseRootDir, TableName.valueOf(tableNameStr));
    deleteDir(basedir);
    final TableDescriptor htd = createBasic3FamilyHTD(tableNameStr);

    // setup basic indexing for the table
    // enable indexing to a non-existant index table
    byte[] family = new byte[] { 'a' };
    ColumnGroup fam1 = new ColumnGroup(INDEX_TABLE_NAME);
    fam1.add(new CoveredColumn(family, CoveredColumn.ALL_QUALIFIERS));
    CoveredColumnIndexSpecifierBuilder builder = new CoveredColumnIndexSpecifierBuilder();
    builder.addIndexGroup(fam1);
    builder.build(htd);
    WALFactory walFactory = new WALFactory(this.conf, "localhost,1234");

    WAL wal = createWAL(this.conf, walFactory);
    // create the region + its WAL
    HRegion region0 = HRegion.createHRegion(hri, hbaseRootDir, this.conf, htd, wal); // FIXME: Uses
                                                                                     // private type
    region0.close();
    region0.getWAL().close();

    HRegionServer mockRS = Mockito.mock(HRegionServer.class);
    // mock out some of the internals of the RSS, so we can run CPs
    when(mockRS.getWAL(null)).thenReturn(wal);
    RegionServerAccounting rsa = Mockito.mock(RegionServerAccounting.class);
    when(mockRS.getRegionServerAccounting()).thenReturn(rsa);
    ServerName mockServerName = Mockito.mock(ServerName.class);
    when(mockServerName.getServerName()).thenReturn(tableNameStr + ",1234");
    when(mockRS.getServerName()).thenReturn(mockServerName);
    HRegion region =
      spy(HRegion.createHRegion(hri, hbaseRootDir, this.conf, htd, wal, true, mockRS));

    // make an attempted write to the primary that should also be indexed
    byte[] rowkey = Bytes.toBytes("indexed_row_key");
    Put p = new Put(rowkey);
    p.addColumn(family, Bytes.toBytes("qual"), Bytes.toBytes("value"));
    region.put(p);

    // we should then see the server go down
    Mockito.verify(mockRS, Mockito.times(1)).abort(Mockito.anyString(),
      Mockito.any(Exception.class));

    // then create the index table so we are successful on WAL replay
    TestIndexManagementUtil.createIndexTable(UTIL.getAdmin(), INDEX_TABLE_NAME);

    // run the WAL split and setup the region
    runWALSplit(this.conf, walFactory);
    WAL wal2 = createWAL(this.conf, walFactory);
    HRegion region1 = HRegion.createHRegion(hri, hbaseRootDir, this.conf, htd, wal, true, mockRS);

    org.apache.hadoop.hbase.client.Connection hbaseConn =
      ConnectionFactory.createConnection(UTIL.getConfiguration());

    // now check to ensure that we wrote to the index table
    Table index = hbaseConn.getTable(TableName.valueOf(INDEX_TABLE_NAME));
    int indexSize = getKeyValueCount(index);
    assertEquals("Index wasn't propertly updated from WAL replay!", 1, indexSize);
    Get g = new Get(rowkey);
    final Result result = region1.get(g);
    assertEquals("Primary region wasn't updated from WAL replay!", 1, result.size());

    // cleanup the index table
    Admin admin = UTIL.getAdmin();
    admin.disableTable(TableName.valueOf(INDEX_TABLE_NAME));
    admin.deleteTable(TableName.valueOf(INDEX_TABLE_NAME));
    admin.close();
  }

  /**
   * Create simple HTD with three families: 'a', 'b', and 'c'
   * @param tableName name of the table descriptor
   */
  private TableDescriptor createBasic3FamilyHTD(final String tableName) {
    TableDescriptorBuilder tableBuilder =
      TableDescriptorBuilder.newBuilder(TableName.valueOf(tableName));
    ColumnFamilyDescriptor a = ColumnFamilyDescriptorBuilder.of(Bytes.toBytes("a"));
    tableBuilder.setColumnFamily(a);
    ColumnFamilyDescriptor b = ColumnFamilyDescriptorBuilder.of(Bytes.toBytes("b"));
    tableBuilder.setColumnFamily(b);
    ColumnFamilyDescriptor c = ColumnFamilyDescriptorBuilder.of(Bytes.toBytes("c"));
    tableBuilder.setColumnFamily(c);
    return tableBuilder.build();
  }

  /*
   * @return WAL with retries set down from 5 to 1 only.
   */
  private WAL createWAL(final Configuration c, WALFactory walFactory) throws IOException {
    WAL wal = walFactory.getWAL(null);

    // Set down maximum recovery so we dfsclient doesn't linger retrying something
    // long gone.
    HBaseTestingUtility.setMaxRecoveryErrorCount(((FSHLog) wal).getOutputStream(), 1);
    return wal;
  }

  /*
   * Run the split. Verify only single split file made.
   * @return The single split file made
   */
  private Path runWALSplit(final Configuration c, WALFactory walFactory) throws IOException {
    FileSystem fs = FileSystem.get(c);

    List<Path> splits = WALSplitter.split(this.hbaseRootDir,
      new Path(this.logDir, "localhost,1234"), this.oldLogDir, fs, c, walFactory);
    // Split should generate only 1 file since there's only 1 region
    assertEquals("splits=" + splits, 1, splits.size());
    // Make sure the file exists
    assertTrue(fs.exists(splits.get(0)));
    LOGGER.info("Split file=" + splits.get(0));
    return splits.get(0);
  }

  @SuppressWarnings("deprecation")
  private int getKeyValueCount(Table table) throws IOException {
    Scan scan = new Scan();
    scan.setMaxVersions(Integer.MAX_VALUE - 1);

    ResultScanner results = table.getScanner(scan);
    int count = 0;
    for (Result res : results) {
      count += res.listCells().size();
      LOGGER.debug(count + ") " + res);
    }
    results.close();

    return count;
  }
}
