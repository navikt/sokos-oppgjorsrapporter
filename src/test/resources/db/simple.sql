SET SEARCH_PATH TO rapport;

INSERT INTO rapport_bestilling (id, mottatt, mottatt_fra, dokument, generer_som, ferdig_prosessert, prosessering_feilet)
VALUES (1, '2024-11-01T13:15:01+0100', 'MQ', 'todo', 'K27', '2024-11-01T13:15:02+0100', NULL)
;

INSERT INTO rapport (id, bestilling_id, orgnr, type, tittel, dato_valutert, opprettet, arkivert)
VALUES (1, 1, '123456789', 'K27', 'K27 for Snikende Maur 2024-11-01', '2024-11-01', '2024-11-01T13:15:02+0100', null)
;

INSERT INTO rapport_variant (id, rapport_id, format, filnavn, innhold)
VALUES (1, 1, 'application/pdf', '123456789_K27_2024-11-01.pdf', 'PDF'),
       (2, 1, 'text/csv', '123456789_K27_2024-11-01.csv', 'CSV')
;
