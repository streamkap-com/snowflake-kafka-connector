package com.snowflake.kafka.connector;

import com.snowflake.kafka.connector.config.ConnectorConfigDefinition;
import org.apache.kafka.common.config.ConfigDef;

public class StreamkapSnowflakeAdaptor {
    public static ConfigDef newConfigDef() {
        return ConnectorConfigDefinition.getConfig();
    }
}
