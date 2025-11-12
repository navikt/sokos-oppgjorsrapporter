SET lock_timeout = '5s';
SET statement_timeout = '5s';
SET SEARCH_PATH TO rapport;

-- Vi er på en ny nok PostgreSQL (>= 12) til at den skal skjønne at det finnes
-- en eksisterende (valid) check-constraint som sikrer at kolonna kan markeres
-- NOT NULL uten at det må gjøres en table-scan (mens tabellen er låst)
ALTER TABLE rapport
    ALTER COLUMN bestilling_id SET NOT NULL;

ALTER TABLE rapport
    DROP CONSTRAINT bestilling_id_not_null;
