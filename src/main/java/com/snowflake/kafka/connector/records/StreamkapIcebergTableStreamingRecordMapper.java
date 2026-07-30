package com.snowflake.kafka.connector.records;

import static com.snowflake.kafka.connector.records.RecordService.KEY;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * Streamkap override of {@link IcebergTableStreamingRecordMapper}. Keeps fork customisations out of
 * the upstream mapper so future Snowflake merges stay low-conflict (see package CLAUDE.md).
 *
 * <p>ENG-2504: the Iceberg metadata schema hardcodes {@code key STRING}, but a structured/composite
 * CDC key (JSON object/array) fails to ingest into a STRING column. We stringify a non-textual key
 * to JSON text (mirroring upstream's own headers handling); textual keys pass through unchanged.
 */
class StreamkapIcebergTableStreamingRecordMapper extends IcebergTableStreamingRecordMapper {

  public StreamkapIcebergTableStreamingRecordMapper(
      ObjectMapper objectMapper, boolean schematizationEnabled) {
    super(objectMapper, schematizationEnabled);
  }

  @Override
  protected Map<String, Object> getMapForMetadata(JsonNode metadataNode)
      throws JsonProcessingException {
    Map<String, Object> values = super.getMapForMetadata(metadataNode);
    JsonNode keyNode = metadataNode.get(KEY);
    if (keyNode != null && !keyNode.isNull()) {
      values.put(KEY, getTextualValue(keyNode));
    }
    return values;
  }
}
