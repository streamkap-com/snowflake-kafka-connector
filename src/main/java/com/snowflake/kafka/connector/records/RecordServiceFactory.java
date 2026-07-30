package com.snowflake.kafka.connector.records;

import com.fasterxml.jackson.databind.ObjectMapper;

public class RecordServiceFactory {
  public static RecordService createRecordService(
      boolean isIcebergEnabled, boolean enableSchematization) {
    ObjectMapper objectMapper = new ObjectMapper();
    if (isIcebergEnabled) {
      // Streamkap override (ENG-2504): stringifies structured Iceberg RECORD_METADATA.key.
      return new RecordService(
          new StreamkapIcebergTableStreamingRecordMapper(objectMapper, enableSchematization),
          objectMapper);
    } else {
      return new RecordService(
          new SnowflakeTableStreamingRecordMapper(objectMapper, enableSchematization),
          objectMapper);
    }
  }
}
