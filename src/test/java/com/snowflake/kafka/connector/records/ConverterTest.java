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
package com.snowflake.kafka.connector.records;

import static com.snowflake.kafka.connector.records.RecordService.ISO_DATE_TIME_FORMAT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.snowflake.kafka.connector.internal.SnowflakeKafkaConnectorException;
import com.snowflake.kafka.connector.mock.MockSchemaRegistryClient;
import io.confluent.connect.avro.AvroConverter;
import io.confluent.connect.avro.AvroData;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.snowflake.client.jdbc.internal.apache.commons.codec.binary.Hex;
import org.apache.avro.LogicalTypes;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.storage.SimpleHeaderConverter;
import org.junit.jupiter.api.Test;

public class ConverterTest {

  private static final ObjectMapper mapper = new ObjectMapper();
  private static final String TEST_FILE_NAME = "test.avro";

  private static final String TEST_TOPIC = "test";

  private static ThreadLocal<GregorianCalendar> CALENDAR_THREAD_SAFE =
      ThreadLocal.withInitial(
          () -> {
            GregorianCalendar gregorianCalendar =
                new GregorianCalendar(1970, Calendar.JANUARY, 1, 0, 0, 0);
            gregorianCalendar.setTimeZone(TimeZone.getTimeZone("UTC"));
            gregorianCalendar.add(Calendar.DATE, 10000);
            return gregorianCalendar;
          });

  @Test
  public void testJsonConverter() {
    SnowflakeConverter converter = new SnowflakeJsonConverter();

    ObjectNode node = mapper.createObjectNode();

    node.put("str", "test");
    node.put("num", 123);

    SchemaAndValue sv =
        converter.toConnectData("test", node.toString().getBytes(StandardCharsets.UTF_8));

    assertEquals(SnowflakeJsonSchema.NAME, sv.schema().name());

    SnowflakeRecordContent content = assertInstanceOf(SnowflakeRecordContent.class, sv.value());

    JsonNode[] jsonNodes = content.getData();

    assertEquals(1, jsonNodes.length);
    assertEquals(node.toString(), jsonNodes[0].toString());

    // null value
    sv = converter.toConnectData("test", null);
    content = assertInstanceOf(SnowflakeRecordContent.class, sv.value());
    assertEquals("{}", content.getData()[0].toString());
  }

  @Test
  public void testAvroConverter() throws IOException {
    // todo: test schema registry
    URL resource = ConverterTest.class.getResource(TEST_FILE_NAME);

    byte[] testFile = Files.readAllBytes(Paths.get(resource.getFile()));

    SnowflakeConverter converter = new SnowflakeAvroConverterWithoutSchemaRegistry();

    SchemaAndValue sv = converter.toConnectData("test", testFile);

    assertEquals(SnowflakeJsonSchema.NAME, sv.schema().name());

    SnowflakeRecordContent content = assertInstanceOf(SnowflakeRecordContent.class, sv.value());

    JsonNode[] jsonNodes = content.getData();

    assertEquals(2, jsonNodes.length);

    assertEquals("{\"name\":\"foo\",\"age\":30}", jsonNodes[0].toString());
    assertEquals("{\"name\":\"bar\",\"age\":29}", jsonNodes[1].toString());

    // null value
    sv = converter.toConnectData("test", null);
    content = assertInstanceOf(SnowflakeRecordContent.class, sv.value());
    assertEquals("{}", content.getData()[0].toString());
  }

  @Test
  public void testAvroWithSchemaRegistry() throws IOException {
    MockSchemaRegistryClient client = new MockSchemaRegistryClient();
    SnowflakeAvroConverter converter = new SnowflakeAvroConverter();
    converter.setSchemaRegistry(client);
    SchemaAndValue input = converter.toConnectData("test", client.getData());
    SnowflakeRecordContent content = assertInstanceOf(SnowflakeRecordContent.class, input.value());
    assertEquals(1, content.getData().length);
    assertEquals(mapper.readTree("{\"int\":1234}").asText(), content.getData()[0].asText());

    // null value
    input = converter.toConnectData("test", null);
    content = assertInstanceOf(SnowflakeRecordContent.class, input.value());
    assertEquals("{}", content.getData()[0].toString());
  }

  @Test
  public void testAvroWithSchemaRegistryAndReaderSchema() throws IOException {
    MockSchemaRegistryClient client = new MockSchemaRegistryClient();
    SnowflakeAvroConverter converter = new SnowflakeAvroConverter();
    Map<String, String> configs = new HashMap<String, String>();
    configs.put(
        SnowflakeAvroConverter.READER_SCHEMA,
        "{\"name\":\"test_avro\",\"type\":\"record\",\"fields\":[{\"name\":\"int\",\"type\":\"int\"},{\"name\":\"newfield\",\"type\":\"int\",\"default\":"
            + " 1}]}");
    configs.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "http://fake-url");
    converter.configure(configs, false);
    converter.setSchemaRegistry(client);

    SchemaAndValue input = converter.toConnectData("test", client.getData());
    SnowflakeRecordContent content = assertInstanceOf(SnowflakeRecordContent.class, input.value());
    assertEquals(1, content.getData().length);
    assertEquals(
        mapper.readTree("{\"int\":1234, \"newfield\":1}").asText(), content.getData()[0].asText());

    // null value
    input = converter.toConnectData("test", null);
    content = assertInstanceOf(SnowflakeRecordContent.class, input.value());
    assertEquals("{}", content.getData()[0].toString());
  }

  @Test
  public void testAvroWithSchemaRegistryAndWrongReaderSchema() {
    SnowflakeAvroConverter converter = new SnowflakeAvroConverter();
    Map<String, String> configs = new HashMap<>();
    configs.put(
        SnowflakeAvroConverter.READER_SCHEMA,
        "{\"name\":\"test_avro\",\"type\":\"record\",\"fields\":[{\"name\":\"int\",\"type\":\"int\"},{\"name\":\"newfield\",\"type\":\"int\",\"default\":"
            + " 1},{\"name\":\"missingfield\",\"type\"::\"int\"}]}");
    configs.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "http://fake-url");

    assertThrows(SnowflakeKafkaConnectorException.class, () -> converter.configure(configs, false));
  }

  @Test
  public void testAvroWithSchemaRegistryByteInput() throws IOException {
    // Define AVRO Schema
    org.apache.avro.Schema decimalType =
        LogicalTypes.decimal(20, 4)
            .addToSchema(org.apache.avro.Schema.create(org.apache.avro.Schema.Type.BYTES));
    org.apache.avro.Schema schemaWithDecimal =
        org.apache.avro.SchemaBuilder.record("MyRecord")
            .fields()
            .name("bytesDecimal")
            .type(decimalType)
            .noDefault()
            .endRecord();
    System.out.println(schemaWithDecimal.toString(true));

    // Create AVRO object with the schema
    BigDecimal testDecimal = new BigDecimal("90.0000");
    BigInteger testInt = testDecimal.unscaledValue();
    GenericRecord avroRecord = new GenericData.Record(schemaWithDecimal);
    avroRecord.put("bytesDecimal", testInt.toByteArray());

    // Verify that byte representation of unscaled BigDecimal(90.0000) is equivalent with
    // BigInteger("0DBBA0", 16)
    assertArrayEquals(new BigInteger("0DBBA0", 16).toByteArray(), testInt.toByteArray());

    // Convert AVRO data to Kafka Connect Data for AVRO converter
    AvroData avroData = new AvroData(100);
    SchemaAndValue schemaAndValue = avroData.toConnectData(schemaWithDecimal, avroRecord);

    // Use Confluent AVRO converter to convert AVRO data into byte array
    SchemaRegistryClient schemaRegistry =
        new io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient();
    AvroConverter avroConverter = new AvroConverter(schemaRegistry);
    avroConverter.configure(
        Collections.singletonMap("schema.registry.url", "http://fake-url"), false);
    byte[] converted =
        avroConverter.fromConnectData(TEST_TOPIC, schemaAndValue.schema(), schemaAndValue.value());

    // The byte array "converted" in the previous line is the value that gets stored in Kafka in a
    // real cluster.
    System.out.println(
        Hex.encodeHexString(
            converted)); // 0000000001060dbba0, 0dbba0 is the encoding for the BigDecimal

    // Use Snowflake AVRO converter to convert byte array to JSON
    SnowflakeAvroConverter converter = new SnowflakeAvroConverter();
    converter.setSchemaRegistry(schemaRegistry);
    SchemaAndValue avroInputValue = converter.toConnectData(TEST_TOPIC, converted);
    SnowflakeRecordContent content = (SnowflakeRecordContent) avroInputValue.value();

    // This string is exactly what will appear in Snowflake Database.
    assertEquals("{\"bytesDecimal\":90.0}", content.getData()[0].toString());
  }

  @Test
  public void testBrokenRecord() throws IOException {
    byte[] data = "fasfas".getBytes(StandardCharsets.UTF_8);
    SnowflakeConverter converter = new SnowflakeJsonConverter();
    SchemaAndValue result = converter.toConnectData("test", data);
    SnowflakeRecordContent content = assertInstanceOf(SnowflakeRecordContent.class, result.value());
    assertTrue(content.isBroken());
    assertArrayEquals(data, content.getBrokenData());

    converter = new SnowflakeAvroConverter();
    result = converter.toConnectData("test", data);
    content = assertInstanceOf(SnowflakeRecordContent.class, result.value());
    assertTrue(content.isBroken());
    assertArrayEquals(data, content.getBrokenData());

    MockSchemaRegistryClient client = new MockSchemaRegistryClient();

    byte[] brokenAvroData =
        new byte[] {(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01};
    result = converter.toConnectData("test", brokenAvroData);
    content = assertInstanceOf(SnowflakeRecordContent.class, result.value());
    assertTrue(content.isBroken());
    assertArrayEquals(brokenAvroData, content.getBrokenData());

    ((SnowflakeAvroConverter) converter).setSchemaRegistry(client);
    result = converter.toConnectData("test", brokenAvroData);
    content = assertInstanceOf(SnowflakeRecordContent.class, result.value());
    assertTrue(content.isBroken());
    assertArrayEquals(brokenAvroData, content.getBrokenData());

    converter = new SnowflakeAvroConverterWithoutSchemaRegistry();
    result = converter.toConnectData("test", data);
    content = assertInstanceOf(SnowflakeRecordContent.class, result.value());
    assertTrue(content.isBroken());
    assertArrayEquals(data, content.getBrokenData());
  }

  @Test
  public void testConnectJsonConverter_MapInt64() throws JsonProcessingException {
    JsonConverter jsonConverter = new JsonConverter();
    Map<String, ?> config = Collections.singletonMap("schemas.enable", false);
    jsonConverter.configure(config, false);
    Map<String, Object> jsonMap = new HashMap<>();
    // Value will map to int64.
    jsonMap.put("test", Integer.MAX_VALUE);
    SchemaAndValue schemaAndValue =
        jsonConverter.toConnectData("test", mapper.writeValueAsBytes(jsonMap));
    JsonNode result =
        RecordService.convertToJson(schemaAndValue.schema(), schemaAndValue.value(), false);

    ObjectNode expected = mapper.createObjectNode();
    expected.put("test", Integer.MAX_VALUE);
    assertEquals(expected.toString(), result.toString());
  }

  @Test
  public void testConnectJsonConverter_INT32_Time_Thread_Safe()
      throws ExecutionException, InterruptedException {

    Callable<Boolean> task = () -> testSimpleDataFormat_jsonConverter_thread_safe();

    // pool with 5 threads
    ExecutorService exec = Executors.newFixedThreadPool(5);
    List<Future<Boolean>> results = new ArrayList<>();

    // perform 10 date conversions
    for (int i = 0; i < 50; i++) {
      results.add(exec.submit(task));
    }
    exec.shutdown();

    // look at the results
    for (Future<Boolean> result : results) {
      assertTrue(result.get());
    }
  }

  private boolean testSimpleDataFormat_jsonConverter_thread_safe() {
    JsonConverter jsonConverter = new JsonConverter();
    Map<String, ?> config = Collections.singletonMap("schemas.enable", true);

    jsonConverter.configure(config, false);

    String value =
        "{ \"schema\": { \"type\": \"int32\", \"name\": \"org.apache.kafka.connect.data.Date\","
            + " \"version\": 1 }, \"payload\": 10000 }";
    SchemaAndValue schemaInputValue = jsonConverter.toConnectData("test", value.getBytes());

    JsonNode result =
        RecordService.convertToJson(schemaInputValue.schema(), schemaInputValue.value(), false);
    System.out.println("Record Service result:" + result + " Thread :" + Thread.currentThread());

    String exptectedDateTimeFormatStr =
        ISO_DATE_TIME_FORMAT.get().format(CALENDAR_THREAD_SAFE.get().getTime());
    return result.toString().contains(exptectedDateTimeFormatStr);
  }


  @Test
  public void testDebeziumDate_jsonConverter() {
    JsonConverter jsonConverter = new JsonConverter();
    Map<String, ?> config = Map.of("schemas.enable", true);
    jsonConverter.configure(config, false);

    int daysFromEpoch = 10001;
    String value =
          "{ \"schema\": { \"type\": \"int32\", \"name\": \"io.debezium.time.Date\", \"version\": 1 }, \"payload\": %d }".formatted(daysFromEpoch);
    SchemaAndValue schemaInputValue = jsonConverter.toConnectData("test", value.getBytes());

    JsonNode result = RecordService.convertToJson(schemaInputValue.schema(), schemaInputValue.value(), false);
    String dateOfEpochIso = LocalDate.ofEpochDay(daysFromEpoch).format(DateTimeFormatter.ISO_LOCAL_DATE);

    assertEquals(dateOfEpochIso, result.asText());

  }

  @Test
  public void testDebeziumTimestamp_jsonConverter() {
    JsonConverter jsonConverter = new JsonConverter();
    Map<String, ?> config = Map.of("schemas.enable", true);
    jsonConverter.configure(config, false);

    // 86400000 ms = exactly 1 day from epoch
    long epochMillis = 86_400_000L;
    String value =
          "{ \"schema\": { \"type\": \"int64\", \"name\": \"io.debezium.time.Timestamp\", \"version\": 1 }, \"payload\": %d }".formatted(epochMillis);
    SchemaAndValue schemaInputValue = jsonConverter.toConnectData("test", value.getBytes());

    JsonNode result = RecordService.convertToJson(schemaInputValue.schema(), schemaInputValue.value(), false);

    assertEquals("1970-01-02T00:00:00", result.asText());
  }

  @Test
  public void testDebeziumMicroTimestamp_jsonConverter() {
    JsonConverter jsonConverter = new JsonConverter();
    Map<String, ?> config = Map.of("schemas.enable", true);
    jsonConverter.configure(config, false);

    // 86400000000 µs = exactly 1 day from epoch
    long epochMicros = 86_400_000_000L;
    String value =
          "{ \"schema\": { \"type\": \"int64\", \"name\": \"io.debezium.time.MicroTimestamp\", \"version\": 1 }, \"payload\": %d }".formatted(epochMicros);
    SchemaAndValue schemaInputValue = jsonConverter.toConnectData("test", value.getBytes());

    JsonNode result = RecordService.convertToJson(schemaInputValue.schema(), schemaInputValue.value(), false);

    assertEquals("1970-01-02T00:00:00", result.asText());
  }

  @Test
  public void testDebeziumNanoTimestamp_jsonConverter() {
    JsonConverter jsonConverter = new JsonConverter();
    Map<String, ?> config = Map.of("schemas.enable", true);
    jsonConverter.configure(config, false);

    // 86400000000000 ns = exactly 1 day from epoch
    long epochNanos = 86_400_000_000_000L;
    String value =
          "{ \"schema\": { \"type\": \"int64\", \"name\": \"io.debezium.time.NanoTimestamp\", \"version\": 1 }, \"payload\": %d }".formatted(epochNanos);
    SchemaAndValue schemaInputValue = jsonConverter.toConnectData("test", value.getBytes());

    JsonNode result = RecordService.convertToJson(schemaInputValue.schema(), schemaInputValue.value(), false);

    assertEquals("1970-01-02T00:00:00", result.asText());
  }

  @Test
  public void testDebeziumTime_jsonConverter() {
    JsonConverter jsonConverter = new JsonConverter();
    Map<String, ?> config = Map.of("schemas.enable", true);
    jsonConverter.configure(config, false);

    // 10000 ms = 10 seconds
    int millisOfDay = 10_000;
    String value =
          "{ \"schema\": { \"type\": \"int32\", \"name\": \"io.debezium.time.Time\", \"version\": 1 }, \"payload\": %d }".formatted(millisOfDay);
    SchemaAndValue schemaInputValue = jsonConverter.toConnectData("test", value.getBytes());

    JsonNode result = RecordService.convertToJson(schemaInputValue.schema(), schemaInputValue.value(), false);

    assertEquals("00:00:10", result.asText());
  }

  @Test
  public void testDebeziumMicroTime_jsonConverter() {
    JsonConverter jsonConverter = new JsonConverter();
    Map<String, ?> config = Map.of("schemas.enable", true);
    jsonConverter.configure(config, false);

    // 10000000 µs = 10 seconds
    long microsOfDay = 10_000_000L;
    String value =
          "{ \"schema\": { \"type\": \"int64\", \"name\": \"io.debezium.time.MicroTime\", \"version\": 1 }, \"payload\": %d }".formatted(microsOfDay);
    SchemaAndValue schemaInputValue = jsonConverter.toConnectData("test", value.getBytes());

    JsonNode result = RecordService.convertToJson(schemaInputValue.schema(), schemaInputValue.value(), false);

    assertEquals("00:00:10", result.asText());
  }

  @Test
  public void testDebeziumNanoTime_jsonConverter() {
    JsonConverter jsonConverter = new JsonConverter();
    Map<String, ?> config = Map.of("schemas.enable", true);
    jsonConverter.configure(config, false);

    // 10000000000 ns = 10 seconds
    long nanosOfDay = 10_000_000_000L;
    String value =
          "{ \"schema\": { \"type\": \"int64\", \"name\": \"io.debezium.time.NanoTime\", \"version\": 1 }, \"payload\": %d }".formatted(nanosOfDay);
    SchemaAndValue schemaInputValue = jsonConverter.toConnectData("test", value.getBytes());

    JsonNode result = RecordService.convertToJson(schemaInputValue.schema(), schemaInputValue.value(), false);

    assertEquals("00:00:10", result.asText());
  }

  /**
   * Pins the JVM default zone to a DST-observing zone so a regression to {@code
   * ZoneId.systemDefault()} (instead of {@code ZoneOffset.UTC}) fails here even when the build
   * runs on a UTC CI runner, where systemDefault() == UTC and the bug would otherwise go
   * unnoticed. Asserts against two dates on opposite sides of a DST boundary so a fixed-offset
   * bug (e.g. hardcoding a non-UTC zone) cannot coincidentally pass.
   */
  @Test
  public void testDebeziumTimestamp_jsonConverter_isTimezoneIndependent() {
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));

      JsonConverter jsonConverter = new JsonConverter();
      jsonConverter.configure(Map.of("schemas.enable", true), false);

      // 2026-01-15T12:00:00Z -- EST (UTC-5) in America/New_York
      assertEquals("2026-01-15T12:00:00", convertDebeziumTimestamp(jsonConverter, 1768478400000L));
      // 2026-07-15T12:00:00Z -- EDT (UTC-4) in America/New_York
      assertEquals("2026-07-15T12:00:00", convertDebeziumTimestamp(jsonConverter, 1784116800000L));
      // 2026-03-08T02:20:49Z -- the exact value cited in this PR's own test plan as an example
      // DST-gap value. Under the buggy ZoneId.systemDefault() code this reinterprets as
      // 2026-03-07T21:20:49 (-5h, EST); under UTC it must round-trip unchanged.
      assertEquals("2026-03-08T02:20:49", convertDebeziumTimestamp(jsonConverter, 1772936449000L));
    } finally {
      TimeZone.setDefault(original);
    }
  }

  private static String convertDebeziumTimestamp(JsonConverter jsonConverter, long epochMillis) {
    String value =
        "{ \"schema\": { \"type\": \"int64\", \"name\": \"io.debezium.time.Timestamp\", \"version\": 1 }, \"payload\": %d }"
            .formatted(epochMillis);
    SchemaAndValue schemaInputValue = jsonConverter.toConnectData("test", value.getBytes());
    return RecordService.convertToJson(schemaInputValue.schema(), schemaInputValue.value(), false)
        .asText();
  }

  /**
   * TimestampConv (the backend SMT) only rewrites top-level Struct fields matching its
   * schema.name filter -- it cannot see into ARRAY element schemas, so array elements of
   * io.debezium.time.Timestamp reach this path with their raw Debezium schema name intact. This
   * pins a non-UTC default zone for the same reason as the scalar test above.
   */
  @Test
  public void testDebeziumTimestampArray_jsonConverter_isTimezoneIndependent() {
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));

      Schema elementSchema =
          SchemaBuilder.int64().name("io.debezium.time.Timestamp").version(1).build();
      Schema arraySchema = SchemaBuilder.array(elementSchema).build();

      JsonNode result =
          RecordService.convertToJson(
              arraySchema, List.of(1768478400000L, 1784116800000L), false);

      assertEquals(2, result.size());
      assertEquals("2026-01-15T12:00:00", result.get(0).asText());
      assertEquals("2026-07-15T12:00:00", result.get(1).asText());
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  public void testConnectJsonConverter_MapBigDecimalExceedsMaxPrecision()
      throws JsonProcessingException {
    JsonConverter jsonConverter = new JsonConverter();
    Map<String, ?> config = Collections.singletonMap("schemas.enable", false);
    jsonConverter.configure(config, false);
    Map<String, Object> jsonMap = new HashMap<>();
    jsonMap.put("test", new BigDecimal("999999999999999999999999999999999999999"));
    SchemaAndValue schemaAndValue =
        jsonConverter.toConnectData("test", mapper.writeValueAsBytes(jsonMap));
    JsonNode result =
        RecordService.convertToJson(schemaAndValue.schema(), schemaAndValue.value(), false);

    ObjectNode expected = mapper.createObjectNode();
    expected.put("test", new BigDecimal("999999999999999999999999999999999999999"));
    // TODO: uncomment it once KAFKA-10457 is merged
    // assertEquals(expected.toString(), result.toString());
  }

  /**
   * StreamkapSnowflakeColumnTypeMapper hardcodes every Decimal/BYTES field to a DECIMAL(38,7)
   * column regardless of the schema's actual scale (ENG-2503). This locks in the boundary that
   * actually causes the resulting precision loss: RecordService's only Decimal guard is total
   * precision > 38 (MAX_SNOWFLAKE_NUMBER_PRECISION); it never checks scale, so a value with
   * scale > 7 passes through as a full-precision JSON number and is silently rounded by
   * Snowflake's own ingestion into the DECIMAL(38,7) column -- not by this connector.
   */
  @Test
  public void testDecimal_jsonConverter_scaleAboveSevenIsNotGuarded() {
    Schema schema = Decimal.schema(9);
    BigDecimal value = new BigDecimal("123456.123456789"); // scale 9, precision 15

    JsonNode result = RecordService.convertToJson(schema, value, false);

    assertTrue(result.isNumber());
    assertEquals(value, result.decimalValue());
  }

  /** Precision <= 38 (MAX_SNOWFLAKE_NUMBER_PRECISION): serializes as a number, unguarded. */
  @Test
  public void testDecimal_jsonConverter_precisionAtMaxSerializesAsNumber() {
    Schema schema = Decimal.schema(2);
    BigDecimal value = new BigDecimal("9".repeat(36) + ".12"); // precision 38

    JsonNode result = RecordService.convertToJson(schema, value, false);

    assertTrue(result.isNumber());
    assertEquals(value, result.decimalValue());
  }

  /** Precision > 38: RecordService's only Decimal guard kicks in, falling back to text. */
  @Test
  public void testDecimal_jsonConverter_precisionAboveMaxFallsBackToText() {
    Schema schema = Decimal.schema(2);
    BigDecimal value = new BigDecimal("9".repeat(37) + ".12"); // precision 39

    JsonNode result = RecordService.convertToJson(schema, value, false);

    assertTrue(result.isTextual());
    assertEquals(value.toString(), result.asText());
  }

  @Test
  public void testConnectSimpleHeaderConverter_MapDateAndOtherTypes() throws ParseException {
    SimpleHeaderConverter headerConverter = new SimpleHeaderConverter();
    String timestamp = "1970-03-22T00:00:00.000Z";
    String rawHeader = "{\"f1\": \"" + timestamp + "\", \"f2\": true}";
    SchemaAndValue schemaAndValue =
        headerConverter.toConnectHeader(
            "test", "h1", rawHeader.getBytes(StandardCharsets.US_ASCII));
    JsonNode result =
        RecordService.convertToJson(schemaAndValue.schema(), schemaAndValue.value(), false);

    ObjectNode expected = mapper.createObjectNode();
    String expectedTimestampValue =
        String.valueOf(
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .parse("1970-03-22T00:00:00.000Z")
                .getTime());
    expected.put("f1", expectedTimestampValue);
    expected.put("f2", true);
    assertEquals(expected.toString(), result.toString());
  }

  @Test
  public void testAvroConverterConfig() {
    SnowflakeAvroConverter converter = new SnowflakeAvroConverter();
    converter.configure(Collections.singletonMap("schema.registry.url", "http://fake-url"), false);

    Map<String, ?> config = Collections.singletonMap("schema.registry.url", "mock://my-scope-name");
    converter.readBreakOnSchemaRegistryError(config);
    assertFalse(converter.getBreakOnSchemaRegistryError());

    config =
        Collections.singletonMap(SnowflakeAvroConverter.BREAK_ON_SCHEMA_REGISTRY_ERROR, "true");
    converter.readBreakOnSchemaRegistryError(config);
    assertTrue(converter.getBreakOnSchemaRegistryError());

    config =
        Collections.singletonMap(SnowflakeAvroConverter.BREAK_ON_SCHEMA_REGISTRY_ERROR, "trueeee");
    converter.readBreakOnSchemaRegistryError(config);
    assertFalse(converter.getBreakOnSchemaRegistryError());

    config =
        Collections.singletonMap(SnowflakeAvroConverter.BREAK_ON_SCHEMA_REGISTRY_ERROR, "True");
    converter.readBreakOnSchemaRegistryError(config);
    assertTrue(converter.getBreakOnSchemaRegistryError());
  }

  @Test
  public void testAvroConverterErrorConfig() {
    SnowflakeAvroConverter converter = new SnowflakeAvroConverter();

    assertThrows(
        SnowflakeKafkaConnectorException.class,
        () -> converter.configure(new HashMap<String, String>(), true));
  }

  @Test
  public void testAvroConverterSchemaRegistryErrorFail() {
    SnowflakeAvroConverter converter = new SnowflakeAvroConverter();
    Map<String, ?> config =
        Collections.singletonMap(SnowflakeAvroConverter.BREAK_ON_SCHEMA_REGISTRY_ERROR, "true");
    converter.readBreakOnSchemaRegistryError(config);

    SchemaBuilder builder =
        SchemaBuilder.struct()
            .field("int8", SchemaBuilder.int8().defaultValue((byte) 2).doc("int8 field").build());
    Schema schema = builder.build();
    Struct original = new Struct(schema).put("int8", (byte) 12);
    SchemaRegistryClient schemaRegistry =
        new io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient();
    AvroConverter avroConverter = new AvroConverter(schemaRegistry);
    avroConverter.configure(
        Collections.singletonMap("schema.registry.url", "http://fake-url"), false);
    byte[] converted = avroConverter.fromConnectData("test", original.schema(), original);

    assertThrows(
        SnowflakeKafkaConnectorException.class, () -> converter.toConnectData("test", converted));
  }

  @Test
  public void testAvroConverterSchemaRegistryErrorContinue() {
    SnowflakeAvroConverter converter = new SnowflakeAvroConverter();

    SchemaBuilder builder =
        SchemaBuilder.struct()
            .field("int8", SchemaBuilder.int8().defaultValue((byte) 2).doc("int8 field").build());
    Schema schema = builder.build();
    Struct original = new Struct(schema).put("int8", (byte) 12);
    SchemaRegistryClient schemaRegistry =
        new io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient();
    AvroConverter avroConverter = new AvroConverter(schemaRegistry);
    avroConverter.configure(
        Collections.singletonMap("schema.registry.url", "http://fake-url"), false);
    byte[] converted = avroConverter.fromConnectData("test", original.schema(), original);
    SchemaAndValue result = converter.toConnectData("test", converted);
    SnowflakeRecordContent content = assertInstanceOf(SnowflakeRecordContent.class, result.value());
    assertTrue(content.isBroken());
  }
}
