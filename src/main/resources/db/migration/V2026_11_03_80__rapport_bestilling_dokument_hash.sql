SET lock_timeout = '60s';
SET statement_timeout = '60s';
SET SEARCH_PATH TO rapport;

ALTER TABLE rapport_bestilling
    ADD COLUMN dokument_hash BYTEA NULL,
    ALTER COLUMN dokument_hash SET DEFAULT 'conflict-inducing default value';

-- Fyll inn dokument_hash for gamle rader.
-- For rader som har duplikater legges det kun inn hash på den eldste raden, slik at en unique-index kan opprettes (se neste migreringsfil).
-- Dette betyr at man lett kan se hvilke rader som er duplikater med "WHERE dokument_hash IS NULL".
WITH hashes AS (SELECT MIN(id)                 AS id,
                       generer_som,
                       sha256(dokument::bytea) AS hash
                FROM rapport_bestilling
                GROUP BY generer_som, sha256(dokument::bytea))
UPDATE rapport_bestilling b
SET dokument_hash = h.hash
FROM hashes h
WHERE b.id = h.id;
