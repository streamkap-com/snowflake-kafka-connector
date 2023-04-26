package com.snowflake.kafka.connector.internal.streaming;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.After;
import org.junit.Test;

import com.snowflake.kafka.connector.SnowflakeSinkConnectorConfig;
import com.snowflake.kafka.connector.dlq.InMemoryKafkaRecordErrorReporter;
import com.snowflake.kafka.connector.internal.SnowflakeConnectionService;
import com.snowflake.kafka.connector.internal.SnowflakeSinkService;
import com.snowflake.kafka.connector.internal.SnowflakeSinkServiceFactory;
import com.snowflake.kafka.connector.internal.TestUtils;

import io.confluent.connect.avro.AvroConverter;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.ObjectMapper;

public class SnowflakeSinkNoAutoSchematizationIT {

  private SnowflakeConnectionService conn = TestUtils.getConnectionServiceStreamingWithEncryptedKey();
  private String table = TestUtils.randomTableName();
//   private String table = TestUtils.getDatabase() + "." + TestUtils.getschema() + "." + TestUtils.randomTableName();
  private int partition = 0;
  private int partition2 = 1;
  private String topic = "test";
  private TopicPartition topicPartition = new TopicPartition(topic, partition);
  private static ObjectMapper MAPPER = new ObjectMapper();

  @After
  public void afterEach() {
    TestUtils.dropTable(table);
  }

  @Test
  public void testColumnNameIsReservedKeyword() throws Exception {
    Map<String, String> config = TestUtils.getConfForStreamingWithEncryptedKey();
    config.put(SnowflakeSinkConnectorConfig.ENABLE_SCHEMATIZATION_CONFIG, "true");
    config.put(
            SnowflakeSinkConnectorConfig.VALUE_CONVERTER_CONFIG_FIELD,
            "io.confluent.connect.avro.AvroConverter");
    config.put(SnowflakeSinkConnectorConfig.VALUE_SCHEMA_REGISTRY_CONFIG_FIELD, "http://fake-url");
    config.put(SnowflakeSinkConnectorConfig.ENABLE_SCHEMATIZATION_CONFIG, "true");
    config.put(SnowflakeSinkConnectorConfig.SCHEMATIZATION_AUTO_CONFIG, "false");
    
    // get rid of these at the end
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    // avro
    SchemaBuilder schemaBuilder =
            SchemaBuilder.struct()
                    .field("all", Schema.STRING_SCHEMA);

    Struct original =
            new Struct(schemaBuilder.build())
                    .put("all", "some-value");

    SchemaRegistryClient schemaRegistry = new MockSchemaRegistryClient();
    AvroConverter avroConverter = new AvroConverter(schemaRegistry);
    avroConverter.configure(
            Collections.singletonMap("schema.registry.url", "http://fake-url"), false);
    byte[] converted = avroConverter.fromConnectData(topic, original.schema(), original);
    conn.createTableWithOnlyMetadataColumn(table, false);

    SchemaAndValue avroInputValue = avroConverter.toConnectData(topic, converted);

    long startOffset = 0;
    long endOffset = 0;

    SinkRecord avroRecordValue =
            new SinkRecord(
                    topic,
                    partition,
                    Schema.STRING_SCHEMA,
                    "test",
                    avroInputValue.schema(),
                    avroInputValue.value(),
                    startOffset);

    SnowflakeSinkService service =
            SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
                    .setRecordNumber(1)
                    .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
                    .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
                    .addTask(table, new TopicPartition(topic, partition))
                    .build();

    // The first insert should fail and schema evolution will kick in to update the schema
    service.insert(avroRecordValue);
    TestUtils.assertWithRetry(
            () -> service.getOffset(new TopicPartition(topic, partition)) == startOffset, 20, 5);

    Map<String, String> sfAvroSchemaForTableCreation = new HashMap<>();
    sfAvroSchemaForTableCreation.put("RECORD_METADATA", "VARIANT");
    sfAvroSchemaForTableCreation.put("all", "VARCHAR");
    TestUtils.checkTableSchema(table, sfAvroSchemaForTableCreation);

    // Retry the insert should succeed now with the updated schema
    service.insert(avroRecordValue);
    TestUtils.assertWithRetry(
            () -> service.getOffset(new TopicPartition(topic, partition)) == endOffset + 1, 20, 5);

    Map<String, Object> contentForAvroTableCreation = new HashMap<>();
    contentForAvroTableCreation.put("all", "some-value");
    TestUtils.checkTableContentOneRow(table, contentForAvroTableCreation);

    service.closeAll();
  }

}
