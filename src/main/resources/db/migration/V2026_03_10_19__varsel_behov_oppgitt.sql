SET lock_timeout = '5s';
SET statement_timeout = '5s';

SET SEARCH_PATH TO rapport;

ALTER TABLE varsel_behov
    ADD COLUMN oppgitt TIMESTAMPTZ NULL DEFAULT NULL;
