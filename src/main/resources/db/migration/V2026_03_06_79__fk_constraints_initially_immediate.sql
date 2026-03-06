SET lock_timeout = '5s';
SET statement_timeout = '5s';

SET SEARCH_PATH TO rapport;

ALTER TABLE rapport_variant
    ALTER CONSTRAINT rapport_variant_rapport_id_fkey DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE rapport_audit
    ALTER CONSTRAINT rapport_audit_rapport_id_fkey DEFERRABLE INITIALLY IMMEDIATE,
    ALTER CONSTRAINT rapport_audit_variant_id_fkey DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE varsel_behov
    ALTER CONSTRAINT varsel_behov_rapport_id_fkey DEFERRABLE INITIALLY IMMEDIATE;
