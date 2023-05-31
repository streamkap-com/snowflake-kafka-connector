package com.snowflake.kafka.connector.internal;

import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import org.junit.Test;

public class DynamicTablesIT {
  private SnowflakeConnectionService conn = SnowflakeConnectionServiceFactory.builder()
      .setProperties(TestUtils.getConfWithEncryptedKey())
      .build();

  @Test
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
