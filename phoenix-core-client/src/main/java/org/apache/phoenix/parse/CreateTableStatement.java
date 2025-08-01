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
package org.apache.phoenix.parse;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.schema.PTableType;

import org.apache.phoenix.thirdparty.com.google.common.collect.ImmutableList;
import org.apache.phoenix.thirdparty.com.google.common.collect.ImmutableListMultimap;
import org.apache.phoenix.thirdparty.com.google.common.collect.ListMultimap;

public class CreateTableStatement extends MutableStatement {
  private final TableName tableName;
  private final PTableType tableType;
  private final List<ColumnDef> columns;
  private final PrimaryKeyConstraint pkConstraint;
  private final List<ParseNode> splitNodes;
  private final int bindCount;
  private final ListMultimap<String, Pair<String, Object>> props;
  private final boolean ifNotExists;
  private final TableName baseTableName;
  private final ParseNode whereClause;
  // TODO change this to boolean at the next major release and remove TableProperty.IMMUTABLE_ROWS
  // and QueryServiceOptions.IMMUTABLE_ROWS_ATTRIB
  private final Boolean immutableRows;
  private final Map<String, Integer> familyCQCounters;
  private final boolean noVerify;

  public CreateTableStatement(CreateTableStatement createTable, List<ColumnDef> columns) {
    this.tableName = createTable.tableName;
    this.tableType = createTable.tableType;
    this.columns = ImmutableList.copyOf(columns);
    this.pkConstraint = createTable.pkConstraint;
    this.splitNodes = createTable.splitNodes;
    this.bindCount = createTable.bindCount;
    this.props = createTable.props;
    this.ifNotExists = createTable.ifNotExists;
    this.baseTableName = createTable.baseTableName;
    this.whereClause = createTable.whereClause;
    this.immutableRows = createTable.immutableRows;
    this.familyCQCounters = createTable.familyCQCounters;
    this.noVerify = createTable.noVerify;
  }

  public CreateTableStatement(CreateTableStatement createTable, PrimaryKeyConstraint pkConstraint,
    List<ColumnDef> columns) {
    this.tableName = createTable.tableName;
    this.tableType = createTable.tableType;
    this.columns = ImmutableList.copyOf(columns);
    this.pkConstraint = pkConstraint;
    this.splitNodes = createTable.splitNodes;
    this.bindCount = createTable.bindCount;
    this.props = createTable.props;
    this.ifNotExists = createTable.ifNotExists;
    this.baseTableName = createTable.baseTableName;
    this.whereClause = createTable.whereClause;
    this.immutableRows = createTable.immutableRows;
    this.familyCQCounters = createTable.familyCQCounters;
    this.noVerify = createTable.noVerify;
  }

  public CreateTableStatement(CreateTableStatement createTable,
    ListMultimap<String, Pair<String, Object>> props, List<ColumnDef> columns) {
    this.tableName = createTable.tableName;
    this.tableType = createTable.tableType;
    this.columns = ImmutableList.copyOf(columns);
    this.pkConstraint = createTable.pkConstraint;
    this.splitNodes = createTable.splitNodes;
    this.bindCount = createTable.bindCount;
    this.props = props;
    this.ifNotExists = createTable.ifNotExists;
    this.baseTableName = createTable.baseTableName;
    this.whereClause = createTable.whereClause;
    this.immutableRows = createTable.immutableRows;
    this.familyCQCounters = createTable.familyCQCounters;
    this.noVerify = createTable.noVerify;
  }

  protected CreateTableStatement(TableName tableName,
    ListMultimap<String, Pair<String, Object>> props, List<ColumnDef> columns,
    PrimaryKeyConstraint pkConstraint, List<ParseNode> splitNodes, PTableType tableType,
    boolean ifNotExists, TableName baseTableName, ParseNode whereClause, int bindCount,
    Boolean immutableRows, Map<String, Integer> familyCounters, boolean noVerify) {
    this.tableName = tableName;
    this.props = props == null ? ImmutableListMultimap.<String, Pair<String, Object>> of() : props;
    // When it is an index on SYSTEM.CATALOG tableType => PTableType.INDEX
    // If Schema is SYSTEM and tableType = SYSTEM | TABLE => PTableType.SYSTEM
    // else the passed in tableType
    this.tableType =
      (PhoenixDatabaseMetaData.SYSTEM_CATALOG_SCHEMA.equals(tableName.getSchemaName())
        && (tableType == PTableType.TABLE || tableType == PTableType.SYSTEM)
          ? PTableType.SYSTEM
          : tableType);
    this.columns =
      columns == null ? ImmutableList.<ColumnDef> of() : ImmutableList.<ColumnDef> copyOf(columns);
    this.pkConstraint = pkConstraint == null ? PrimaryKeyConstraint.EMPTY : pkConstraint;
    this.splitNodes =
      splitNodes == null ? Collections.<ParseNode> emptyList() : ImmutableList.copyOf(splitNodes);
    this.bindCount = bindCount;
    this.ifNotExists = ifNotExists;
    this.baseTableName = baseTableName;
    this.whereClause = whereClause;
    this.immutableRows = immutableRows;
    this.familyCQCounters = familyCounters;
    this.noVerify = noVerify;
  }

  public ParseNode getWhereClause() {
    return whereClause;
  }

  @Override
  public int getBindCount() {
    return bindCount;
  }

  public TableName getTableName() {
    return tableName;
  }

  public TableName getBaseTableName() {
    return baseTableName;
  }

  public List<ColumnDef> getColumnDefs() {
    return columns;
  }

  public List<ParseNode> getSplitNodes() {
    return splitNodes;
  }

  public PTableType getTableType() {
    return tableType;
  }

  public ListMultimap<String, Pair<String, Object>> getProps() {
    return props;
  }

  public boolean ifNotExists() {
    return ifNotExists;
  }

  public PrimaryKeyConstraint getPrimaryKeyConstraint() {
    return pkConstraint;
  }

  public Boolean immutableRows() {
    return immutableRows;
  }

  public Map<String, Integer> getFamilyCQCounters() {
    return familyCQCounters;
  }

  public boolean isNoVerify() {
    return noVerify;
  }
}
