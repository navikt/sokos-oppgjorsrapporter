SET lock_timeout = '30s';
SET statement_timeout = '30s';
SET SEARCH_PATH TO rapport;

ALTER TABLE rapport
    ADD COLUMN navn TEXT NULL;
