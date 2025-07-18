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
package org.apache.phoenix.schema.tuple;

import java.util.List;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;

/**
 * Interface representing an ordered list of KeyValues returned as the result of a query. Each tuple
 * represents a row (i.e. all its KeyValues will have the same key), and each KeyValue represents a
 * column value.
 * @since 0.1
 */
public interface Tuple {
  /** Returns Number of KeyValues contained by the Tuple. */
  public int size();

  /**
   * Determines whether or not the Tuple is immutable (the typical case) or will potentially have
   * additional KeyValues added to it (the case during filter evaluation when we see one KeyValue at
   * a time).
   * @return true if Tuple is immutable and false otherwise.
   */
  public boolean isImmutable();

  /**
   * Get the row key for the Tuple
   * @param ptr the bytes pointer that will be updated to point to the key buffer.
   */
  public void getKey(ImmutableBytesWritable ptr);

  /**
   * Get the KeyValue at the given index whose value is concatenated with the serialized list of
   * dynamic column PColumns for that row key.
   * @param index       the zero-based KeyValue index between 0 and {@link #size()} exclusive
   * @param dynColsList the serialized list of dynamic column PColumns
   * @return the KeyValue at the given index
   * @throws IndexOutOfBoundsException if an invalid index is used
   */
  public Cell mergeWithDynColsListBytesAndGetValue(int index, byte[] dynColsList);

  /**
   * Get the KeyValue at the given index.
   * @param index the zero-based KeyValue index between 0 and {@link #size()} exclusive
   * @return the KeyValue at the given index
   * @throws IndexOutOfBoundsException if an invalid index is used
   */
  public Cell getValue(int index);

  /**
   * Get the KeyValue contained by the Tuple with the given family and qualifier name.
   * @param family    the column family of the KeyValue being retrieved
   * @param qualifier the column qualify of the KeyValue being retrieved
   * @return the KeyValue with the given family and qualifier name or null if not found.
   */
  public Cell getValue(byte[] family, byte[] qualifier);

  /**
   * Get the value byte array of the KeyValue contained by the Tuple with the given family and
   * qualifier name.
   * @param family    the column family of the KeyValue being retrieved
   * @param qualifier the column qualify of the KeyValue being retrieved
   * @param ptr       the bytes pointer that will be updated to point to the value buffer.
   * @return true if the KeyValue with the given family and qualifier name exists; otherwise false.
   */
  public boolean getValue(byte[] family, byte[] qualifier, ImmutableBytesWritable ptr);

  /**
   * Get the sequence value given the sequence index. May only be evaluated on the client-side.
   * @return the current or next sequence value
   */
  public long getSequenceValue(int index);

  public void setKeyValues(List<Cell> values);

  /**
   * Returns the serialized size in bytes of this tuple's data without copying the underlying cell
   * bytes. This includes the serialized size of all Cells or KeyValues contained in the tuple. This
   * can be used for memory tracking and optimization purposes.
   * @return The total size in bytes of the tuple's data.
   */
  public long getSerializedSize();
}
