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

import org.apache.phoenix.jdbc.PhoenixStatement.Operation;

public class DropSchemaStatement extends MutableStatement {
  private final String schemaName;
  private final boolean ifExists;
  private final boolean cascade;

  public DropSchemaStatement(String schemaName, boolean ifExists, boolean cascade) {
    this.schemaName = schemaName;
    this.ifExists = ifExists;
    this.cascade = cascade;
  }

  @Override
  public int getBindCount() {
    return 0;
  }

  public String getSchemaName() {
    return schemaName;
  }

  public boolean ifExists() {
    return ifExists;
  }

  public boolean cascade() {
    return cascade;
  }

  @Override
  public Operation getOperation() {
    return Operation.DELETE;
  }

}
