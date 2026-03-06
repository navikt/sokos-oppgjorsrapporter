SET lock_timeout = '30s';
SET statement_timeout = '30s';
SET SEARCH_PATH TO rapport;

-- Unngå full tablescan for "finn en bestilling som trenger å bli prosessert"

-- Squawk anbefaler CREATE INDEX med CONCURRENTLY for å unngå at
-- database-brukere låses ute mens indexen bygges - men det funker ikke med
-- nåværende Flyway: https://github.com/flyway/flyway/issues/3961
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX ON rapport_audit (hendelse);
