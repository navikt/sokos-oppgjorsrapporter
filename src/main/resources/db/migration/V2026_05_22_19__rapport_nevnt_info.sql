SET lock_timeout = '5s';
SET statement_timeout = '5s';

SET SEARCH_PATH TO rapport;

ALTER TABLE rapport
    ADD COLUMN nevnt_info JSONB NULL DEFAULT NULL;

-- squawk-ignore require-concurrent-index-creation
CREATE INDEX ON rapport USING gin (nevnt_info);
