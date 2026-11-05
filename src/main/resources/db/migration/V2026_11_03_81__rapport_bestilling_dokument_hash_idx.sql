SET lock_timeout = '60s';
SET statement_timeout = '60s';
SET SEARCH_PATH TO rapport;

-- Bruk (generer_som +) dokument_hash til å sikre at vi avviser duplikater av allerede prosesserte bestillinger.
CREATE UNIQUE INDEX CONCURRENTLY
    IF NOT EXISTS rapport_bestilling_dokument_hash_uniq_per_type_idx
    ON rapport_bestilling (generer_som, dokument_hash);
