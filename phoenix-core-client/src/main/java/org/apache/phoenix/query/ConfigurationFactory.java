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
package org.apache.phoenix.query;

import java.util.concurrent.Callable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.phoenix.util.PhoenixContextExecutor;

/**
 * Creates {@link Configuration} instances that contain HBase/Hadoop settings.
 * @since 2.0
 */
public interface ConfigurationFactory {
  /** Returns Configuration containing HBase/Hadoop settings */
  Configuration getConfiguration();

  Configuration getConfiguration(Configuration conf);

  /**
   * Default implementation uses {@link org.apache.hadoop.hbase.HBaseConfiguration#create()}.
   */
  static class ConfigurationFactoryImpl implements ConfigurationFactory {
    @Override
    public Configuration getConfiguration() {
      return PhoenixContextExecutor.callWithoutPropagation(new Callable<Configuration>() {
        @Override
        public Configuration call() throws Exception {
          return HBaseConfiguration.create();
        }
      });
    }

    @Override
    public Configuration getConfiguration(final Configuration conf) {
      return PhoenixContextExecutor.callWithoutPropagation(new Callable<Configuration>() {
        @Override
        public Configuration call() throws Exception {
          return HBaseConfiguration.create(conf);
        }
      });
    }
  }
}
