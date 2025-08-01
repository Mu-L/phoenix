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
package org.apache.phoenix.end2end;

import static org.apache.phoenix.schema.PTable.QualifierEncodingScheme.NON_ENCODED_QUALIFIERS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.util.CDCUtil;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.SchemaUtil;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
@Category(ParallelStatsDisabledTest.class)
public class CDCDefinitionIT extends CDCBaseIT {
  private final boolean forView;

  public CDCDefinitionIT(boolean forView) {
    this.forView = forView;
  }

  @Parameterized.Parameters(name = "forView={0}")
  public static synchronized Collection<Boolean[]> data() {
    return Arrays.asList(new Boolean[][] { { false }, { true } });
  }

  @Test
  public void testCreate() throws Exception {
    Connection conn = newConnection();
    String tableName = generateUniqueName();
    String datatableName = tableName;
    conn.createStatement().execute("CREATE TABLE  " + tableName + " ( k INTEGER PRIMARY KEY,"
      + " v1 INTEGER," + " v2 DATE) TTL=100");
    if (forView) {
      String viewName = generateUniqueName();
      conn.createStatement().execute("CREATE VIEW " + viewName + " AS SELECT * FROM " + tableName);
      tableName = viewName;
    }
    String cdcName = generateUniqueName();
    String cdc_sql;

    try {
      conn.createStatement().execute("CREATE CDC " + cdcName + " ON NON_EXISTENT_TABLE");
      fail("Expected to fail due to non-existent table");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.TABLE_UNDEFINED.getErrorCode(), e.getErrorCode());
    }

    cdc_sql = "CREATE CDC " + cdcName + " ON " + tableName;
    createCDC(conn, cdc_sql, null);
    assertCDCState(conn, cdcName, null, 3);
    assertNoResults(conn, cdcName);

    try {
      conn.createStatement().execute(cdc_sql);
      fail("Expected to fail due to duplicate index");
    } catch (SQLException e) {
      if (forView) {
        assertEquals(SQLExceptionCode.TABLE_ALREADY_EXIST.getErrorCode(), e.getErrorCode());
        assertTrue(e.getMessage().endsWith(cdcName));
      } else {
        // we only support Streams for tables as of now
        assertEquals(SQLExceptionCode.CDC_ALREADY_ENABLED.getErrorCode(), e.getErrorCode());
      }
    }

    try {
      conn.createStatement().execute(
        "CREATE CDC IF NOT EXISTS " + cdcName + " ON " + tableName + " INCLUDE (pre, post)");
    } catch (SQLException e) {
      // when we replace CREATE CDC with ENABLE CDC, we will not have IF NOT EXISTS usage
      if (!forView) {
        assertEquals(SQLExceptionCode.CDC_ALREADY_ENABLED.getErrorCode(), e.getErrorCode());
      }
    }

    cdcName = generateUniqueName();
    cdc_sql = "CREATE CDC " + cdcName + " ON " + tableName + " INCLUDE (pre, post)";
    try {
      createCDC(conn, cdc_sql);
    } catch (SQLException e) {
      if (!forView) {
        assertEquals(SQLExceptionCode.CDC_ALREADY_ENABLED.getErrorCode(), e.getErrorCode());
      } else {
        Assert.fail("Multiple CDCs should be allowed on views.");
      }
    }

    conn.close();
  }

  @Test
  public void testCreateDropCaseSensitiveTable() throws Exception {
    Connection conn = newConnection();
    String tableName = "\"" + generateUniqueName().toLowerCase() + "\"";
    conn.createStatement().execute("CREATE TABLE  " + tableName + " ( k INTEGER PRIMARY KEY,"
      + " v1 INTEGER," + " v2 DATE) TTL=100");
    if (forView) {
      String viewName = "\"" + generateUniqueName().toLowerCase() + "\"";
      conn.createStatement().execute("CREATE VIEW " + viewName + " AS SELECT * FROM " + tableName);
      tableName = viewName;
    }
    String cdcName = "\"" + generateUniqueName().toLowerCase() + "\"";
    String cdc_sql = "CREATE CDC " + cdcName + " ON " + tableName;
    conn.createStatement().execute(cdc_sql);
    conn.createStatement().executeQuery("SELECT * FROM " + cdcName);

    String drop_sql = forView ? "DROP VIEW " + tableName : "DROP TABLE " + tableName;
    conn.createStatement().execute(drop_sql);
    String drop_cdc_sql = "DROP CDC " + cdcName + " ON " + tableName;
    try {
      conn.createStatement().execute(drop_cdc_sql);
      fail("Expected to fail as cdc table doesn't exist");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.TABLE_UNDEFINED.getErrorCode(), e.getErrorCode());
      assertTrue(e.getMessage().endsWith(SchemaUtil.getUnEscapedFullName(cdcName)));
    }
  }

  @Test
  public void testCreateDropCaseSensitiveSchemaAndTable() throws Exception {
    Connection conn = newConnection();
    String schemaName = "\"" + generateUniqueName().toLowerCase() + "\"";
    String tableName =
      SchemaUtil.getTableName(schemaName, "\"" + generateUniqueName().toLowerCase() + "\"");
    conn.createStatement().execute("CREATE TABLE  " + tableName + " ( k INTEGER PRIMARY KEY,"
      + " v1 INTEGER," + " v2 DATE) TTL=100");
    if (forView) {
      String viewName =
        SchemaUtil.getTableName(schemaName, "\"" + generateUniqueName().toLowerCase() + "\"");
      conn.createStatement().execute("CREATE VIEW " + viewName + " AS SELECT * FROM " + tableName);
      tableName = viewName;
    }
    String cdcName = "\"" + generateUniqueName().toLowerCase() + "\"";
    String cdc_sql = "CREATE CDC " + cdcName + " ON " + tableName;
    conn.createStatement().execute(cdc_sql);
    String cdcFullName = SchemaUtil.getTableName(schemaName, cdcName);
    conn.createStatement().executeQuery("SELECT * FROM " + cdcFullName);

    String drop_sql = forView ? "DROP VIEW " + tableName : "DROP TABLE " + tableName;
    conn.createStatement().execute(drop_sql);
    String drop_cdc_sql = "DROP CDC " + cdcName + " ON " + tableName;
    try {
      conn.createStatement().execute(drop_cdc_sql);
      fail("Expected to fail as cdc table doesn't exist");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.TABLE_UNDEFINED.getErrorCode(), e.getErrorCode());
      assertTrue(e.getMessage().endsWith(SchemaUtil.getUnEscapedFullName(cdcName)));
    }
  }

  @Test
  public void testCreateWithSchemaName() throws Exception {
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String schemaName = generateUniqueName();
    String tableName = SchemaUtil.getTableName(schemaName, generateUniqueName());
    String datatableName = tableName;
    conn.createStatement().execute(
      "CREATE TABLE  " + tableName + " ( k INTEGER PRIMARY KEY," + " v1 INTEGER, v2 DATE)");
    if (forView) {
      String viewName = SchemaUtil.getTableName(schemaName, generateUniqueName());
      conn.createStatement().execute("CREATE VIEW " + viewName + " AS SELECT * FROM " + tableName);
      tableName = viewName;
    }
    String cdcName = generateUniqueName();
    String cdc_sql;

    try {
      conn.createStatement().execute("CREATE CDC " + cdcName + " ON NON_EXISTENT_TABLE");
      fail("Expected to fail due to non-existent table");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.TABLE_UNDEFINED.getErrorCode(), e.getErrorCode());
    }

    cdc_sql = "CREATE CDC " + cdcName + " ON " + tableName;
    createCDC(conn, cdc_sql);
    assertCDCState(conn, cdcName, null, 3);
    assertPTable(cdcName, null, tableName, datatableName);
  }

  @Test
  public void testCreateCDCMultitenant() throws Exception {
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String tableName = generateUniqueName();
    conn.createStatement()
      .execute("CREATE TABLE  " + tableName + " (tenantId INTEGER NOT NULL, k INTEGER NOT NULL,"
        + " v1 INTEGER, v2 DATE, " + "CONSTRAINT pk PRIMARY KEY (tenantId, k)) MULTI_TENANT=true");
    String cdcName = generateUniqueName();
    conn.createStatement().execute("CREATE CDC " + cdcName + " ON " + tableName);

    PTable indexTable = PhoenixRuntime.getTable(conn, CDCUtil.getCDCIndexName(cdcName));
    assertEquals(true, indexTable.isMultiTenant());
    List<PColumn> idxPkColumns = indexTable.getPKColumns();
    assertEquals(":TENANTID", idxPkColumns.get(0).getName().getString());
    assertEquals(": PARTITION_ID()", idxPkColumns.get(1).getName().getString());
    assertEquals(": PHOENIX_ROW_TIMESTAMP()", idxPkColumns.get(2).getName().getString());
    assertEquals(":K", idxPkColumns.get(3).getName().getString());

    PTable cdcTable = PhoenixRuntime.getTable(conn, cdcName);
    assertEquals(true, cdcTable.isMultiTenant());
    List<PColumn> cdcPkColumns = cdcTable.getPKColumns();
    assertEquals("TENANTID", cdcPkColumns.get(0).getName().getString());
    assertEquals("K", cdcPkColumns.get(1).getName().getString());
  }

  @Test
  public void testCreateWithNonDefaultColumnEncoding() throws Exception {
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String tableName = generateUniqueName();
    conn.createStatement().execute(
      "CREATE TABLE  " + tableName + " ( k INTEGER PRIMARY KEY," + " v1 INTEGER," + " v2 DATE)");
    if (forView) {
      String viewName = generateUniqueName();
      conn.createStatement().execute("CREATE VIEW " + viewName + " AS SELECT * FROM " + tableName);
      tableName = viewName;
    }
    String cdcName = generateUniqueName();

    conn.createStatement()
      .execute("CREATE CDC " + cdcName + " ON " + tableName + " COLUMN_ENCODED_BYTES="
        + String.valueOf(NON_ENCODED_QUALIFIERS.getSerializedMetadataValue()));
    PTable indexTable = PhoenixRuntime.getTable(conn, CDCUtil.getCDCIndexName(cdcName));
    assertEquals(indexTable.getEncodingScheme(), NON_ENCODED_QUALIFIERS);
  }

  @Test
  public void testDropCDC() throws SQLException {
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String tableName = generateUniqueName();
    conn.createStatement().execute(
      "CREATE TABLE  " + tableName + " ( k INTEGER PRIMARY KEY," + " v1 INTEGER," + " v2 DATE)");
    String cdcName = generateUniqueName();
    String cdc_sql = "CREATE CDC " + cdcName + " ON " + tableName;
    conn.createStatement().execute(cdc_sql);

    String drop_cdc_sql = "DROP CDC " + cdcName + " ON " + tableName;
    conn.createStatement().execute(drop_cdc_sql);

    try (ResultSet rs = conn.createStatement()
      .executeQuery("SELECT cdc_include FROM " + "system.catalog WHERE table_name = '" + cdcName
        + "' AND column_name IS NULL and column_family IS NULL")) {
      assertEquals(false, rs.next());
    }
    try (ResultSet rs = conn.createStatement()
      .executeQuery("SELECT index_type FROM " + "system.catalog WHERE table_name = '"
        + CDCUtil.getCDCIndexName(cdcName)
        + "' AND column_name IS NULL and column_family IS NULL")) {
      assertEquals(false, rs.next());
    }

    try {
      conn.createStatement().execute(drop_cdc_sql);
      fail("Expected to fail as cdc table doesn't exist");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.TABLE_UNDEFINED.getErrorCode(), e.getErrorCode());
      assertTrue(e.getMessage().endsWith(cdcName));
    }
  }

  @Test
  public void testDropCDCIndex() throws SQLException {
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String tableName = generateUniqueName();
    conn.createStatement().execute(
      "CREATE TABLE  " + tableName + " ( k INTEGER PRIMARY KEY," + " v1 INTEGER," + " v2 DATE)");
    String cdcName = generateUniqueName();
    String cdc_sql = "CREATE CDC " + cdcName + " ON " + tableName;
    conn.createStatement().execute(cdc_sql);
    assertCDCState(conn, cdcName, null, 3);
    String drop_cdc_index_sql =
      "DROP INDEX \"" + CDCUtil.getCDCIndexName(cdcName) + "\" ON " + tableName;
    try {
      conn.createStatement().execute(drop_cdc_index_sql);
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.CANNOT_DROP_CDC_INDEX.getErrorCode(), e.getErrorCode());
      assertTrue(e.getMessage().endsWith(CDCUtil.getCDCIndexName(cdcName)));
    }
  }

  @Test
  public void testDropTable() throws SQLException {
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String tableName = generateUniqueName();
    conn.createStatement().execute(
      "CREATE TABLE  " + tableName + " ( k INTEGER PRIMARY KEY," + " v1 INTEGER," + " v2 DATE)");
    String cdcName = generateUniqueName();
    String cdc_sql = "CREATE CDC " + cdcName + " ON " + tableName;
    conn.createStatement().execute(cdc_sql);

    String drop_table_sql = "DROP TABLE " + tableName;
    conn.createStatement().execute(drop_table_sql);

    // index should have been dropped
    try (ResultSet rs = conn.createStatement()
      .executeQuery("SELECT index_type FROM " + "system.catalog WHERE table_name = '"
        + CDCUtil.getCDCIndexName(cdcName)
        + "' AND column_name IS NULL and column_family IS NULL")) {
      assertEquals(false, rs.next());
    }
    // cdc object should have been dropped
    try (ResultSet rs = conn.createStatement()
      .executeQuery("SELECT cdc_include FROM " + "system.catalog WHERE table_name = '" + cdcName
        + "' AND column_name IS NULL and column_family IS NULL")) {
      assertEquals(false, rs.next());
    }

    String drop_cdc_sql = "DROP CDC " + cdcName + " ON " + tableName;
    try {
      conn.createStatement().execute(drop_cdc_sql);
      fail("Expected to fail as cdc table doesn't exist");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.TABLE_UNDEFINED.getErrorCode(), e.getErrorCode());
      assertTrue(e.getMessage().endsWith(cdcName));
    }
  }

  @Test
  public void testSelectCDCBadIncludeSpec() throws Exception {
    Connection conn = newConnection();
    String tableName = generateUniqueName();
    conn.createStatement()
      .execute("CREATE TABLE  " + tableName + " ( k INTEGER PRIMARY KEY," + " v1 INTEGER)");
    if (forView) {
      String viewName = generateUniqueName();
      conn.createStatement().execute("CREATE VIEW " + viewName + " AS SELECT * FROM " + tableName);
      tableName = viewName;
    }
    String cdcName = generateUniqueName();
    String cdc_sql = "CREATE CDC  " + cdcName + " ON " + tableName;
    createCDC(conn, cdc_sql);
    try {
      conn.createStatement()
        .executeQuery("SELECT " + "/*+ CDC_INCLUDE(DUMMY) */ * FROM " + cdcName);
      fail("Expected to fail due to invalid CDC INCLUDE hint");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.UNKNOWN_INCLUDE_CHANGE_SCOPE.getErrorCode(), e.getErrorCode());
      assertTrue(e.getMessage().endsWith("DUMMY"));
    }
  }
}
