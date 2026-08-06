package com.snowflake.kafka.connector.internal.streaming.schemaevolution.iceberg;

import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;

/**
 * Streamkap override of {@link IcebergColumnTypeMapper}. Keeps fork customisations out of the
 * upstream mapper so future Snowflake merges stay low-conflict (see package CLAUDE.md).
 *
 * <p>ENG-2596: upstream maps a Kafka {@code Decimal} to {@code VARCHAR} for Iceberg tables, whereas
 * classic tables use the Streamkap mapping {@code DECIMAL(38,7)} (STR-3827 / ENG-2503). That made
 * the same source column land as text on Iceberg but numeric on classic. Map it to
 * {@code DECIMAL(38,7)} here so both destination types agree.
 *
 * <p>Applies only to columns the connector <em>creates</em> (schema evolution from the Kafka
 * record schema). Columns that already exist are read back through
 * {@code mapToColumnTypeFromIcebergSchema}, which preserves their declared {@code DECIMAL(p,s)}.
 *
 * <p>Carries the same caveat as the classic mapping: scale is capped at 7, so a source scale &gt; 7
 * (or &gt; 31 integer digits) loses precision.
 */
class StreamkapIcebergColumnTypeMapper extends IcebergColumnTypeMapper {

  /** Mirrors StreamkapSnowflakeColumnTypeMapper's classic-table Decimal mapping. */
  private static final String STREAMKAP_DECIMAL = "DECIMAL(38,7)";

  @Override
  String mapToColumnTypeFromKafkaSchema(Schema.Type kafkaType, String schemaName) {
    if (kafkaType == Schema.Type.BYTES && Decimal.LOGICAL_NAME.equals(schemaName)) {
      return STREAMKAP_DECIMAL;
    }
    return super.mapToColumnTypeFromKafkaSchema(kafkaType, schemaName);
  }
}
