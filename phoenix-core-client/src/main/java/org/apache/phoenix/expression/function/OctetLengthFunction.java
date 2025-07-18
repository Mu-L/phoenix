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
package org.apache.phoenix.expression.function;

import java.sql.SQLException;
import java.util.List;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.parse.FunctionParseNode.Argument;
import org.apache.phoenix.parse.FunctionParseNode.BuiltInFunction;
import org.apache.phoenix.schema.tuple.Tuple;
import org.apache.phoenix.schema.types.PBinary;
import org.apache.phoenix.schema.types.PBinaryBase;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.schema.types.PInteger;
import org.apache.phoenix.schema.types.PVarbinary;

@BuiltInFunction(name = OctetLengthFunction.NAME,
    args = { @Argument(allowedTypes = { PBinary.class, PVarbinary.class }), })
public class OctetLengthFunction extends ScalarFunction {

  public static final String NAME = "OCTET_LENGTH";

  public OctetLengthFunction() {
  }

  public OctetLengthFunction(List<Expression> children) throws SQLException {
    super(children);
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public boolean evaluate(Tuple tuple, ImmutableBytesWritable ptr) {
    // get binary data parameter
    Expression dataExpr = children.get(0);
    if (!dataExpr.evaluate(tuple, ptr)) return false;
    if (ptr.getLength() == 0) return true;
    // set result
    ((PBinaryBase) dataExpr.getDataType()).octetLength(ptr, dataExpr.getSortOrder(), ptr);
    return true;
  }

  @Override
  public PDataType getDataType() {
    return PInteger.INSTANCE;
  }
}
