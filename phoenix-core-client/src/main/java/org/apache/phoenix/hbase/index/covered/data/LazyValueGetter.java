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
package org.apache.phoenix.hbase.index.covered.data;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.phoenix.hbase.index.AbstractValueGetter;
import org.apache.phoenix.hbase.index.ValueGetter;
import org.apache.phoenix.hbase.index.covered.filter.ApplyAndFilterDeletesFilter.DeleteTracker;
import org.apache.phoenix.hbase.index.covered.update.ColumnReference;
import org.apache.phoenix.hbase.index.scanner.Scanner;
import org.apache.phoenix.hbase.index.scanner.ScannerBuilder.CoveredDeleteScanner;
import org.apache.phoenix.hbase.index.util.ImmutableBytesPtr;

/**
 * {@link ValueGetter} that uses lazy initialization to get the value for the given
 * {@link ColumnReference}. Once stored, the mapping for that reference is retained.
 */
public class LazyValueGetter extends AbstractValueGetter {

  private CoveredDeleteScanner scan;
  private volatile Map<ColumnReference, ImmutableBytesWritable> values;
  private byte[] row;

  /**
   * Back the getter with a {@link Scanner} to actually access the local data.
   * @param scan       backing scanner
   * @param currentRow row key for the row to seek in the scanner
   */
  public LazyValueGetter(CoveredDeleteScanner scan, byte[] currentRow) {
    this.scan = scan;
    this.row = currentRow;
  }

  @Override
  public ImmutableBytesWritable getLatestValue(ColumnReference ref, long ts) throws IOException {
    Map<ColumnReference, ImmutableBytesWritable> v = values;
    // ensure we have a backing map
    if (v == null) {
      synchronized (this) {
        v = values;
        if (v == null) {
          v = values =
            Collections.synchronizedMap(new HashMap<ColumnReference, ImmutableBytesWritable>());
        }
      }
    }

    // check the value in the map
    ImmutableBytesWritable value = v.get(ref);
    if (value == null) {
      value = get(ref);
      DeleteTracker deleteTracker = scan.getDeleteTracker();
      if (value == null) {
        // Delete family is used for row deletion. Family won't necessarily match as we'll be at
        // the delete family marker on the last column family if there is one.
        if (deleteTracker.deleteFamily != null && deleteTracker.deleteFamily.getTimestamp() == ts) {
          value = HIDDEN_BY_DELETE;
        }
      }
      v.put(ref, value);
    }

    return value;
  }

  /** Returns the first value on the scanner for the given column */
  private ImmutableBytesPtr get(ColumnReference ref) throws IOException {
    KeyValue first = ref.getFirstKeyValueForRow(row);
    if (!scan.seek(first)) {
      return null;
    }
    // there is a next value - we only care about the current value, so we can just snag that
    Cell next = scan.next();
    if (ref.matches(next)) {
      return new ImmutableBytesPtr(next.getValueArray(), next.getValueOffset(),
        next.getValueLength());
    }
    return null;
  }

  @Override
  public byte[] getRowKey() {
    return this.row;
  }
}
