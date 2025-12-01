SET lock_timeout = '5s';
SET statement_timeout = '5s';
SET SEARCH_PATH TO rapport;

ALTER TABLE rapport
    ADD COLUMN bankkonto TEXT NULL;

UPDATE rapport r
SET bankkonto = (SELECT CASE
                            WHEN b.dokument IS JSON THEN json(b.dokument) #>> '{header,bankkonto}'
                            END
                 FROM rapport_bestilling b
                 WHERE b.id = r.bestilling_id);

-- Vi har ikke noe særlig med data i databasen vår ennå, så det blir fjas å
-- splitte ut en "concurrently" til en annen migreringsfil her:
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX ON rapport.rapport (bankkonto);
