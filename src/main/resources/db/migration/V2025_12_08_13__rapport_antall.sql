SET lock_timeout = '30s';
SET statement_timeout = '30s';
SET SEARCH_PATH TO rapport;

-- Antall rader/underenheter/personer i en enkelt rapport vil aldri være i
-- nærheten av å treffe på Int.MAX; squawk kan ta seg en bolle.
-- squawk-ignore-file prefer-bigint-over-int
ALTER TABLE rapport
    ADD COLUMN antall_rader        INTEGER NULL,
    ADD COLUMN antall_underenheter INTEGER NULL,
    ADD COLUMN antall_personer     INTEGER NULL;

WITH as_jsonb AS (SELECT id,
                         json(dokument)::jsonb AS jdok
                  FROM rapport_bestilling
                  WHERE dokument IS JSON),
     extracted AS (SELECT id,
                          jsonb_path_query(jdok, '$.datarec[*].bedriftsnummer') AS orgnr,
                          jsonb_path_query(jdok, '$.datarec[*].fnr')            AS fnr
                   FROM as_jsonb),
     counts AS (SELECT id,
                       COUNT(*)              AS rader,
                       COUNT(DISTINCT orgnr) AS underenheter,
                       COUNT(DISTINCT fnr)   AS personer
                FROM extracted
                GROUP BY id)
UPDATE rapport r
SET antall_rader        = c.rader,
    antall_underenheter = c.underenheter,
    antall_personer     = c.personer
FROM counts c
WHERE r.bestilling_id = c.id;

ALTER TABLE rapport
    -- Vi har ikke noe særlig med data i databasen vår ennå, så det er ikke noe
    -- problem at reads blir blokkert mens tabellen blir scannet.
    -- squawk-ignore adding-not-nullable-field
    ALTER COLUMN antall_rader SET NOT NULL;
