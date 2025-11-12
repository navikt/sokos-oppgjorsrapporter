SET lock_timeout = '5s';
SET statement_timeout = '5s';
SET SEARCH_PATH TO rapport;

ALTER TABLE rapport
    VALIDATE CONSTRAINT bestilling_id_not_null,
    VALIDATE CONSTRAINT rapport_rapport_bestilling_fkey;
