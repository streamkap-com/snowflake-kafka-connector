package com.snowflake.kafka.connector.records;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.snowflake.kafka.connector.Utils;
import com.snowflake.kafka.connector.records.RecordService.SnowflakeTableRow;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ENG-2504: covers the Streamkap override that stringifies a structured/composite Iceberg
 * RECORD_METADATA.key so it fits the hardcoded `key STRING` column instead of failing ingestion.
 * Upstream metadata handling is covered by {@link IcebergTableStreamingRecordMapperTest}.
 */
class StreamkapIcebergTableStreamingRecordMapperTest {
  private static final ObjectMapper objectMapper = new ObjectMapper();

  @ParameterizedTest(name = "{0}")
  @MethodSource("prepareKeyData")
  void shouldStringifyStructuredMetadataKey(
      String description, SnowflakeTableRow row, Map<String, Object> expected)
      throws JsonProcessingException {
    StreamkapIcebergTableStreamingRecordMapper mapper =
        new StreamkapIcebergTableStreamingRecordMapper(objectMapper, false);
    StreamkapIcebergTableStreamingRecordMapper mapperSchematization =
        new StreamkapIcebergTableStreamingRecordMapper(objectMapper, true);

    Map<String, Object> result = mapper.processSnowflakeRecord(row, true);
    Map<String, Object> resultSchematized = mapperSchematization.processSnowflakeRecord(row, true);

    assertThat(result.get(Utils.TABLE_COLUMN_METADATA)).isEqualTo(expected);
    assertThat(resultSchematized.get(Utils.TABLE_COLUMN_METADATA)).isEqualTo(expected);
  }

  private static Stream<Arguments> prepareKeyData() throws JsonProcessingException {
    return Stream.of(
        // A plain string key is unchanged (proves upstream behaviour is preserved).
        Arguments.of(
            "Plain string key is preserved",
            buildRow("{\"key\": \"pk-123\", \"headers\": {}}"),
            ImmutableMap.of("key", "pk-123", "headers", ImmutableMap.of())),
        // A structured/composite key is serialized to JSON text.
        Arguments.of(
            "Structured (composite) key is stringified",
            buildRow("{\"key\": {\"id\": 1, \"sub\": \"a\"}, \"headers\": {}}"),
            ImmutableMap.of("key", "{\"id\":1,\"sub\":\"a\"}", "headers", ImmutableMap.of())),
        // An array key is serialized to JSON text.
        Arguments.of(
            "Array key is stringified",
            buildRow("{\"key\": [1, 2], \"headers\": {}}"),
            ImmutableMap.of("key", "[1,2]", "headers", ImmutableMap.of())));
  }

  private static SnowflakeTableRow buildRow(String metadata) throws JsonProcessingException {
    return new SnowflakeTableRow(
        new SnowflakeRecordContent(objectMapper.readTree("{}")),
        objectMapper.readTree(metadata));
  }
}
