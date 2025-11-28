SET lock_timeout = '5s';
SET statement_timeout = '5s';
SET SEARCH_PATH TO rapport;

ALTER TABLE rapport_bestilling
    ADD COLUMN prosessering_feilet TIMESTAMPTZ NULL;
