package com.snowflake.kafka.connector.internal;

import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import org.junit.Ignore;
import org.junit.Test;

public class DynamicTablesIT {
  private SnowflakeConnectionService conn = SnowflakeConnectionServiceFactory.builder()
      .setProperties(TestUtils.getConfWithEncryptedKey())
      .build();

  @Test
  @Ignore("draft uses manually created table")
  public void testGetDynamicTableMetadata() throws Exception {
    DatabaseMetaData dm = conn.getDatabaseMetadata();

    System.out.println("## tsttbl columns ###########################");
    PreparedStatement stm = dm.getConnection().prepareStatement("DESCRIBE TABLE tsttbl");
    printResultSet(stm.executeQuery());

    System.out.println("## tsttbl_v columns ###########################");
    stm = dm.getConnection().prepareStatement("DESCRIBE TABLE tsttbl_v");
    printResultSet(stm.executeQuery());
    
    printResultSet(dm.getCatalogs());
    printResultSet(dm.getSchemas());
    printResultSet(dm.getColumns("%", "%", "%", null));
  }

  @Test
  // @Ignore("draft uses manually created table")
  public void testCreateDynamicTable() throws Exception {
    DatabaseMetaData dm = conn.getDatabaseMetadata();

    String stmStr = ""
      + " CREATE OR REPLACE DYNAMIC TABLE TSTTBL_junit_V"
      + "   lag = '1 seconds'"
      + "   warehouse = STREAMKAP_WH"
      + " AS SELECT * EXCLUDE(dedupe_id)"
      + "         FROM ("
      + "         SELECT *,"
      + "             ROW_NUMBER() OVER "
      + "                 (PARTITION BY id "
      + "                 ORDER BY CREATED_AT DESC) AS dedupe_id"
      + "             FROM TSTTBL)"
      + "         WHERE dedupe_id = 1        -- Latest record"
      + "             AND __deleted = 'false';  -- Excluding deleted record"
    ;
    System.out.println("## creating TSTTBL_junit_V ###########################");
    PreparedStatement stm = dm.getConnection().prepareStatement(stmStr);
    stm.execute();

    System.out.println("## TSTTBL_junit_V columns ###########################");
    stm = dm.getConnection().prepareStatement("DESCRIBE TABLE TSTTBL_junit_V");
    printResultSet(stm.executeQuery());
  }

  private void printResultSet(ResultSet rs) throws SQLException {
    ResultSetMetaData rsmd = rs.getMetaData();
    int columnsNumber = rsmd.getColumnCount();
    while (rs.next()) {
      for (int i = 1; i <= columnsNumber; i++) {
        if (i > 1)
          System.out.print(",  ");
        String columnValue = rs.getString(i);
        System.out.print(columnValue + " " + rsmd.getColumnName(i));
      }
      System.out.println("");
    }
  }
}
