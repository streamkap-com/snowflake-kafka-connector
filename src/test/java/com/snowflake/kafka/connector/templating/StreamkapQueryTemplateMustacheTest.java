package com.snowflake.kafka.connector.templating;

import com.github.mustachejava.Mustache;
import com.snowflake.kafka.connector.Utils;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the Mustache HTML-escaping issue where {{quotedTable}} containing
 * double-quoted identifiers (e.g. "GROUP_AREA") gets escaped to &quot;GROUP_AREA&quot;,
 * producing invalid SQL with unexpected '&' characters.
 */
public class StreamkapQueryTemplateMustacheTest {

    static final String CREATE_SQL_TEMPLATE =
            "CREATE OR REPLACE DYNAMIC TABLE {{table}}_DT TARGET_LAG='{{targetLag}} minutes' " +
                    "WAREHOUSE={{warehouse}} AS SELECT * FROM( SELECT *, ROW_NUMBER() OVER " +
                    "(PARTITION BY {{primaryKeyColumns}} ORDER BY _streamkap_ts_ms DESC, _streamkap_offset DESC) " +
                    "AS dedupe_id FROM {{quotedTable}} ) WHERE dedupe_id = 1 AND __deleted = 'false';" +
                    "CREATE OR REPLACE TASK {{table}}_CT WAREHOUSE={{warehouse}} SCHEDULE='{{schedule}} minutes' " +
                    "TASK_AUTO_RETRY_ATTEMPTS=3 ALLOW_OVERLAPPING_EXECUTION=FALSE AS DELETE FROM {{quotedTable}} " +
                    "WHERE NOT EXISTS ( SELECT 1 FROM ( SELECT {{primaryKeyColumns}}, MAX(_streamkap_ts_ms) AS max_timestamp " +
                    "FROM {{quotedTable}} GROUP BY {{primaryKeyColumns}} ) AS subquery " +
                    "WHERE {{keyColumnsAndCondition}} AND {{quotedTable}}._streamkap_ts_ms = subquery.max_timestamp)";

    @Test
    void quotedTableName_shouldNotBeHtmlEscaped() {
        String tableName = "group_area";
        String quotedTableName = Utils.quoteNameIfNeeded(tableName);
        // quoteNameIfNeeded wraps in double quotes: "GROUP_AREA"
        assertEquals("\"GROUP_AREA\"", quotedTableName);

        List<String> keyCols = Arrays.asList("id");

        Map<String, Object> values = new HashMap<>();
        values.put("warehouse", "MY_WH");
        values.put("targetLag", 15);
        values.put("schedule", 60);
        values.put("table", tableName.toUpperCase());
        values.put("quotedTable", quotedTableName);
        values.put("primaryKeyColumns", String.join(",", keyCols));
        values.put("keyColumnsAndCondition", String.join("AND", keyCols.stream()
                .map(v -> quotedTableName + "." + v + " = subquery." + v)
                .collect(Collectors.toList())));

        Mustache template = StreamkapQueryTemplate.mustacheFactory.compile(new StringReader(CREATE_SQL_TEMPLATE), "test-template");
        StringWriter writer = new StringWriter();
        template.execute(writer, values);
        String renderedSql = writer.toString();

        // The rendered SQL must not contain HTML entities
        assertFalse(renderedSql.contains("&quot;"),
                "SQL contains HTML-escaped quotes (&quot;). Rendered SQL:\n" + renderedSql);
        assertFalse(renderedSql.contains("&amp;"),
                "SQL contains HTML-escaped ampersand (&amp;). Rendered SQL:\n" + renderedSql);
        assertFalse(renderedSql.contains("&"),
                "SQL contains '&' character from HTML escaping. Rendered SQL:\n" + renderedSql);

        // The rendered SQL must contain the properly quoted table name
        assertTrue(renderedSql.contains("FROM \"GROUP_AREA\" )"),
                "SQL should contain FROM \"GROUP_AREA\". Rendered SQL:\n" + renderedSql);
        assertTrue(renderedSql.contains("DELETE FROM \"GROUP_AREA\""),
                "SQL should contain DELETE FROM \"GROUP_AREA\". Rendered SQL:\n" + renderedSql);
    }

    @Test
    void quotedTableName_withSchemaPrefix_shouldNotBeHtmlEscaped() {
        String tableName = "my_schema.group_area";
        String[] parts = tableName.split("\\.");
        String quotedTableName = Utils.quoteNameIfNeeded(parts[0]) + "." + Utils.quoteNameIfNeeded(parts[1]);
        // "MY_SCHEMA"."GROUP_AREA"
        assertEquals("\"MY_SCHEMA\".\"GROUP_AREA\"", quotedTableName);

        List<String> keyCols = Arrays.asList("id", "region_id");

        Map<String, Object> values = new HashMap<>();
        values.put("warehouse", "MY_WH");
        values.put("targetLag", 15);
        values.put("schedule", 60);
        values.put("table", tableName.toUpperCase());
        values.put("quotedTable", quotedTableName);
        values.put("primaryKeyColumns", String.join(",", keyCols));
        values.put("keyColumnsAndCondition", String.join("AND", keyCols.stream()
                .map(v -> quotedTableName + "." + v + " = subquery." + v)
                .collect(Collectors.toList())));

        Mustache template = StreamkapQueryTemplate.mustacheFactory.compile(new StringReader(CREATE_SQL_TEMPLATE), "test-schema-template");
        StringWriter writer = new StringWriter();
        template.execute(writer, values);
        String renderedSql = writer.toString();

        assertFalse(renderedSql.contains("&"),
                "SQL contains '&' from HTML escaping. Rendered SQL:\n" + renderedSql);
        assertTrue(renderedSql.contains("FROM \"MY_SCHEMA\".\"GROUP_AREA\" )"),
                "SQL should contain properly quoted schema.table. Rendered SQL:\n" + renderedSql);
    }

    @Test
    void keyColumnsAndCondition_withMultipleKeys_shouldHaveSpacesAroundAND() throws Exception {
        // Build a SinkRecord with a composite key schema (id, region_id)
        org.apache.kafka.connect.data.Schema keySchema = org.apache.kafka.connect.data.SchemaBuilder.struct()
                .field("id", org.apache.kafka.connect.data.Schema.INT32_SCHEMA)
                .field("region_id", org.apache.kafka.connect.data.Schema.INT32_SCHEMA)
                .build();
        org.apache.kafka.connect.data.Struct keyValue = new org.apache.kafka.connect.data.Struct(keySchema)
                .put("id", 1)
                .put("region_id", 42);

        org.apache.kafka.connect.data.Schema valueSchema = org.apache.kafka.connect.data.SchemaBuilder.struct()
                .field("id", org.apache.kafka.connect.data.Schema.INT32_SCHEMA)
                .field("region_id", org.apache.kafka.connect.data.Schema.INT32_SCHEMA)
                .field("name", org.apache.kafka.connect.data.Schema.STRING_SCHEMA)
                .build();
        org.apache.kafka.connect.data.Struct value = new org.apache.kafka.connect.data.Struct(valueSchema)
                .put("id", 1)
                .put("region_id", 42)
                .put("name", "test");

        org.apache.kafka.connect.sink.SinkRecord record = new org.apache.kafka.connect.sink.SinkRecord(
                "test-topic", 0, keySchema, keyValue, valueSchema, value, 0);

        // Invoke production code: StreamkapQueryTemplate.getRecordDataAsMap via reflection
        StreamkapQueryTemplate sqt = new StreamkapQueryTemplate();
        sqt.setSFWarehouse("MY_WH");
        sqt.setTargetLag(15);
        sqt.setCleanupTaskSchedule(60);

        java.lang.reflect.Method getRecordDataAsMap = StreamkapQueryTemplate.class.getDeclaredMethod(
                "getRecordDataAsMap", String.class, org.apache.kafka.connect.sink.SinkRecord.class, Map.class);
        getRecordDataAsMap.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) getRecordDataAsMap.invoke(
                sqt, "GROUP_AREA", record, new HashMap<>());

        String keyColumnsAndCondition = (String) values.get("keyColumnsAndCondition");

        // Should contain " AND " with spaces, not "idAND"
        assertFalse(keyColumnsAndCondition.contains("idAND"),
                "keyColumnsAndCondition has missing spaces around AND: " + keyColumnsAndCondition);
        assertTrue(keyColumnsAndCondition.contains(" AND "),
                "keyColumnsAndCondition should use ' AND ' with spaces: " + keyColumnsAndCondition);
    }
}