SET lock_timeout = '60s';
SET statement_timeout = '60s';
SET SEARCH_PATH TO rapport;

ALTER TABLE rapport
    ADD COLUMN belop NUMERIC(31, 2) NULL;

UPDATE rapport r
SET belop = (SELECT CASE
                        -- For 'trekk-hend' finnes det ikke noe beløp.
                        WHEN b.generer_som = 'ref-arbg' AND
                             b.dokument IS JSON
                            THEN (json(b.dokument) #>> '{header,sumBelop}')::numeric
                        WHEN b.generer_som = 'trekk-kred' AND
                             xml_is_well_formed_document(b.dokument)
                            THEN (
                                     xpath(
                                             './rundata/brukerdata/brevinfo/variablefelter/UR/sumtot/belop/text()',
                                             xmlparse(DOCUMENT b.dokument))
                                     )[1]
                                     ::text -- xml til text
                                     ::numeric / 100 -- text til nummer, og så øre til kr
                        END
             FROM rapport_bestilling b
             WHERE b.id = r.bestilling_id);
