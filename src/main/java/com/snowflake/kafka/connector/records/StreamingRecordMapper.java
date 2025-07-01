package com.snowflake.kafka.connector.records;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.NumericNode;
import com.snowflake.kafka.connector.records.RecordService.SnowflakeTableRow;

import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

abstract class StreamingRecordMapper {

  protected final ObjectMapper mapper;
  protected final boolean schematizationEnabled;

  public StreamingRecordMapper(ObjectMapper mapper, boolean schematizationEnabled) {
    this.mapper = mapper;
    this.schematizationEnabled = schematizationEnabled;
  }

  abstract Map<String, Object> processSnowflakeRecord(
      SnowflakeTableRow row, boolean includeAllMetadata) throws JsonProcessingException;

  protected String getTextualValue(JsonNode valueNode) throws JsonProcessingException {
    String value;
    if (valueNode.isTextual()) {
      value = valueNode.textValue();
    } else if (valueNode.isBinary()) {
      byte[] binaryValue = Base64.getDecoder().decode(valueNode.asText());
      value = HexFormat.of().formatHex(binaryValue);
    } else if (valueNode.isNull()) {
      value = null;
    }
    //BEGIN: ENG-355/aqemia-snowflake-missing-records
    else if (valueNode.isDouble() && valueNode.doubleValue() == Double.POSITIVE_INFINITY) {
      value = "inf"; // corelate with net.snowflake.ingest.streaming.internal.DataValidationUtil.validateAndParseReal
    } else if (valueNode.isDouble() && valueNode.doubleValue() == Double.NEGATIVE_INFINITY) {
      value = "-inf";
    } else if (valueNode.isDouble() && valueNode.doubleValue() == Double.NaN) {
      value = "nan";
    }
    // END: ENG-355/aqemia-snowflake-missing-records
    else {
      value = writeValueAsStringOrNanOrInfinity(valueNode);
    }
    return value;
  }

  protected String writeValueAsStringOrNanOrInfinity(JsonNode columnNode)
      throws JsonProcessingException {
    if (columnNode instanceof NumericNode && ((NumericNode) columnNode).isNaN()) {
      // DoubleNode::isNaN() and FloatNode::isNaN() will return true on both infinite values,
      // therefore we need to handle them here, where isNaN() is true
      boolean infinity = false;
      boolean negative = false;
      if (columnNode instanceof DoubleNode) {
        double value = (columnNode).doubleValue();
        infinity = Double.isInfinite(value);
        negative = value < 0;
      } else if ((columnNode instanceof FloatNode)) {
        float value = (columnNode).floatValue();
        infinity = Float.isInfinite(value);
        negative = value < 0;
      }
      if (infinity) {
        if (negative) {
          return "-Inf";
        } else {
          return "Inf";
        }
      } else {
        return "NaN";
      }
    } else {
      return mapper.writeValueAsString(columnNode);
    }
  }
}
