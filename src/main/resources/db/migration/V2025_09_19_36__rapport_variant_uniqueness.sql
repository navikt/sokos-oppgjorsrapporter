SET lock_timeout = '5s';
SET SEARCH_PATH TO rapport;
ALTER TABLE rapport_variant
    ADD CONSTRAINT rapport_variant_format_unique_per_rapport_id UNIQUE (rapport_id, format);
