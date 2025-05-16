package com.snowflake.kafka.connector.internal.streaming.schemaevolution.snowflake;

import com.snowflake.kafka.connector.internal.SnowflakeConnectionService;
import com.snowflake.kafka.connector.internal.SnowflakeKafkaConnectorException;
import com.snowflake.kafka.connector.internal.streaming.schemaevolution.SchemaEvolutionService;
import com.snowflake.kafka.connector.internal.streaming.schemaevolution.SchemaEvolutionTargetItems;
import com.snowflake.kafka.connector.internal.streaming.schemaevolution.TableSchema;
import com.snowflake.kafka.connector.internal.streaming.schemaevolution.TableSchemaResolver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.snowflake.kafka.connector.templating.StreamkapQueryTemplate;
import net.snowflake.ingest.streaming.internal.ColumnProperties;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.connect.sink.SinkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SnowflakeSchemaEvolutionService implements SchemaEvolutionService {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(SnowflakeSchemaEvolutionService.class);

  private final SnowflakeConnectionService conn;
  private final TableSchemaResolver tableSchemaResolver;

  public SnowflakeSchemaEvolutionService(SnowflakeConnectionService conn) {
    this.conn = conn;
    this.tableSchemaResolver = new SnowflakeTableSchemaResolver();
  }

  public SnowflakeSchemaEvolutionService(
      SnowflakeConnectionService conn, TableSchemaResolver tableSchemaResolver) {
    this.conn = conn;
    this.tableSchemaResolver = tableSchemaResolver;
  }

  /**
   * Execute an ALTER TABLE command if there is any extra column that needs to be added, or any
   * column nullability that needs to be updated, used by schema evolution
   *
   * @param targetItems target items for schema evolution such as table name, columns to drop,
   *     columns to add
   * @param record the sink record that contains the schema and actual data
   * @param existingSchema is unused in this implementation
   */
  @Override
  public void evolveSchemaIfNeeded(
      SchemaEvolutionTargetItems targetItems,
      SinkRecord record,
      Map<String, ColumnProperties> existingSchema,
      StreamkapQueryTemplate streamkapQueryTemplate,
      String targetTableName) {
    String tableName = (!StringUtils.isEmpty(targetTableName) ? targetTableName : targetItems.getTableName());
    List<String> columnsToDropNullability = targetItems.getColumnsToDropNonNullability();
    // Update nullability if needed, ignore any exceptions since other task might be succeeded
    if (!columnsToDropNullability.isEmpty()) {
      LOGGER.debug(
          "Dropping nonNullability for table: {} columns: {}", tableName, columnsToDropNullability);
      try {
        conn.alterNonNullableColumns(targetItems.getTableName(), columnsToDropNullability);
      } catch (SnowflakeKafkaConnectorException e) {
        LOGGER.warn(
            String.format(
                "Failure altering table to update nullability: %s, this could happen when multiple"
                    + " partitions try to alter the table at the same time and the warning could be"
                    + " ignored",
                tableName),
            e);
      }
    }
    List<String> columnsToAdd = targetItems.getColumnsToAdd();
    // Add columns if needed, ignore any exceptions since other task might be succeeded
    if (!columnsToAdd.isEmpty()) {
      LOGGER.debug("Adding columns to table: {} columns: {}", tableName, columnsToAdd);

      List<String> fieldNamesOrderedAsOnSource = Stream.concat(
              record.keySchema() != null ? record.keySchema().fields().stream().map(f -> f.name()) : Stream.<String>empty(),
              record.valueSchema() != null ? record.valueSchema().fields().stream().map(f -> f.name())  : Stream.<String>empty()
      ).collect(Collectors.toList());
      List<String> extraColNamesOrderedAsOnSource = new ArrayList<>(columnsToAdd);
      extraColNamesOrderedAsOnSource.sort(
              Comparator.comparingInt(fieldNamesOrderedAsOnSource::indexOf));

      TableSchema tableSchema =
          tableSchemaResolver.resolveTableSchemaFromRecord(record, extraColNamesOrderedAsOnSource);
      try {
        conn.appendColumnsToTable(tableName, tableSchema.getColumnInfos());
      } catch (SnowflakeKafkaConnectorException e) {
        LOGGER.warn(
            String.format(
                "Failure altering table to add column: %s, this could happen when multiple"
                    + " partitions try to alter the table at the same time and the warning could be"
                    + " ignored",
                tableName),
            e);
      }

      if( streamkapQueryTemplate.isApplyDynamicTableScript()) {
        streamkapQueryTemplate.applyCreateScriptIfAvailable(tableName, record, conn);
      }
    }
  }
}
