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
package org.apache.phoenix.hbase.index.parallel;

import java.util.concurrent.Callable;
import org.apache.hadoop.hbase.Abortable;

/**
 * Like a {@link Callable}, but supports an internal {@link Abortable} that can be checked
 * periodically to determine if the batch should abort
 * @param <V> expected result of the task
 */
public abstract class Task<V> implements Callable<V> {

  private Abortable batch;

  void setBatchMonitor(Abortable abort) {
    this.batch = abort;
  }

  protected boolean isBatchFailed() {
    return this.batch.isAborted();
  }
}
