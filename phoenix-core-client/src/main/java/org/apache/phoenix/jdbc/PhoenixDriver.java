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
package org.apache.phoenix.jdbc;

import static org.apache.phoenix.monitoring.GlobalClientMetrics.GLOBAL_FAILED_PHOENIX_CONNECTIONS;
import static org.apache.phoenix.monitoring.GlobalClientMetrics.GLOBAL_PHOENIX_CONNECTIONS_ATTEMPTED_COUNTER;
import static org.apache.phoenix.thirdparty.com.google.common.base.Preconditions.checkNotNull;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import javax.annotation.concurrent.GuardedBy;
import org.apache.hadoop.conf.Configuration;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.exception.SQLExceptionInfo;
import org.apache.phoenix.query.ConnectionQueryServices;
import org.apache.phoenix.query.ConnectionQueryServicesImpl;
import org.apache.phoenix.query.ConnectionlessQueryServicesImpl;
import org.apache.phoenix.query.HBaseFactoryProvider;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesImpl;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.util.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.phoenix.thirdparty.com.google.common.annotations.VisibleForTesting;
import org.apache.phoenix.thirdparty.com.google.common.cache.*;
import org.apache.phoenix.thirdparty.com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * JDBC Driver implementation of Phoenix for production. To use this driver, specify the following
 * URL: {@code jdbc:phoenix:<zookeeper quorum server name>; } Only an embedded driver is currently
 * supported (Phoenix client runs in the same JVM as the driver). Connections are lightweight and
 * are not pooled. The last part of the URL, the hbase zookeeper quorum server name, determines the
 * hbase cluster to which queries will be routed.
 * @since 0.1
 */
public final class PhoenixDriver extends PhoenixEmbeddedDriver {
  private static final Logger LOGGER = LoggerFactory.getLogger(PhoenixDriver.class);
  public static final PhoenixDriver INSTANCE;
  private static volatile String driverShutdownMsg;
  static {
    try {
      INSTANCE = new PhoenixDriver();
      try {
        // Add shutdown hook to release any resources that were never closed
        // In theory not necessary, but it won't hurt anything
        Runtime.getRuntime().addShutdownHook(new Thread() {
          @Override
          public void run() {
            final Configuration config =
              HBaseFactoryProvider.getConfigurationFactory().getConfiguration();
            final ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true)
              .setNameFormat("PHOENIX-DRIVER-SHUTDOWNHOOK" + "-thread-%s").build();
            final ExecutorService svc = Executors.newSingleThreadExecutor(threadFactory);
            try {
              Future<?> future = svc.submit(new Runnable() {
                @Override
                public void run() {
                  closeInstance(INSTANCE);
                }
              });
              // Pull the timeout value (default 5s).
              long millisBeforeShutdown = config.getLong(QueryServices.DRIVER_SHUTDOWN_TIMEOUT_MS,
                QueryServicesOptions.DEFAULT_DRIVER_SHUTDOWN_TIMEOUT_MS);

              // Close with a timeout. If this is running, we know the JVM wants to
              // go down. There may be other threads running that are holding the
              // lock. We don't want to be blocked on them (for the normal HBase retry
              // policy). We don't care about any exceptions, we're going down anyways.
              future.get(millisBeforeShutdown, TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
              LOGGER.warn("Failed to close instance", e);
            } catch (InterruptedException e) {
              LOGGER.warn("Interrupted waiting to close instance", e);
            } catch (TimeoutException e) {
              LOGGER.warn("Timed out waiting to close instance", e);
            } finally {
              // We're going down, but try to clean up.
              svc.shutdownNow();
            }
          }
        });

        // Only register the driver when we successfully register the shutdown hook
        // Don't want to register it if we're already in the process of going down.
        DriverManager.registerDriver(INSTANCE);
      } catch (IllegalStateException e) {
        LOGGER.warn(
          "Failed to register PhoenixDriver shutdown hook as the JVM is already shutting down");

        // Close the instance now because we don't have the shutdown hook
        closeInstance(INSTANCE);

        throw e;
      }
    } catch (SQLException e) {
      throw new IllegalStateException(
        "Unable to register " + PhoenixDriver.class.getName() + ": " + e.getMessage());
    }
  }

  private static void closeInstance(PhoenixDriver instance) {
    try {
      instance.close();
    } catch (SQLException e) {
      LOGGER.warn("Unable to close PhoenixDriver on shutdown", e);
    } finally {
      driverShutdownMsg = "Phoenix driver closed because server is shutting down";
    }
  }

  // One entry per cluster here
  // TODO that's not true, we can have multiple connections with different configs / principals
  private final Cache<ConnectionInfo, ConnectionQueryServices> connectionQueryServicesCache =
    initializeConnectionCache();

  public PhoenixDriver() { // for Squirrel
    // Use production services implementation
    super();
  }

  private Cache<ConnectionInfo, ConnectionQueryServices> initializeConnectionCache() {
    Configuration config = HBaseFactoryProvider.getConfigurationFactory().getConfiguration();
    int maxCacheDuration =
      config.getInt(QueryServices.CLIENT_CONNECTION_CACHE_MAX_DURATION_MILLISECONDS,
        QueryServicesOptions.DEFAULT_CLIENT_CONNECTION_CACHE_MAX_DURATION);
    RemovalListener<ConnectionInfo, ConnectionQueryServices> cacheRemovalListener =
      new RemovalListener<ConnectionInfo, ConnectionQueryServices>() {
        @Override
        public void
          onRemoval(RemovalNotification<ConnectionInfo, ConnectionQueryServices> notification) {
          String connInfoIdentifier = notification.getKey().toString();
          LOGGER.debug(
            "Expiring " + connInfoIdentifier + " because of " + notification.getCause().name());

          try {
            notification.getValue().close();
          } catch (SQLException se) {
            LOGGER.error("Error while closing expired cache connection " + connInfoIdentifier, se);
          }
        }
      };
    return CacheBuilder.newBuilder().expireAfterAccess(maxCacheDuration, TimeUnit.MILLISECONDS)
      .removalListener(cacheRemovalListener).build();
  }

  // writes guarded by "this"
  private volatile QueryServices services;

  @GuardedBy("closeLock")
  private volatile boolean closed = false;
  private final ReadWriteLock closeLock = new ReentrantReadWriteLock();

  @Override
  public QueryServices getQueryServices() throws SQLException {
    lockInterruptibly(LockMode.READ);
    try {
      checkClosed();
      // Lazy initialize QueryServices so that we only attempt to create an HBase Configuration
      // object upon the first attempt to connect to any cluster. Otherwise, an attempt will be
      // made at driver initialization time which is too early for some systems.
      QueryServices result = services;
      if (result == null) {
        synchronized (this) {
          result = services;
          if (result == null) {
            services = result = new QueryServicesImpl(getDefaultProps());
          }
        }
      }
      return result;
    } finally {
      unlock(LockMode.READ);
    }
  }

  @Override
  public boolean acceptsURL(String url) throws SQLException {
    // Accept the url only if test=true attribute not set
    return super.acceptsURL(url) && !isTestUrl(url);
  }

  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    GLOBAL_PHOENIX_CONNECTIONS_ATTEMPTED_COUNTER.increment();
    if (!acceptsURL(url)) {
      GLOBAL_FAILED_PHOENIX_CONNECTIONS.increment();
      return null;
    }
    lockInterruptibly(LockMode.READ);
    try {
      checkClosed();
      return createConnection(url, info);
    } catch (SQLException sqlException) {
      if (sqlException.getErrorCode() != SQLExceptionCode.NEW_CONNECTION_THROTTLED.getErrorCode()) {
        GLOBAL_FAILED_PHOENIX_CONNECTIONS.increment();
      }
      throw sqlException;
    } catch (Exception e) {
      GLOBAL_FAILED_PHOENIX_CONNECTIONS.increment();
      throw e;
    } finally {
      unlock(LockMode.READ);
    }
  }

  @Override
  protected ConnectionQueryServices getConnectionQueryServices(String url, final Properties infoIn)
    throws SQLException {
    lockInterruptibly(LockMode.READ);
    try {
      checkClosed();
      SQLException sqlE = null;
      boolean success = false;
      final QueryServices services = getQueryServices();
      ConnectionQueryServices connectionQueryServices = null;
      // Also performs the Kerberos login if the URL/properties request this
      final Properties info = PropertiesUtil.deepCopy(infoIn);
      final ConnectionInfo connInfo = ConnectionInfo.create(url, services.getProps(), info);
      // Set connection parameters to normalized value from URL
      info.putAll(connInfo.asProps().asMap());
      try {
        connectionQueryServices =
          connectionQueryServicesCache.get(connInfo, new Callable<ConnectionQueryServices>() {
            @Override
            public ConnectionQueryServices call() throws Exception {
              ConnectionQueryServices connectionQueryServices;
              if (connInfo.isConnectionless()) {
                connectionQueryServices =
                  new ConnectionlessQueryServicesImpl(services, connInfo, info);
              } else {
                connectionQueryServices = new ConnectionQueryServicesImpl(services, connInfo, info);
              }

              return connectionQueryServices;
            }
          });
        connectionQueryServices.init(url, info);
        success = true;
      } catch (ExecutionException ee) {
        if (ee.getCause() instanceof SQLException) {
          sqlE = (SQLException) ee.getCause();
        } else {
          throw new SQLException(ee);
        }
      } catch (SQLException e) {
        sqlE = e;
      } finally {
        if (!success) {
          // Remove from map, as initialization failed
          connectionQueryServicesCache.invalidate(connInfo);
          if (sqlE != null) {
            throw sqlE;
          }
        }
      }
      return connectionQueryServices;
    } finally {
      unlock(LockMode.READ);
    }
  }

  @GuardedBy("closeLock")
  private void checkClosed() {
    if (closed) {
      throwDriverClosedException();
    }
  }

  private void throwDriverClosedException() {
    throw new IllegalStateException(
      driverShutdownMsg != null ? driverShutdownMsg : "The Phoenix jdbc driver has been closed.");
  }

  /**
   * Invalidate the CQS in global connection query services cache.
   * @param url        The JDBC connection string
   * @param properties properties containing the fields of connection info (key of cache)
   * @throws SQLException if fails to generate key for CQS to invalidate
   */
  void invalidateCache(String url, Properties properties) throws SQLException {
    ConnectionInfo connInfo = ConnectionInfo.create(url, getQueryServices().getProps(), properties);
    LOGGER.info("Invalidating the CQS from cache for connInfo={}", connInfo);
    connectionQueryServicesCache.invalidate(connInfo);
    LOGGER.debug(connectionQueryServicesCache.asMap().keySet().stream().map(Objects::toString)
      .collect(Collectors.joining(",")));
  }

  @Override
  public synchronized void close() throws SQLException {
    lockInterruptibly(LockMode.WRITE);
    try {
      if (closed) {
        return;
      }
      closed = true;
    } finally {
      unlock(LockMode.WRITE);
    }

    if (services != null) {
      try {
        services.close();
      } finally {
        services = null;
      }
    }

    if (connectionQueryServicesCache != null) {
      try {
        for (ConnectionQueryServices cqsi : connectionQueryServicesCache.asMap().values()) {
          cqsi.close();
        }
      } catch (Exception e) {
        LOGGER.warn("Failed to close ConnectionQueryServices instance", e);
      }
    }
  }

  private enum LockMode {
    READ,
    WRITE
  };

  private void lockInterruptibly(LockMode mode) throws SQLException {
    checkNotNull(mode);
    switch (mode) {
      case READ:
        try {
          closeLock.readLock().lockInterruptibly();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new SQLExceptionInfo.Builder(SQLExceptionCode.INTERRUPTED_EXCEPTION).setRootCause(e)
            .build().buildException();
        }
        break;
      case WRITE:
        try {
          closeLock.writeLock().lockInterruptibly();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new SQLExceptionInfo.Builder(SQLExceptionCode.INTERRUPTED_EXCEPTION).setRootCause(e)
            .build().buildException();
        }
    }
  }

  private void unlock(LockMode mode) {
    checkNotNull(mode);
    switch (mode) {
      case READ:
        closeLock.readLock().unlock();
        break;
      case WRITE:
        closeLock.writeLock().unlock();
    }
  }

  @VisibleForTesting
  protected boolean checkIfCQSIIsInCache(ConnectionInfo connInfo) {
    return connectionQueryServicesCache.getIfPresent(connInfo) != null;
  }

}
