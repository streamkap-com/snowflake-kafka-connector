CREATE OR REPLACE DYNAMIC TABLE TSTTBL2_V
  lag = '1 seconds'
  warehouse = STREAMKAP_WH
AS SELECT * EXCLUDE(dedupe_id)
        FROM (
        SELECT *,
            ROW_NUMBER() OVER 
                (PARTITION BY id 
                ORDER BY CREATED_AT DESC) AS dedupe_id
            FROM TSTTBL)
        WHERE dedupe_id = 1        -- Latest record
            AND __deleted = 'false';  -- Excluding deleted record