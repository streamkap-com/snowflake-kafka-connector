package com.snowflake.kafka.connector.internal.streaming.schemaevolution.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.apache.kafka.connect.data.Date;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Time;
import org.apache.kafka.connect.data.Timestamp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ENG-2596: covers the Streamkap override mapping Kafka Decimal to DECIMAL(38,7) for Iceberg
 * tables (parity with classic tables), and pins that every other mapping still delegates to
 * upstream. Upstream behaviour is covered by {@link IcebergColumnTypeMapperTest}.
 */
class StreamkapIcebergColumnTypeMapperTest {

  private final StreamkapIcebergColumnTypeMapper mapper = new StreamkapIcebergColumnTypeMapper();

  @Test
  void shouldMapDecimalToStreamkapDecimalInsteadOfVarchar() {
    assertThat(mapper.mapToColumnTypeFromKafkaSchema(Schema.Type.BYTES, Decimal.LOGICAL_NAME))
        .isEqualTo("DECIMAL(38,7)");
  }

  @Test
  void shouldNotAffectNonDecimalBytes() {
    // BYTES without the Decimal logical type must stay BINARY
    assertThat(mapper.mapToColumnTypeFromKafkaSchema(Schema.Type.BYTES, null)).isEqualTo("BINARY");
  }

  @Test
  void shouldNotAffectDecimalLogicalNameOnOtherKafkaTypes() {
    // the override is keyed on BYTES + Decimal, not the logical name alone
    assertThat(mapper.mapToColumnTypeFromKafkaSchema(Schema.Type.STRING, Decimal.LOGICAL_NAME))
        .isEqualTo("VARCHAR");
  }

  /**
   * Proves the override is actually wired into {@link IcebergColumnTreeFactory} — the injection
   * point schema evolution goes through — rather than merely being correct in isolation.
   */
  @Test
  void factoryShouldProduceStreamkapDecimalForAConnectDecimalField() {
    IcebergColumnTreeFactory treeFactory = new IcebergColumnTreeFactory();
    IcebergColumnTreeTypeBuilder typeBuilder = new IcebergColumnTreeTypeBuilder();

    IcebergColumnTree tree =
        treeFactory.fromConnectSchema(new Field("AMOUNT", 0, Decimal.schema(2)));

    assertThat(typeBuilder.buildType(tree)).contains("DECIMAL(38,7)");
  }

  @ParameterizedTest(name = "should delegate {0}/{1} to upstream -> {2}")
  @MethodSource("delegatedTypes")
  void shouldDelegateEverythingElseToUpstream(
      Schema.Type kafkaType, String schemaName, String expected) {
    assertThat(mapper.mapToColumnTypeFromKafkaSchema(kafkaType, schemaName)).isEqualTo(expected);
  }

  private static Stream<Arguments> delegatedTypes() {
    return Stream.of(
        Arguments.of(Schema.Type.INT8, null, "INT"),
        Arguments.of(Schema.Type.INT16, null, "INT"),
        Arguments.of(Schema.Type.INT32, Date.LOGICAL_NAME, "DATE"),
        Arguments.of(Schema.Type.INT32, Time.LOGICAL_NAME, "TIME(6)"),
        Arguments.of(Schema.Type.INT32, null, "INT"),
        Arguments.of(Schema.Type.INT64, Timestamp.LOGICAL_NAME, "TIMESTAMP(6)"),
        Arguments.of(Schema.Type.INT64, null, "LONG"),
        Arguments.of(Schema.Type.FLOAT32, null, "FLOAT"),
        Arguments.of(Schema.Type.FLOAT64, null, "DOUBLE"),
        Arguments.of(Schema.Type.BOOLEAN, null, "BOOLEAN"),
        Arguments.of(Schema.Type.STRING, null, "VARCHAR"),
        Arguments.of(Schema.Type.MAP, null, "MAP"),
        Arguments.of(Schema.Type.ARRAY, null, "ARRAY"),
        Arguments.of(Schema.Type.STRUCT, null, "OBJECT"));
  }
}
