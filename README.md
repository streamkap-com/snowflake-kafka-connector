# Snowflake-kafka-connector
[![License](http://img.shields.io/:license-Apache%202-brightgreen.svg)](http://www.apache.org/licenses/LICENSE-2.0.txt)

Snowflake-kafka-connector is a plugin of Apache Kafka Connect - ingests data from a Kafka Topic to a Snowflake Table.

### Contributing to the Snowflake Kafka Connector
The following requirements must be met before you can merge your PR:
- Tests: all test suites must pass, see the [test README](https://github.com/snowflakedb/snowflake-kafka-connector/blob/master/README-TEST.md)
- Formatter: run this script [`./format.sh`](https://github.com/snowflakedb/snowflake-kafka-connector/blob/master/format.sh) from root
- CLA: all contributers must sign the Snowflake CLA. This is a one time signature, please provide your email so we can work with you to get this signed after you open a PR.

Thank you for contributing! We will review and approve PRs as soon as we can.

### Test and Code Coverage Statuses

[![Kafka Connector Integration Test](https://github.com/snowflakedb/snowflake-kafka-connector/actions/workflows/IntegrationTest.yml/badge.svg?branch=master)](https://github.com/snowflakedb/snowflake-kafka-connector/actions/workflows/IntegrationTest.yml)

[![Kafka Connector Apache End2End Test](https://github.com/snowflakedb/snowflake-kafka-connector/actions/workflows/End2EndTestApache.yml/badge.svg?branch=master)](https://github.com/snowflakedb/snowflake-kafka-connector/actions/workflows/End2EndTestApache.yml)

[![Kafka Connector Confluent End2End Test](https://github.com/snowflakedb/snowflake-kafka-connector/actions/workflows/End2EndTestConfluent.yml/badge.svg?branch=master)](https://github.com/snowflakedb/snowflake-kafka-connector/actions/workflows/End2EndTestConfluent.yml)

[![codecov](https://codecov.io/gh/snowflakedb/snowflake-kafka-connector/branch/master/graph/badge.svg)](https://codecov.io/gh/snowflakedb/snowflake-kafka-connector)

## Streamkap Developer Notes

### Toolchains Plugin

[Toolchains](https://maven.apache.org/guides/mini/guide-using-toolchains.html) provides a way to discover JDKs (and other tools) to be used during the build of the Snowflake Kafka Connector, without the need to configure them in each plugin or `pom` file. 

Using Toolchains, a project can now be built using a specific version of the JDK that's independent from the one Maven is running with or the default JDK (usually based on the `JAVA_HOME` environment variable) configured in your local environment. Think of it like configuring your IDEs like Eclipse, IDEA and NetBeans to compile or build your projects using a particular JDK version.

Before you build the Snowflake Kafka Connector, create a `toolchains.xml` file in `${user.home}/.m2/` (*nix OS) or `%UserProfile%/.m2/` (Windows OS).

Here's an example for you to use:

```
<?xml version="1.0" encoding="UTF-8"?>
<toolchains>
  <!-- JDK toolchains -->
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>11</version>
    </provides>
    <configuration>
      <jdkHome>C:\Program Files\Eclipse Adoptium\jdk-11.0.19.7-hotspot</jdkHome>
    </configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>17</version>
    </provides>
    <configuration>
      <jdkHome>C:\Program Files\Eclipse Adoptium\jdk-17.0.7.7-hotspot</jdkHome>
    </configuration>
  </toolchain>
</toolchains>
```

As you can see, if you have multiple JDK versions installed, you can add multiple `<toolchain>...</toolchain>` entries for them. What version you then compile or build projects with depends on which of the `toolchains.xml` entries your toolchain plugin `<jdk>...</jdk>` configuration points to.