SET lock_timeout = '5s';
SET statement_timeout = '5s';
SET SEARCH_PATH TO rapport;

ALTER TYPE rapport_type RENAME VALUE 'K27' TO 'ref-arbg';
ALTER TYPE rapport_type RENAME VALUE 'T12' TO 'trekk-hend';
ALTER TYPE rapport_type RENAME VALUE 'T14' TO 'trekk-kred';
