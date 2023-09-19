package com.snowflake.kafka.connector;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.transforms.ReplaceField;
import org.junit.jupiter.api.Test;

import com.snowflake.kafka.connector.internal.TestUtils;
import com.streamkap.common.test.sink.StreamkapSinkITBase;

public class StreamkapITSnowflake extends StreamkapSinkITBase<SnowflakeSinkTask> {
    private static final String SCHEMA_NAME = "junit";

    ReplaceField<SinkRecord> renameAmbigiousFields = new ReplaceField.Value<>();

    public StreamkapITSnowflake() throws Exception {
        Map<String, String> config = new HashMap<>();

        config.put("renames",
                "account:_account,all:_all,alter:_alter,and:_and,any:_any,as:_as,between:_between,by:_by,case:_case,cast:_cast,check:_check,column:_column,connect:_connect,connection:_connection,constraint:_constraint,create:_create,cross:_cross,current:_current,current_date:_current_date,current_time:_current_time,current_timestamp:_current_timestamp,current_user:_current_user,database:_database,delete:_delete,distinct:_distinct,drop:_drop,else:_else,exists:_exists,false:_false,following:_following,for:_for,from:_from,full:_full,grant:_grant,group:_group,gscluster:_gscluster,having:_having,ilike:_ilike,in:_in,increment:_increment,inner:_inner,insert:_insert,intersect:_intersect,into:_into,is:_is,issue:_issue,join:_join,lateral:_lateral,left:_left,like:_like,localtime:_localtime,localtimestamp:_localtimestamp,minus:_minus,natural:_natural,not:_not,null:_null,of:_of,on:_on,or:_or,order:_order,organization:_organization,qualify:_qualify,regexp:_regexp,revoke:_revoke,right:_right,rlike:_rlike,row:_row,rows:_rows,sample:_sample,schema:_schema,select:_select,set:_set,some:_some,start:_start,table:_table,tablesample:_tablesample,then:_then,to:_to,trigger:_trigger,true:_true,try_cast:_try_cast,union:_union,unique:_unique,update:_update,using:_using,values:_values,view:_view,when:_when,whenever:_whenever,where:_where,with:_with");
        renameAmbigiousFields.configure(config);

        super.init(TestUtils.generateConnectionToSnowflakeWithEncryptedKey());
    }

    public Map<String, String> getConf() {
        Map<String, String> confFromJson = TestUtils.getConfWithEncryptedKey();
        Map<String, String> config = new HashMap<>();
        config.put("behavior.on.null.values", "IGNORE");
        config.put("connector.class", "com.snowflake.kafka.connector.SnowflakeSinkConnector");
        config.put("name", "destination_65019e4b368473fede109f76");
        config.put("snowflake.database.name", confFromJson.get(Utils.SF_DATABASE));
        config.put("snowflake.enable.schematization", "true");
        config.put("snowflake.ingestion.method", "SNOWPIPE_STREAMING");
        config.put("snowflake.private.key", confFromJson.get(Utils.SF_PRIVATE_KEY));
        config.put("snowflake.private.key.passphrase", confFromJson.get(Utils.PRIVATE_KEY_PASSPHRASE));
        config.put("snowflake.role.name", confFromJson.get(Utils.SF_ROLE));
        config.put("snowflake.schema.name", confFromJson.get(Utils.SF_SCHEMA));
        config.put("snowflake.schematization.auto", "false");
        config.put("snowflake.topic2table.map",
                "REGEX_MATCHER>^([-\\w]+\\.)([-\\w]+\\.)?([-\\w]+\\.)?([-\\w]+\\.)?([-\\w]+),$5");
        config.put("snowflake.url.name", confFromJson.get(Utils.SF_URL));
        config.put("snowflake.user.name", confFromJson.get(Utils.SF_USER));
        config.put("buffer.count.records", "0");
        config.put("buffer.flush.time", "-1");

        SnowflakeSinkConnectorConfig.setDefaultValues(config);

        return config;
    }

    @Override
    protected void taskPut(SnowflakeSinkTask task, List<SinkRecord> records) {
        try {
            task.put(records);
            Thread.sleep(11000);
            task.put(records); // flush snowflake channel
            Thread.sleep(11000);
            task.put(records); // flush snowflake channel
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    protected boolean checkTableMetadata() {
        return false;
    }


    @Test
    public void testNominal() throws SQLException, InterruptedException {
        super.testNominal();
    }

    // @Test
    // public void testMultiFieldKey() throws SQLException, InterruptedException {
    //     super.testMultiFieldKey();
    // }

    // @Test
    // public void testNullKeyUpsert() throws SQLException, InterruptedException {
    //     super.testNullKeyUpsert();
    // }

    // @Test
    // public void testNullKeyAppend() throws SQLException, InterruptedException {
    //     super.testNullKeyAppend();
    // }

    // @Test
    // public void testHardDelete() throws SQLException, InterruptedException {
    //     Map<String, String> config = getConf();
    //     config.put("databricks.ingestion.mode", "upsert");
    //     config.put("databricks.hard.delete", "true");
    //     super.testHardDelete(config);
    // }

    @Override
    protected SnowflakeSinkTask createSinkTask() {
        return new SnowflakeSinkTask();
    }

    @Override
    protected String getSchemaName() {
        return SCHEMA_NAME;
    }

    @Override
    protected void configureIngestionMode(Map<String, String> config, boolean isUpsert) {
        // config.put("databricks.ingestion.mode", isUpsert ? "upsert" : "append");
    }

    @Override
    protected SinkRecord applyTransforms(SinkRecord record) {
        return renameAmbigiousFields.apply(record);
    }
}
