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
package org.apache.phoenix.hbase.index.write;

import static org.apache.phoenix.query.BaseTest.setUpConfigForMiniCluster;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessor;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.RegionObserver;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.regionserver.Region;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.JVMClusterUtil.RegionServerThread;
import org.apache.hadoop.hbase.wal.WALKey;
import org.apache.phoenix.end2end.ServerMetadataCacheTestImpl;
import org.apache.phoenix.hbase.index.IndexTableName;
import org.apache.phoenix.hbase.index.IndexTestingUtils;
import org.apache.phoenix.hbase.index.Indexer;
import org.apache.phoenix.hbase.index.covered.ColumnGroup;
import org.apache.phoenix.hbase.index.covered.CoveredColumn;
import org.apache.phoenix.hbase.index.covered.CoveredColumnIndexSpecifierBuilder;
import org.apache.phoenix.hbase.index.table.HTableInterfaceReference;
import org.apache.phoenix.hbase.index.util.IndexManagementUtil;
import org.apache.phoenix.hbase.index.util.TestIndexManagementUtil;
import org.apache.phoenix.hbase.index.write.recovery.PerRegionIndexWriteCache;
import org.apache.phoenix.hbase.index.write.recovery.StoreFailuresInCachePolicy;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.phoenix.thirdparty.com.google.common.collect.Multimap;

/**
 * When a regionserver crashes, its WAL is split and then replayed to the server. If the index
 * region was present on the same server, we have to make a best effort to not kill the server for
 * not succeeding on index writes while the index region is coming up.
 */

public class TestWALRecoveryCaching {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestWALRecoveryCaching.class);
  private static final long ONE_SEC = 1000;
  private static final long ONE_MIN = 60 * ONE_SEC;
  private static final long TIMEOUT = ONE_MIN;

  @Rule
  public IndexTableName testTable = new IndexTableName();

  private String getIndexTableName() {
    return this.testTable.getTableNameString() + "_index";
  }

  // -----------------------------------------------------------------------------------------------
  // Warning! The classes here rely on this static. Adding multiple tests to this class and running
  // them concurrently could have unexpected results (including, but not limited to, odd failures
  // and flapping tests).
  // -----------------------------------------------------------------------------------------------
  private static CountDownLatch allowIndexTableToRecover;

  public static class IndexTableBlockingReplayObserver
    implements RegionObserver, RegionCoprocessor {

    @Override
    public Optional<RegionObserver> getRegionObserver() {
      return Optional.of(this);
    }

    @Override
    public void preWALRestore(
      org.apache.hadoop.hbase.coprocessor.ObserverContext<
        ? extends RegionCoprocessorEnvironment> ctx,
      org.apache.hadoop.hbase.client.RegionInfo info, WALKey logKey,
      org.apache.hadoop.hbase.wal.WALEdit logEdit) throws IOException {
      try {
        LOGGER.debug("Restoring logs for index table");
        if (allowIndexTableToRecover != null) {
          allowIndexTableToRecover.await();
          LOGGER.debug("Completed index table recovery wait latch");
        }
      } catch (InterruptedException e) {
        Assert.fail("Should not be interrupted while waiting to allow the index to restore WALs.");
      }
    }
  }

  public static class ReleaseLatchOnFailurePolicy extends StoreFailuresInCachePolicy {

    /**
     */
    public ReleaseLatchOnFailurePolicy(PerRegionIndexWriteCache failedIndexEdits) {
      super(failedIndexEdits);
    }

    @Override
    public void handleFailure(Multimap<HTableInterfaceReference, Mutation> attempted,
      Exception cause) throws IOException {
      LOGGER.debug("Found index update failure!");
      if (allowIndexTableToRecover != null) {
        LOGGER.info("failed index write on WAL recovery - allowing index table to be restored.");
        allowIndexTableToRecover.countDown();
      }
      super.handleFailure(attempted, cause);
    }

  }

  // TODO: Jesse to fix
  @SuppressWarnings("deprecation")
  @Ignore("Configuration issue - valid test, just needs fixing")
  @Test
  public void testWaitsOnIndexRegionToReload() throws Exception {
    HBaseTestingUtility util = new HBaseTestingUtility();
    Configuration conf = util.getConfiguration();
    setUpConfigForMiniCluster(conf);

    // setup other useful stats
    IndexTestingUtils.setupConfig(conf);
    conf.setBoolean(Indexer.CHECK_VERSION_CONF_KEY, false);

    // make sure everything is setup correctly
    IndexManagementUtil.ensureMutableIndexingCorrectlyConfigured(conf);

    // start the cluster with 2 rs
    util.startMiniCluster(2);

    Admin admin = util.getHBaseAdmin();
    // setup the index
    byte[] family = Bytes.toBytes("family");
    byte[] qual = Bytes.toBytes("qualifier");
    byte[] nonIndexedFamily = Bytes.toBytes("nonIndexedFamily");
    String indexedTableName = getIndexTableName();
    ColumnGroup columns = new ColumnGroup(indexedTableName);
    columns.add(new CoveredColumn(family, qual));
    CoveredColumnIndexSpecifierBuilder builder = new CoveredColumnIndexSpecifierBuilder();
    builder.addIndexGroup(columns);

    // create the primary table w/ indexing enabled
    TableDescriptor primaryTable =
      TableDescriptorBuilder.newBuilder(TableName.valueOf(testTable.getTableName()))
        .addColumnFamily(ColumnFamilyDescriptorBuilder.of(family))
        .addColumnFamily(ColumnFamilyDescriptorBuilder.of(nonIndexedFamily)).build();
    builder.addArbitraryConfigForTesting(Indexer.RecoveryFailurePolicyKeyForTesting,
      ReleaseLatchOnFailurePolicy.class.getName());
    builder.build(primaryTable);
    admin.createTable(primaryTable);

    // create the index table
    TableDescriptorBuilder indexTableBuilder =
      TableDescriptorBuilder.newBuilder(TableName.valueOf(Bytes.toBytes(getIndexTableName())))
        .addCoprocessor(IndexTableBlockingReplayObserver.class.getName());
    TestIndexManagementUtil.createIndexTable(admin, indexTableBuilder);

    // figure out where our tables live
    ServerName shared = ensureTablesLiveOnSameServer(util.getMiniHBaseCluster(),
      Bytes.toBytes(indexedTableName), testTable.getTableName());

    // load some data into the table
    Put p = new Put(Bytes.toBytes("row"));
    p.addColumn(family, qual, Bytes.toBytes("value"));
    Connection hbaseConn = ConnectionFactory.createConnection(conf);
    Table primary =
      hbaseConn.getTable(org.apache.hadoop.hbase.TableName.valueOf(testTable.getTableName()));
    primary.put(p);

    // turn on the recovery latch
    allowIndexTableToRecover = new CountDownLatch(1);

    // kill the server where the tables live - this should trigger distributed log splitting
    // find the regionserver that matches the passed server
    List<HRegion> online = new ArrayList<HRegion>();
    online.addAll(
      getRegionsFromServerForTable(util.getMiniHBaseCluster(), shared, testTable.getTableName()));
    online.addAll(getRegionsFromServerForTable(util.getMiniHBaseCluster(), shared,
      Bytes.toBytes(indexedTableName)));

    // log all the current state of the server
    LOGGER.info("Current Server/Region paring: ");
    for (RegionServerThread t : util.getMiniHBaseCluster().getRegionServerThreads()) {
      // check all the conditions for the server to be done
      HRegionServer server = t.getRegionServer();
      if (server.isStopping() || server.isStopped() || server.isAborted()) {
        LOGGER.info("\t== Offline: " + server.getServerName());
        continue;
      }

      List<HRegion> regions = server.getRegions();
      LOGGER.info("\t" + server.getServerName() + " regions: " + regions);
    }

    LOGGER.debug("Killing server " + shared);
    util.getMiniHBaseCluster().killRegionServer(shared);
    LOGGER.debug("Waiting on server " + shared + "to die");
    util.getMiniHBaseCluster().waitForRegionServerToStop(shared, TIMEOUT);
    // force reassign the regions from the table
    // LOGGER.debug("Forcing region reassignment from the killed server: " + shared);
    // for (HRegion region : online) {
    // util.getMiniHBaseCluster().getMaster().assign(region.getRegionName());
    // }
    System.out.println(" ====== Killed shared server ==== ");

    // make a second put that (1), isn't indexed, so we can be sure of the index state and (2)
    // ensures that our table is back up
    Put p2 = new Put(p.getRow());
    p2.addColumn(nonIndexedFamily, Bytes.toBytes("Not indexed"),
      Bytes.toBytes("non-indexed value"));
    primary.put(p2);

    // make sure that we actually failed the write once (within a 5 minute window)
    assertTrue("Didn't find an error writing to index table within timeout!",
      allowIndexTableToRecover.await(ONE_MIN * 5, TimeUnit.MILLISECONDS));

    // scan the index to make sure it has the one entry, (that had to be replayed from the WAL,
    // since we hard killed the server)
    Scan s = new Scan();
    Table index =
      hbaseConn.getTable(org.apache.hadoop.hbase.TableName.valueOf(getIndexTableName()));
    ResultScanner scanner = index.getScanner(s);
    int count = 0;
    for (Result r : scanner) {
      LOGGER.info("Got index table result:" + r);
      count++;
    }
    assertEquals("Got an unexpected found of index rows", 1, count);

    // cleanup
    scanner.close();
    index.close();
    primary.close();
    ServerMetadataCacheTestImpl.resetCache();
    util.shutdownMiniCluster();
  }

  /**
   */
  private List<HRegion> getRegionsFromServerForTable(MiniHBaseCluster cluster, ServerName server,
    byte[] table) {
    List<HRegion> online = Collections.emptyList();
    for (RegionServerThread rst : cluster.getRegionServerThreads()) {
      // if its the server we are going to kill, get the regions we want to reassign
      if (rst.getRegionServer().getServerName().equals(server)) {
        online = rst.getRegionServer().getRegions(org.apache.hadoop.hbase.TableName.valueOf(table));
        break;
      }
    }
    return online;
  }

  /**
   */
  private ServerName ensureTablesLiveOnSameServer(MiniHBaseCluster cluster, byte[] indexTable,
    byte[] primaryTable) throws Exception {

    ServerName shared = getSharedServer(cluster, indexTable, primaryTable);
    boolean tryIndex = true;
    while (shared == null) {

      // start killing servers until we get an overlap
      Set<ServerName> servers;
      byte[] table = null;
      // switch which server we kill each time to get region movement
      if (tryIndex) {
        table = indexTable;
      } else {
        table = primaryTable;
      }
      servers = getServersForTable(cluster, table);
      tryIndex = !tryIndex;
      for (ServerName server : servers) {
        // find the regionserver that matches the passed server
        List<HRegion> online = getRegionsFromServerForTable(cluster, server, table);

        LOGGER.info("Shutting down and reassigning regions from " + server);
        cluster.stopRegionServer(server);
        cluster.waitForRegionServerToStop(server, TIMEOUT);

        // force reassign the regions from the table
        for (Region region : online) {
          cluster.getMaster().getAssignmentManager().assign(region.getRegionInfo());
        }

        LOGGER.info("Starting region server:" + server.getHostname());
        cluster.startRegionServer(server.getHostname(), server.getPort());

        cluster.waitForRegionServerToStart(server.getHostname(), server.getPort(), TIMEOUT);

        // start a server to get back to the base number of servers
        LOGGER.info("STarting server to replace " + server);
        cluster.startRegionServer();
        break;
      }

      shared = getSharedServer(cluster, indexTable, primaryTable);
    }
    return shared;
  }

  /**
   */
  private ServerName getSharedServer(MiniHBaseCluster cluster, byte[] indexTable,
    byte[] primaryTable) throws Exception {
    Set<ServerName> indexServers = getServersForTable(cluster, indexTable);
    Set<ServerName> primaryServers = getServersForTable(cluster, primaryTable);

    Set<ServerName> joinSet = new HashSet<ServerName>(indexServers);
    joinSet.addAll(primaryServers);
    // if there is already an overlap, then find it and return it
    if (joinSet.size() < indexServers.size() + primaryServers.size()) {
      // find the first overlapping server
      for (ServerName server : joinSet) {
        if (indexServers.contains(server) && primaryServers.contains(server)) {
          return server;
        }
      }
      throw new RuntimeException(
        "Couldn't find a matching server on which both the primary and index table live, "
          + "even though they have overlapping server sets");
    }
    return null;
  }

  private Set<ServerName> getServersForTable(MiniHBaseCluster cluster, byte[] table)
    throws Exception {
    Set<ServerName> indexServers = new HashSet<ServerName>();
    for (Region region : cluster.getRegions(table)) {
      indexServers
        .add(cluster.getServerHoldingRegion(null, region.getRegionInfo().getRegionName()));
    }
    return indexServers;
  }
}
