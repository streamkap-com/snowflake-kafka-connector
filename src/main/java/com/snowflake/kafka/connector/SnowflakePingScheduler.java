/*
 * Copyright (c) 2019 Snowflake Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.snowflake.kafka.connector;

import com.snowflake.kafka.connector.internal.KCLogger;
import com.snowflake.kafka.connector.internal.SnowflakeConnectionService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SnowflakePingScheduler manages periodic pings to Snowflake to ensure the JDBC driver's
 * application connection parameter is used regularly to register the partner ID.
 *
 * <p>The scheduler executes a lightweight query ("SELECT 1") every 7 days using the existing
 * JDBC connection.
 */
public class SnowflakePingScheduler {
  private final SnowflakeConnectionService connectionService;
  private final String taskConfigId;
  private final KCLogger logger;

  private ScheduledExecutorService scheduler;

  /**
   * Creates a new SnowflakePingScheduler instance.
   *
   * @param connectionService the Snowflake JDBC connection service to use for pinging
   * @param taskConfigId the task configuration ID for logging purposes
   * @param logger the logger instance for this scheduler
   */
  public SnowflakePingScheduler(
      SnowflakeConnectionService connectionService, String taskConfigId, KCLogger logger) {
    this.connectionService = connectionService;
    this.taskConfigId = taskConfigId;
    this.logger = logger;
  }

  /**
   * Starts the periodic ping scheduler.
   *
   * <p>The scheduler is initialized to ping Snowflake immediately on start and then every 7 days
   * thereafter.
   */
  public void start() {
    try {
      scheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "SnowflakePingScheduler-" + taskConfigId);
                t.setDaemon(true);
                return t;
              });

      scheduler.scheduleAtFixedRate(
          this::ping,
          0, // Initial delay - ping immediately on start
          1, // Period - 1 hour
          TimeUnit.MINUTES);

      logger.info("Snowflake ping scheduler started for task {}", taskConfigId);
    } catch (Exception e) {
      logger.error("Failed to start ping scheduler: {}", e.getMessage());
    }
  }

  /**
   * Stops the periodic ping scheduler.
   *
   * <p>Attempts graceful shutdown with a 5-second timeout, then forces shutdown if needed.
   */
  public void stop() {
    if (scheduler != null && !scheduler.isShutdown()) {
      try {
        scheduler.shutdown();
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
        logger.info("Snowflake ping scheduler stopped for task {}", taskConfigId);
      } catch (InterruptedException e) {
        scheduler.shutdownNow();
        logger.error("Error shutting down ping scheduler: {}", e.getMessage());
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Pings Snowflake using the existing JDBC connection to ensure partner ID is registered.
   *
   * <p>This method is called periodically (every 7 days) by the scheduler and executes a
   * lightweight query to maintain the connection and register the partner ID via the JDBC
   * connection's application parameter.
   */
  private void ping() {
    PreparedStatement stmt = null;
    try {
      if (connectionService != null) {
        // Get the underlying JDBC connection using the service's getConnection() method
        // Execute a lightweight query to ping Snowflake and ensure partner ID is registered
        // via the JDBC connection's application parameter
        Connection conn = connectionService.getConnection();
        if (conn != null) {
          stmt = conn.prepareStatement("SELECT 1");
          stmt.execute();
          logger.info("Successfully pinged Snowflake for task {}", taskConfigId);
        }
      }
    } catch (Exception e) {
      logger.warn("Failed to ping Snowflake for task {}: {}", taskConfigId, e.getMessage());
    } finally {
      if (stmt != null) {
        try {
          stmt.close();
        } catch (SQLException e) {
          logger.warn("Error closing statement: {}", e.getMessage());
        }
      }
    }
  }
}

