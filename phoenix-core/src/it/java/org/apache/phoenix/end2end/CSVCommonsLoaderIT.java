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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FileUtils;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.jdbc.PhoenixTestDriver;
import org.apache.phoenix.schema.IllegalDataException;
import org.apache.phoenix.schema.types.PArrayDataType;
import org.apache.phoenix.schema.types.PInteger;
import org.apache.phoenix.util.CSVCommonsLoader;
import org.apache.phoenix.util.DateUtil;
import org.apache.phoenix.util.PhoenixRuntime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

import org.apache.phoenix.thirdparty.com.google.common.collect.ImmutableList;

@Category(ParallelStatsDisabledTest.class)
public class CSVCommonsLoaderIT extends ParallelStatsDisabledIT {

  private static final String DATATYPE_TABLE = "DATATYPE";
  private static final String DATATYPES_CSV_VALUES =
    "CKEY, CVARCHAR, CCHAR, CINTEGER, CDECIMAL, CUNSIGNED_INT, CBOOLEAN, CBIGINT, CUNSIGNED_LONG, CTIME, CDATE\n"
      + "KEY1,A,A,2147483647,1.1,0,TRUE,9223372036854775807,0,1990-12-31 10:59:59,1999-12-31 23:59:59\n"
      + "KEY2,B,B,-2147483648,-1.1,2147483647,FALSE,-9223372036854775808,9223372036854775807,2000-01-01 00:00:01,2012-02-29 23:59:59\n"
      + "KEY3,,,,,,,,,,\n";
  private static final String STOCK_CSV_VALUES = "AAPL,APPLE Inc.\n" + "CRM,SALESFORCE\n"
    + "GOOG,Google\n" + "HOG,Harlet-Davidson Inc.\n" + "HPQ,Hewlett Packard\n" + "INTC,Intel\n"
    + "MSFT,Microsoft\n" + "WAG,Walgreens\n" + "WMT,Walmart\n";
  private static final String[] STOCK_COLUMNS_WITH_BOGUS = new String[] { "SYMBOL", "BOGUS" };
  private static final String[] STOCK_COLUMNS = new String[] { "SYMBOL", "COMPANY" };
  private static final String STOCK_CSV_VALUES_WITH_HEADER =
    STOCK_COLUMNS[0] + "," + STOCK_COLUMNS[1] + "\n" + STOCK_CSV_VALUES;
  private static final String STOCK_CSV_VALUES_WITH_DELIMITER = "APPL" + '\u0001' + '\u0002'
    + "APPLE\n" + " Inc" + '\u0002' + "\n" + "MSFT" + '\u0001' + "Microsoft\n";

  private static final String STOCK_TDV_VALUES = "AAPL\tAPPLE Inc\n" + "CRM\tSALESFORCE\n"
    + "GOOG\tGoogle\n" + "HOG\tHarlet-Davidson Inc.\n" + "HPQ\tHewlett Packard\n" + "INTC\tIntel\n"
    + "MSFT\tMicrosoft\n" + "WAG\tWalgreens\n" + "WMT\tWalmart\n";
  private static final String STOCK_TDV_VALUES_WITH_HEADER =
    STOCK_COLUMNS[0] + "\t" + STOCK_COLUMNS[1] + "\n" + STOCK_TDV_VALUES;

  private static final String ENCAPSULATED_CHARS_TABLE = "ENCAPSULATEDCHAR";
  private static final String[] ENCAPSULATED_CHARS_COLUMNS = new String[] { "MYKEY", "MYVALUE" };
  private static final String CSV_VALUES_ENCAPSULATED_CONTROL_CHARS =
    "ALL THREEF,\"This has a all three , , \"\" \r\n in it. \"\n"
      + "COMMA,\"This has a comma , in it. \"\n" + "CRLF,\"This has a crlf \r\n in it. \"\n"
      + "QUOTE,\"This has a quote \"\" in it. \"\n";
  private static final String CSV_VALUES_ENCAPSULATED_CONTROL_CHARS_WITH_HEADER =
    ENCAPSULATED_CHARS_COLUMNS[0] + "," + ENCAPSULATED_CHARS_COLUMNS[1] + "\n"
      + CSV_VALUES_ENCAPSULATED_CONTROL_CHARS;
  private static final String CSV_VALUES_BAD_ENCAPSULATED_CONTROL_CHARS =
    "ALL THREEF,\"This has a all three , , \"\" \r\n in it. \"\n"
      + "COMMA,\"This has a comma , in it. \"\n" + "CRLF,\"This has a crlf \r\n in it. \"\n"
      + "BADENCAPSULATEDQUOTE,\"\"This has a bad quote in it. \"\n";
  private static final String CSV_VALUES_BAD_ENCAPSULATED_CONTROL_CHARS_WITH_HEADER =
    ENCAPSULATED_CHARS_COLUMNS[0] + "," + ENCAPSULATED_CHARS_COLUMNS[1] + "\n"
      + CSV_VALUES_BAD_ENCAPSULATED_CONTROL_CHARS;

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  @Test
  public void testCSVCommonsUpsert() throws Exception {
    CSVParser parser = null;
    PhoenixConnection conn = null;
    try {
      String stockTableName = generateUniqueName();
      // Create table
      String statements = "CREATE TABLE IF NOT EXISTS " + stockTableName
        + "(SYMBOL VARCHAR NOT NULL PRIMARY KEY, COMPANY VARCHAR);";
      conn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
      PhoenixRuntime.executeStatements(conn, new StringReader(statements), null);

      // Upsert CSV file
      CSVCommonsLoader csvUtil =
        new CSVCommonsLoader(conn, stockTableName, Collections.<String> emptyList(), true);
      csvUtil.upsert(new StringReader(STOCK_CSV_VALUES_WITH_HEADER));

      // Compare Phoenix ResultSet with CSV file content
      PreparedStatement statement =
        conn.prepareStatement("SELECT SYMBOL, COMPANY FROM " + stockTableName);
      ResultSet phoenixResultSet = statement.executeQuery();
      parser = new CSVParser(new StringReader(STOCK_CSV_VALUES_WITH_HEADER), csvUtil.getFormat());
      for (CSVRecord record : parser) {
        assertTrue(phoenixResultSet.next());
        int i = 0;
        for (String value : record) {
          assertEquals(value, phoenixResultSet.getString(i + 1));
          i++;
        }
      }
      assertFalse(phoenixResultSet.next());
    } finally {
      if (parser != null) parser.close();
      if (conn != null) conn.close();
    }
  }

  @Test
  public void testCSVCommonsUpsert_MultiTenant() throws Exception {
    CSVParser parser = null;
    PhoenixConnection globalConn = null;
    PhoenixConnection tenantConn = null;
    try {
      String stockTableMultiName = generateUniqueName();

      // Create table using the global connection
      String statements = "CREATE TABLE IF NOT EXISTS " + stockTableMultiName
        + "(TENANT_ID VARCHAR NOT NULL, SYMBOL VARCHAR NOT NULL, COMPANY VARCHAR,"
        + " CONSTRAINT PK PRIMARY KEY(TENANT_ID,SYMBOL)) MULTI_TENANT = true;";
      globalConn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
      PhoenixRuntime.executeStatements(globalConn, new StringReader(statements), null);
      globalConn.close();

      tenantConn = new PhoenixTestDriver().connect(getUrl() + ";TenantId=acme", new Properties())
        .unwrap(PhoenixConnection.class);

      // Upsert CSV file
      CSVCommonsLoader csvUtil = new CSVCommonsLoader(tenantConn, stockTableMultiName,
        Collections.<String> emptyList(), true);
      csvUtil.upsert(new StringReader(STOCK_CSV_VALUES_WITH_HEADER));

      // Compare Phoenix ResultSet with CSV file content
      PreparedStatement statement =
        tenantConn.prepareStatement("SELECT SYMBOL, COMPANY FROM " + stockTableMultiName);
      ResultSet phoenixResultSet = statement.executeQuery();
      parser = new CSVParser(new StringReader(STOCK_CSV_VALUES_WITH_HEADER), csvUtil.getFormat());
      for (CSVRecord record : parser) {
        assertTrue(phoenixResultSet.next());
        int i = 0;
        for (String value : record) {
          assertEquals(value, phoenixResultSet.getString(i + 1));
          i++;
        }
      }
      assertFalse(phoenixResultSet.next());
    } finally {
      if (parser != null) parser.close();
      if (tenantConn != null) tenantConn.close();
    }
  }

  @Test
  public void testTDVCommonsUpsert() throws Exception {
    CSVParser parser = null;
    PhoenixConnection conn = null;
    try {
      String stockTableName = generateUniqueName();

      // Create table
      String statements = "CREATE TABLE IF NOT EXISTS " + stockTableName
        + "(SYMBOL VARCHAR NOT NULL PRIMARY KEY, COMPANY VARCHAR);";
      conn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
      PhoenixRuntime.executeStatements(conn, new StringReader(statements), null);

      // Upsert TDV file
      CSVCommonsLoader csvUtil =
        new CSVCommonsLoader(conn, stockTableName, Collections.<String> emptyList(), true, '\t',
          '"', null, CSVCommonsLoader.DEFAULT_ARRAY_ELEMENT_SEPARATOR);
      csvUtil.upsert(new StringReader(STOCK_TDV_VALUES_WITH_HEADER));

      // Compare Phoenix ResultSet with CSV file content
      PreparedStatement statement =
        conn.prepareStatement("SELECT SYMBOL, COMPANY FROM " + stockTableName);
      ResultSet phoenixResultSet = statement.executeQuery();
      parser = new CSVParser(new StringReader(STOCK_TDV_VALUES_WITH_HEADER), csvUtil.getFormat());
      for (CSVRecord record : parser) {
        assertTrue(phoenixResultSet.next());
        int i = 0;
        for (String value : record) {
          assertEquals(value, phoenixResultSet.getString(i + 1));
          i++;
        }
      }
      assertFalse(phoenixResultSet.next());
    } finally {
      if (parser != null) parser.close();
      if (conn != null) conn.close();
    }
  }

  @Test
  public void testCSVUpsertWithCustomDelimiters() throws Exception {
    CSVParser parser = null;
    PhoenixConnection conn = null;
    try {
      String stockTableName = generateUniqueName();

      // Create table
      String statements = "CREATE TABLE IF NOT EXISTS " + stockTableName
        + "(SYMBOL VARCHAR NOT NULL PRIMARY KEY, COMPANY VARCHAR);";
      conn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
      PhoenixRuntime.executeStatements(conn, new StringReader(statements), null);

      // Upsert CSV file
      CSVCommonsLoader csvUtil =
        new CSVCommonsLoader(conn, stockTableName, Arrays.<String> asList(STOCK_COLUMNS), true, '1',
          '2', '3', CSVCommonsLoader.DEFAULT_ARRAY_ELEMENT_SEPARATOR);
      csvUtil.upsert(new StringReader(STOCK_CSV_VALUES_WITH_DELIMITER));

      // Compare Phoenix ResultSet with CSV file content
      PreparedStatement statement =
        conn.prepareStatement("SELECT SYMBOL, COMPANY FROM " + stockTableName);
      ResultSet phoenixResultSet = statement.executeQuery();
      parser =
        new CSVParser(new StringReader(STOCK_CSV_VALUES_WITH_DELIMITER), csvUtil.getFormat());
      for (CSVRecord record : parser) {
        assertTrue(phoenixResultSet.next());
        int i = 0;
        for (String value : record) {
          assertEquals(value, phoenixResultSet.getString(i + 1));
          i++;
        }
      }
      assertFalse(phoenixResultSet.next());
    } finally {
      if (parser != null) parser.close();
      if (conn != null) conn.close();
    }
  }

  @Test
  public void testCSVUpsertWithColumns() throws Exception {
    CSVParser parser = null;
    PhoenixConnection conn = null;
    try {
      String stockTableName = generateUniqueName();

      // Create table
      String statements = "CREATE TABLE IF NOT EXISTS " + stockTableName
        + "(SYMBOL VARCHAR NOT NULL PRIMARY KEY, COMPANY VARCHAR);";
      conn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
      PhoenixRuntime.executeStatements(conn, new StringReader(statements), null);

      // Upsert CSV file
      CSVCommonsLoader csvUtil =
        new CSVCommonsLoader(conn, stockTableName, Arrays.<String> asList(STOCK_COLUMNS), true);
      // no header
      csvUtil.upsert(new StringReader(STOCK_CSV_VALUES));

      // Compare Phoenix ResultSet with CSV file content
      PreparedStatement statement =
        conn.prepareStatement("SELECT SYMBOL, COMPANY FROM " + stockTableName);
      ResultSet phoenixResultSet = statement.executeQuery();
      parser = new CSVParser(new StringReader(STOCK_CSV_VALUES), csvUtil.getFormat());
      for (CSVRecord record : parser) {
        assertTrue(phoenixResultSet.next());
        int i = 0;
        for (String value : record) {
          assertEquals(value, phoenixResultSet.getString(i + 1));
          i++;
        }
      }

      assertFalse(phoenixResultSet.next());
    } finally {
      if (parser != null) parser.close();
      if (conn != null) conn.close();
    }
  }

  @Test
  public void testCSVUpsertWithNoColumns() throws Exception {
    CSVParser parser = null;
    PhoenixConnection conn = null;
    try {
      String stockTableName = generateUniqueName();

      // Create table
      String statements = "CREATE TABLE IF NOT EXISTS " + stockTableName
        + "(SYMBOL VARCHAR NOT NULL PRIMARY KEY, COMPANY VARCHAR);";
      conn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
      PhoenixRuntime.executeStatements(conn, new StringReader(statements), null);

      // Upsert CSV file
      CSVCommonsLoader csvUtil = new CSVCommonsLoader(conn, stockTableName, null, true);
      csvUtil.upsert(new StringReader(STOCK_CSV_VALUES));

      // Compare Phoenix ResultSet with CSV file content
      PreparedStatement statement =
        conn.prepareStatement("SELECT SYMBOL, COMPANY FROM " + stockTableName);
      ResultSet phoenixResultSet = statement.executeQuery();
      parser = new CSVParser(new StringReader(STOCK_CSV_VALUES), csvUtil.getFormat());
      for (CSVRecord record : parser) {
        assertTrue(phoenixResultSet.next());
        int i = 0;
        for (String value : record) {
          assertEquals(value, phoenixResultSet.getString(i + 1));
          i++;
        }
      }

      assertFalse(phoenixResultSet.next());
    } finally {
      if (parser != null) parser.close();
      if (conn != null) conn.close();
    }
  }

  @Test
  public void testCSVUpsertWithBogusColumn() throws Exception {
    CSVParser parser = null;
    PhoenixConnection conn = null;
    try {
      String stockTableName = generateUniqueName();

      // Create table
      String statements = "CREATE TABLE IF NOT EXISTS " + stockTableName
        + "(SYMBOL VARCHAR NOT NULL PRIMARY KEY, COMPANY VARCHAR);";
      conn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
      PhoenixRuntime.executeStatements(conn, new StringReader(statements), null);

      // Upsert CSV file, not strict
      CSVCommonsLoader csvUtil =
        new CSVCommonsLoader(conn, stockTableName, Arrays.asList(STOCK_COLUMNS_WITH_BOGUS), false);
      csvUtil.upsert(new StringReader(STOCK_CSV_VALUES));

      // Compare Phoenix ResultSet with CSV file content
      PreparedStatement statement =
        conn.prepareStatement("SELECT SYMBOL, COMPANY FROM " + stockTableName);
      ResultSet phoenixResultSet = statement.executeQuery();
      parser = new CSVParser(new StringReader(STOCK_CSV_VALUES), csvUtil.getFormat());
      for (CSVRecord record : parser) {
        assertTrue(phoenixResultSet.next());
        assertEquals(record.get(0), phoenixResultSet.getString(1));
        assertNull(phoenixResultSet.getString(2));
      }

      assertFalse(phoenixResultSet.next());
    } finally {
      if (parser != null) parser.close();
      if (conn != null) conn.close();
    }
  }

  // Ensure that strict mode also causes the import to stop if a data type on a single
  // row is not correct
  @Test
  public void testCSVUpsertWithInvalidNumericalData_StrictMode() throws Exception {
    CSVParser parser = null;
    PhoenixConnection conn = null;
    try {
      String stockTableName = generateUniqueName();

      // Create table
      String statements = "CREATE TABLE IF NOT EXISTS " + stockTableName
        + "(SYMBOL VARCHAR NOT NULL PRIMARY KEY, COMPANY_ID BIGINT);";
      conn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
      PhoenixRuntime.executeStatements(conn, new StringReader(statements), null);

      // Upsert CSV file in strict mode
      CSVCommonsLoader csvUtil =
        new CSVCommonsLoader(conn, stockTableName, Arrays.asList("SYMBOL", "COMPANY_ID"), true);
      try {
        csvUtil.upsert(new StringReader(STOCK_CSV_VALUES));
        fail("Running an upsert with data that can't be upserted in strict mode "
          + "should throw an exception");
      } catch (IllegalDataException e) {
        // Expected
      }

    } finally {
      if (parser != null) parser.close();
      if (conn != null) conn.close();
    }
  }

  @Test
  public void testCSVUpsertWithAllColumn() throws Exception {
    CSVParser parser = null;
    PhoenixConnection conn = null;
    try {
      String stockTableName = generateUniqueName();

      // Create table
      String statements = "CREATE TABLE IF NOT EXISTS " + stockTableName
        + "(SYMBOL VARCHAR NOT NULL PRIMARY KEY, COMPANY VARCHAR);";
      conn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
      PhoenixRuntime.executeStatements(conn, new StringReader(statements), null);

      // Upsert CSV file
      CSVCommonsLoader csvUtil =
        new CSVCommonsLoader(conn, stockTableName, Arrays.asList("FOO", "BAR"), false);

      try {
        csvUtil.upsert(new StringReader(STOCK_CSV_VALUES));
        fail();
      } catch (SQLException e) {
        assertTrue(e.getMessage(), e.getMessage().contains(
          "ERROR 504 (42703): Undefined column. columnName=" + stockTableName + ".[FOO, BAR]"));
      }
    } finally {
      if (parser != null) parser.close();
      if (conn != null) conn.close();
    }
  }

  @Test
  public void testCSVUpsertWithBogusColumnStrict() throws Exception {
    CSVParser parser = null;
    PhoenixConnection conn = null;
    try {
      String stockTableName = generateUniqueName();

      // Create table
      String statements = "CREATE TABLE IF NOT EXISTS " + stockTableName
        + "(SYMBOL VARCHAR NOT NULL PRIMARY KEY, COMPANY VARCHAR);";
      conn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
      PhoenixRuntime.executeStatements(conn, new StringReader(statements), null);

      // Upsert CSV file
      CSVCommonsLoader csvUtil =
        new CSVCommonsLoader(conn, stockTableName, Arrays.asList(STOCK_COLUMNS_WITH_BOGUS), true);
      try {
        csvUtil.upsert(new StringReader(STOCK_CSV_VALUES));
        fail();
      } catch (SQLException e) {
        assertTrue(e.getMessage(), e.getMessage().contains(
          "ERROR 504 (42703): Undefined column. columnName=" + stockTableName + ".BOGUS"));
      }
    } finally {
      if (parser != null) parser.close();
      if (conn != null) conn.close();
    }
  }

  @Test
  public void testAllDatatypes() throws Exception {
    CSVParser parser = null;
    PhoenixConnection conn = null;
    try {
      // Create table
      String statements = "CREATE TABLE IF NOT EXISTS " + DATATYPE_TABLE
        + " (CKEY VARCHAR NOT NULL PRIMARY KEY,"
        + "  CVARCHAR VARCHAR, CCHAR CHAR(10), CINTEGER INTEGER, CDECIMAL DECIMAL(31,10), CUNSIGNED_INT UNSIGNED_INT, CBOOLEAN BOOLEAN, CBIGINT BIGINT, CUNSIGNED_LONG UNSIGNED_LONG, CTIME TIME, CDATE DATE);";
      conn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
      PhoenixRuntime.executeStatements(conn, new StringReader(statements), null);

      // Upsert CSV file
      CSVCommonsLoader csvUtil =
        new CSVCommonsLoader(conn, DATATYPE_TABLE, Collections.<String> emptyList(), true);
      csvUtil.upsert(new StringReader(DATATYPES_CSV_VALUES));

      // Compare Phoenix ResultSet with CSV file content
      PreparedStatement statement = conn.prepareStatement(
        "SELECT CKEY, CVARCHAR, CCHAR, CINTEGER, CDECIMAL, CUNSIGNED_INT, CBOOLEAN, CBIGINT, CUNSIGNED_LONG, CTIME, CDATE FROM "
          + DATATYPE_TABLE);
      ResultSet phoenixResultSet = statement.executeQuery();
      parser = new CSVParser(new StringReader(DATATYPES_CSV_VALUES), csvUtil.getFormat());

      for (CSVRecord record : parser) {
        assertTrue(phoenixResultSet.next());
        int i = 0;
        int size = record.size();
        for (String value : record) {
          assertEquals(value, phoenixResultSet.getObject(i + 1).toString().toUpperCase());
          if (i < size - 2) break;
          i++;
        }
        // special case for matching date, time values
        String timeFieldValue = record.get(9);
        assertEquals(timeFieldValue.isEmpty() ? null : DateUtil.parseTime(record.get(9)),
          phoenixResultSet.getTime("CTIME"));

        String dateField = record.get(10);
        assertEquals(dateField.isEmpty() ? null : DateUtil.parseDate(record.get(10)),
          phoenixResultSet.getDate("CDATE"));
      }

      assertFalse(phoenixResultSet.next());
    } finally {
      if (parser != null) parser.close();
      if (conn != null) conn.close();
    }
  }

  @Test
  public void testCSVCommonsUpsertEncapsulatedControlChars() throws Exception {
    CSVParser parser = null;
    PhoenixConnection conn = null;
    try {
      // Create table
      String statements = "CREATE TABLE IF NOT EXISTS " + ENCAPSULATED_CHARS_TABLE
        + "(MYKEY VARCHAR NOT NULL PRIMARY KEY, MYVALUE VARCHAR);";
      conn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
      PhoenixRuntime.executeStatements(conn, new StringReader(statements), null);

      // Upsert CSV file
      CSVCommonsLoader csvUtil = new CSVCommonsLoader(conn, ENCAPSULATED_CHARS_TABLE,
        Collections.<String> emptyList(), true);
      csvUtil.upsert(new StringReader(CSV_VALUES_ENCAPSULATED_CONTROL_CHARS_WITH_HEADER));

      // Compare Phoenix ResultSet with CSV file content
      PreparedStatement statement =
        conn.prepareStatement("SELECT MYKEY, MYVALUE FROM " + ENCAPSULATED_CHARS_TABLE);
      ResultSet phoenixResultSet = statement.executeQuery();
      parser = new CSVParser(new StringReader(CSV_VALUES_ENCAPSULATED_CONTROL_CHARS_WITH_HEADER),
        csvUtil.getFormat());
      for (CSVRecord record : parser) {
        assertTrue(phoenixResultSet.next());
        int i = 0;
        for (String value : record) {
          assertEquals(value, phoenixResultSet.getString(i + 1));
          i++;
        }
      }

      assertFalse(phoenixResultSet.next());
    } finally {
      if (parser != null) parser.close();
      if (conn != null) conn.close();
    }
  }

  @Test
  public void testCSVCommonsUpsertBadEncapsulatedControlChars() throws Exception {
    CSVParser parser = null;
    PhoenixConnection conn = null;
    try {
      // Create table
      String statements = "CREATE TABLE IF NOT EXISTS " + ENCAPSULATED_CHARS_TABLE
        + "(MYKEY VARCHAR NOT NULL PRIMARY KEY, MYVALUE VARCHAR);";
      conn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
      PhoenixRuntime.executeStatements(conn, new StringReader(statements), null);

      // Upsert CSV file
      CSVCommonsLoader csvUtil = new CSVCommonsLoader(conn, ENCAPSULATED_CHARS_TABLE,
        Collections.<String> emptyList(), true);
      try {
        csvUtil.upsert(new StringReader(CSV_VALUES_BAD_ENCAPSULATED_CONTROL_CHARS_WITH_HEADER));
        fail();
      } catch (RuntimeException e) {
        assertTrue(e.getMessage(),
          e.getMessage().contains("invalid char between encapsulated token and delimiter"));
      }
    } finally {
      if (parser != null) parser.close();
      if (conn != null) conn.close();
    }
  }

  @Test
  public void testCSVCommonsUpsert_WithArray() throws Exception {
    CSVParser parser = null;
    PhoenixConnection conn = null;
    try {

      // Create table
      String statements = "CREATE TABLE IF NOT EXISTS ARRAY_TABLE "
        + "(ID BIGINT NOT NULL PRIMARY KEY, VALARRAY INTEGER ARRAY);";
      conn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
      PhoenixRuntime.executeStatements(conn, new StringReader(statements), null);

      // Upsert CSV file
      CSVCommonsLoader csvUtil = new CSVCommonsLoader(conn, "ARRAY_TABLE",
        ImmutableList.<String> of(), true, ',', '"', null, "!");
      csvUtil.upsert(new StringReader("ID,VALARRAY\n" + "1,2!3!4\n"));

      // Compare Phoenix ResultSet with CSV file content
      PreparedStatement statement = conn.prepareStatement("SELECT ID, VALARRAY FROM ARRAY_TABLE");
      ResultSet phoenixResultSet = statement.executeQuery();
      assertTrue(phoenixResultSet.next());
      assertEquals(1L, phoenixResultSet.getLong(1));
      assertEquals(
        PArrayDataType.instantiatePhoenixArray(PInteger.INSTANCE, new Integer[] { 2, 3, 4 }),
        phoenixResultSet.getArray(2));
      assertFalse(phoenixResultSet.next());
    } finally {
      if (parser != null) parser.close();
      if (conn != null) conn.close();
    }
  }

  @Test
  public void testCSVCommonsUpsert_WithTimestamp() throws Exception {
    CSVParser parser = null;
    PhoenixConnection conn = null;
    try {

      // Create table
      String statements =
        "CREATE TABLE IF NOT EXISTS TS_TABLE " + "(ID BIGINT NOT NULL PRIMARY KEY, TS TIMESTAMP);";
      conn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
      PhoenixRuntime.executeStatements(conn, new StringReader(statements), null);

      // Upsert CSV file
      CSVCommonsLoader csvUtil = new CSVCommonsLoader(conn, "TS_TABLE", ImmutableList.<String> of(),
        true, ',', '"', null, "!");
      csvUtil.upsert(
        new StringReader("ID,TS\n" + "1,1970-01-01 00:00:10\n" + "2,1970-01-01 00:00:10.123\n"));

      // Compare Phoenix ResultSet with CSV file content
      PreparedStatement statement =
        conn.prepareStatement("SELECT ID, TS FROM TS_TABLE ORDER BY ID");
      ResultSet phoenixResultSet = statement.executeQuery();
      assertTrue(phoenixResultSet.next());
      assertEquals(1L, phoenixResultSet.getLong(1));
      assertEquals(10000L, phoenixResultSet.getTimestamp(2).getTime());
      assertTrue(phoenixResultSet.next());
      assertEquals(2L, phoenixResultSet.getLong(1));
      assertEquals(10123L, phoenixResultSet.getTimestamp(2).getTime());
      assertFalse(phoenixResultSet.next());
    } finally {
      if (parser != null) parser.close();
      if (conn != null) conn.close();
    }
  }

  @Test
  public void testCSVCommonsUpsert_NonExistentTable() throws Exception {
    PhoenixConnection conn = null;
    try {
      conn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
      CSVCommonsLoader csvUtil =
        new CSVCommonsLoader(conn, "NONEXISTENTTABLE", null, true, ',', '"', null, "!");
      csvUtil.upsert(new StringReader("ID,VALARRAY\n" + "1,2!3!4\n"));
      fail("Trying to load a non-existent table should fail");
    } catch (IllegalArgumentException e) {
      assertEquals("Table NONEXISTENTTABLE not found", e.getMessage());
    } finally {
      if (conn != null) {
        conn.close();
      }
    }

  }

  @Test
  public void testLowerCaseTable() throws Exception {
    Connection conn = DriverManager.getConnection(getUrl());
    conn.setAutoCommit(true);
    String tableName = generateUniqueName().toLowerCase();
    String t1 = generateUniqueName();
    String t2 = t1 + generateUniqueName();
    String csvFileName = "test.csv";
    conn.createStatement().execute("CREATE TABLE \"" + tableName
      + "\" (k1 VARCHAR NOT NULL, k2 VARCHAR, CONSTRAINT PK PRIMARY KEY(K1,K2))");
    File tempFile = tempFolder.newFile(csvFileName);
    FileUtils.writeStringToFile(tempFile, "'" + t1 + "','x'");
    try {
      CSVCommonsLoader csvLoader = new CSVCommonsLoader(conn.unwrap(PhoenixConnection.class),
        "" + tableName + "", null, false, ',', '"', '\\', null);
      csvLoader.upsert(tempFile.getAbsolutePath());
    } catch (Exception e) {
      fail("Failed with Exception:" + e.getMessage());
    }
    ResultSet rs =
      conn.createStatement().executeQuery("SELECT * FROM \"" + tableName + "\" order by k2");
    assertTrue(rs.next());
    assertEquals("'" + t1 + "'", rs.getString(1));
    assertEquals("'" + "x" + "'", rs.getString(2));
    assertFalse(rs.next());

  }
}
