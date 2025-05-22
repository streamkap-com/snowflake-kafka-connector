package com.snowflake.kafka.connector.records;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BinaryNode;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StreamingRecordMapperTest {

    // Minimal concrete subclass for testing
    static class TestStreamingRecordMapper extends StreamingRecordMapper {
        public TestStreamingRecordMapper(ObjectMapper mapper, boolean schematizationEnabled) {
            super(mapper, schematizationEnabled);
        }
        @Override
        public java.util.Map<String, Object> processSnowflakeRecord(RecordService.SnowflakeTableRow row, boolean includeAllMetadata) {
            return null;
        }
    }

    @Test
    void testGetTextualValueForBinary_Streamkap() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StreamingRecordMapper recordMapper = new TestStreamingRecordMapper(mapper, false);

        // Example raw bytes with special characters (including non-ASCII)
        byte[] rawBytes = new byte[] {0x41, 0x42, 0x43, (byte)0xE2, (byte)0x98, (byte)0x83, 0x0A, 0x7F, (byte)0xFF};
        // 0xE2 0x98 0x83 is the UTF-8 encoding for '☃' (snowman), 0x0A is newline, 0x7F is DEL, 0xFF is non-ASCII
        BinaryNode binaryNode = new BinaryNode(rawBytes);

        String result = recordMapper.getTextualValue(binaryNode);

        assertEquals("414243E298830A7FFF", result.toUpperCase());
    }

    @Test
    void testGetTextualValueForMySQL_BinaryUUID_Streamkap() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StreamingRecordMapper recordMapper = new TestStreamingRecordMapper(mapper, false);

        UUID uuid = UUID.fromString("396159d7-5f2c-4b3c-8869-382a92236cf9".toUpperCase());
        byte[] uuidBytes = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) uuidBytes[i] = (byte) (msb >>> (8 * (7 - i)));
        for (int i = 8; i < 16; i++) uuidBytes[i] = (byte) (lsb >>> (8 * (15 - i)));
        BinaryNode base64Node = new BinaryNode(uuidBytes);

        String result = recordMapper.getTextualValue(base64Node);

        assertEquals("396159D75F2C4B3C8869382A92236CF9", result.toUpperCase());
    }

    // @Test
    // void testGetTextualValueForMySQL_BinaryUUID_Java17_Streamkap() throws Exception {
    //     UUID uuid = UUID.fromString("396159d7-5f2c-4b3c-8869-382a92236cf9".toUpperCase());
    //     byte[] uuidBytes = new byte[16];
    //     long msb = uuid.getMostSignificantBits();
    //     long lsb = uuid.getLeastSignificantBits();
    //     for (int i = 0; i < 8; i++) uuidBytes[i] = (byte) (msb >>> (8 * (7 - i)));
    //     for (int i = 8; i < 16; i++) uuidBytes[i] = (byte) (lsb >>> (8 * (15 - i)));

    //     BinaryNode binaryNode = new BinaryNode(uuidBytes);

    //     String result = StreamingRecordMapperTest.getTextualValue(binaryNode);

    //     assertEquals("396159D75F2C4B3C8869382A92236CF9", result.toUpperCase());
    // }

    // Java 17+ snippet using `HexFormat`
    // This method is not part of the original code but is added for completeness
    // and to demonstrate the use of `HexFormat` for binary data conversion.
    // protected static String getTextualValue(JsonNode valueNode) throws JsonProcessingException {
    //     String value = null;
    //     if (valueNode.isTextual()) {
    //         value = valueNode.textValue();
    //     } else if (valueNode.isBinary()) {
    //         byte[] binaryValue = Base64.getDecoder().decode(valueNode.asText());
    //         value = HexFormat.of().formatHex(binaryValue).toString();
    //     } else if (valueNode.isNull()) {
    //         value = null;
    //     }
    //     //BEGIN: ENG-355/aqemia-snowflake-missing-records
    //     else if (valueNode.isDouble() && valueNode.doubleValue() == Double.POSITIVE_INFINITY) {
    //         value = "inf"; // corelate with net.snowflake.ingest.streaming.internal.DataValidationUtil.validateAndParseReal
    //     } else if (valueNode.isDouble() && valueNode.doubleValue() == Double.NEGATIVE_INFINITY) {
    //         value = "-inf";
    //     } else if (valueNode.isDouble() && valueNode.doubleValue() == Double.NaN) {
    //         value = "nan";
    //     }
    //     return value;
    // }
}