package com.snowflake.kafka.connector.internal.streaming.schemaevolution.snowflake;

import static org.apache.kafka.connect.data.Schema.Type.ARRAY;
import static org.apache.kafka.connect.data.Schema.Type.BOOLEAN;
import static org.apache.kafka.connect.data.Schema.Type.BYTES;
import static org.apache.kafka.connect.data.Schema.Type.FLOAT32;
import static org.apache.kafka.connect.data.Schema.Type.FLOAT64;
import static org.apache.kafka.connect.data.Schema.Type.INT16;
import static org.apache.kafka.connect.data.Schema.Type.INT32;
import static org.apache.kafka.connect.data.Schema.Type.INT64;
import static org.apache.kafka.connect.data.Schema.Type.STRING;
import static org.apache.kafka.connect.data.Schema.Type.STRUCT;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.snowflake.kafka.connector.Utils;
import org.apache.kafka.connect.data.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StreamkapSnowflakeColumnTypeMapper extends SnowflakeColumnTypeMapper {

  private static final Logger LOGGER = LoggerFactory.getLogger(StreamkapSnowflakeColumnTypeMapper.class);

  Boolean legacyTimestampMappingEnabled = false;
  // Boolean legacyDateMappingEnabled = false;
  // Boolean legacyTimeMappingEnabled = false;

  @Override
  public String mapToColumnType(Schema.Type kafkaType, String schemaName) {
    if (schemaName != null) {

      if (kafkaType == ARRAY) {
        switch (schemaName) {
//          case "io.debezium.time.Date": return "ARRAY(DATE)";
//          case "io.debezium.time.MicroTime": return "ARRAY(TIME(6))";
//          case "io.debezium.time.Timestamp": return "ARRAY(TIMESTAMP)";
          case "io.debezium.time.Date": return "ARRAY";
          case "io.debezium.time.MicroTime": return "ARRAY";
          case "io.debezium.time.Timestamp": return "ARRAY";
        }
      } else {

        // Debezium types
        // Only where default, literal type mapping (based on kafka type) is not enough
        switch (schemaName) {
          case org.apache.kafka.connect.data.Decimal.LOGICAL_NAME:
            if (kafkaType == BYTES) {
              return "DECIMAL(38,7)";
            } else {
              return "DOUBLE";
            }
          case "io.debezium.time.MicroTime":
          case "io.debezium.time.NanoTime":
            return "TIME(6)";
          case "io.debezium.time.Time":
          case "io.debezium.time.IsoTime":
            return "TIME(3)";
          case "io.debezium.time.ZonedTimestamp":
            return !legacyTimestampMappingEnabled ? "TIMESTAMP_TZ" : "TIMESTAMP";
          case "io.debezium.time.ZonedTime":      // Snowflake doesn't have zoned 'time-only' data types
          case "io.debezium.time.Timestamp":
            return "TIMESTAMP_TZ";
          case "io.debezium.time.MicroTimestamp":
          case "io.debezium.time.NanoTimestamp":
          case "io.debezium.time.IsoTimestamp":
            return "TIMESTAMP";
          case "io.debezium.time.Date":
          case "io.debezium.time.IsoDate":
            return "DATE";
          case "io.debezium.data.Json":
            return "VARIANT";
        }
      }
    }

    //Fall back on default Snowflake type mapping
    return super.mapToColumnType(kafkaType, schemaName);
  }

  @Override
  public Schema.Type mapJsonNodeTypeToKafkaType(JsonNode value) {
    if (value == null || value.isNull()) {
      return STRING;
    } else if (value.isNumber()) {
      if (value.isShort()) {
        return INT16;
      } else if (value.isInt()) {
        return INT32;
      } else if (value.isFloat()) {
        return FLOAT32;
      } else if (value.isDouble()) {
        return FLOAT64;
      }
      return INT64;
    } else if (value.isTextual()) {
      return STRING;
    } else if (value.isBoolean()) {
      return BOOLEAN;
    } else if (value.isBinary()) {
      return BYTES;
    } else if (value.isArray()) {
      return ARRAY;
    } else if (value.isObject()) {
      return STRUCT;
    } else {
      return null;
    }
  }

  public void setStreamkapLegacyMappingConfig(Map<String, String> sinkConfig) {
    this.legacyTimestampMappingEnabled = Boolean.parseBoolean(sinkConfig.getOrDefault(Utils.LEGACY_TIMESTAMP_MAPPING_ENABLED, Utils.LEGACY_TIMESTAMP_MAPPING_DEFAULT.toString()));
  } 
}
