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
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.schema.SortOrder;

import org.apache.phoenix.thirdparty.com.google.common.collect.ImmutableList;

public class IndexKeyConstraint {
  public static final IndexKeyConstraint EMPTY =
    new IndexKeyConstraint(Collections.<Pair<ParseNode, SortOrder>> emptyList());

  private final List<Pair<ParseNode, SortOrder>> columnNameToSortOrder;

  IndexKeyConstraint(List<Pair<ParseNode, SortOrder>> parseNodeAndSortOrder) {
    this.columnNameToSortOrder = ImmutableList.copyOf(parseNodeAndSortOrder);
  }

  public List<Pair<ParseNode, SortOrder>> getParseNodeAndSortOrderList() {
    return columnNameToSortOrder;
  }

  @Override
  public String toString() {
    StringBuffer sb = new StringBuffer();
    for (Pair<ParseNode, SortOrder> entry : columnNameToSortOrder) {
      if (sb.length() != 0) {
        sb.append(", ");
      }
      sb.append(entry.getFirst().toString());
      if (entry.getSecond() != SortOrder.getDefault()) {
        sb.append(" " + entry.getSecond());
      }
    }
    return sb.toString();
  }
}
