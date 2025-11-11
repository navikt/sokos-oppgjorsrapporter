SET SEARCH_PATH TO rapport;

INSERT INTO rapport_bestilling (id, mottatt, mottatt_fra, dokument, generer_som, ferdig_prosessert)
VALUES
    (1, '2024-11-01T11:57:20+0100', 'MQ', 'todo', 'K27', NULL),
    (2, '2024-11-01T11:57:20+0100', 'MQ', 'todo', 'T14', NULL),
    (3, '2024-11-01T11:57:20+0100', 'MQ', 'todo', 'K27', NULL),
    (4, '2024-11-01T11:57:20+0100', 'MQ', 'todo', 'K27', NULL)
;


INSERT INTO rapport (id, bestilling_id, orgnr, type, tittel, dato_valutert, opprettet, arkivert)
VALUES
    (1, 1, '123456789', 'K27', 'K27 for Skinnende Padde 2024-11-01', '2024-11-01', '2024-11-01T11:57:23+0100', null),
    (2, 2, '123456789', 'T14', 'T14 for Skinnende Padde 2024-11-01', '2024-11-01', '2024-11-01T11:57:24+0100', null),
    (3, 3, '234567890', 'K27', 'K27 for Humrende Elg 2024-11-01', '2024-11-01', '2024-11-01T11:57:25+0100', null),
    (4, 4, '345678901', 'K27', 'K27 for Lummer Hummer 2024-11-01', '2024-11-01', '2024-11-01T11:57:26+0100', null)
;

INSERT INTO rapport_variant (id, rapport_id, format, filnavn, innhold)
VALUES
    (1, 1, 'application/pdf', '123456789_K27_2024-11-01.pdf', 'PDF\0001'),
    (2, 1, 'text/csv', '123456789_K27_2024-11-01.csv', 'CSV\0001'),
    (3, 2, 'application/pdf', '123456789_T14_2024-11-01.pdf', 'PDF\0002'),
    (4, 2, 'text/csv', '123456789_T14_2024-11-01.csv', 'CSV\0002'),
    (5, 3, 'application/pdf', '234567890_K27_2024-11-01.pdf', 'PDF\0003'),
    (6, 3, 'text/csv', '234567890_K27_2024-11-01.csv', 'CSV\0003'),
    (7, 4, 'application/pdf', '345678901_K27_2024-11-01.pdf', 'PDF\0004'),
    (8, 4, 'text/csv', '345678901_K27_2024-11-01.csv', 'CSV\0004')
;
