SET SEARCH_PATH TO rapport;

INSERT INTO rapport_bestilling (id, mottatt, mottatt_fra, dokument, generer_som, ferdig_prosessert, prosessering_feilet)
VALUES
    (1, '2023-01-01T00:45:14+0100', 'MQ', 'todo', 'ref-arbg', '2023-01-01T00:45:16+0100', NULL),
    (2, '2023-01-01T09:37:51+0100', 'MQ', 'todo', 'trekk-kred', '2023-01-01T09:37:53+0100', NULL),
    (3, '2023-11-01T11:57:20+0100', 'MQ', 'todo', 'ref-arbg', '2023-11-01T11:57:22+0100', NULL),
    (4, '2023-11-01T11:57:20+0100', 'MQ', 'todo', 'ref-arbg', '2023-11-01T11:57:22+0100', NULL),
    (5, '2023-12-31T23:58:26+0100', 'MQ', 'todo', 'ref-arbg', '2023-12-31T23:58:28+0100', NULL),
    (6, '2024-01-01T00:13:53+0100', 'MQ', 'todo', 'ref-arbg', '2024-01-01T00:13:55+0100', NULL)
;


INSERT INTO rapport (id, bestilling_id, orgnr, type, dato_valutert, bankkonto, navn, antall_rader, antall_underenheter, antall_personer, opprettet, arkivert)
VALUES
    (1, 1, '123456789', 'ref-arbg', '2023-01-01', '12345678901','Test Organisasjon A', 1, 1, 1, '2023-01-01T00:45:15+0100', null),
    (2, 2, '123456789', 'trekk-kred', '2023-01-01', '12345678901','Test Organisasjon A', 1, 1, 1, '2023-01-01T09:37:52+0100', null),
    (3, 3, '234567890', 'ref-arbg', '2023-11-01', '23456789012','Test Organisasjon B', 1, 1, 1, '2023-11-01T11:57:21+0100', null),
    (4, 4, '345678901', 'ref-arbg', '2023-11-01', '34567890123', 'Test Organisasjon C', 1, 1, 1, '2023-11-01T11:57:21+0100', '2023-11-15T08:14:41+0100'),
    (5, 5, '456789012', 'ref-arbg', '2023-12-31', '45678901234', 'Test Organisasjon D', 1, 1, 1, '2023-12-31T23:58:27+0100', null),
    (6, 6, '456789012', 'ref-arbg', '2024-01-01', '45678901234','Test Organisasjon D', 1, 1, 1, '2024-01-01T00:13:54+0100', null)
;

INSERT INTO rapport_variant (id, rapport_id, format, filnavn, innhold)
VALUES
    (1, 1, 'application/pdf', '123456789_ref-arbg_2023-01-01.pdf', 'PDF\0001'),
    (2, 1, 'text/csv', '123456789_ref-arbg_2023-01-01.csv', 'CSV\0001'),
    (3, 2, 'application/pdf', '123456789_trekk-kred_2023-01-01.pdf', 'PDF\0002'),
    (4, 2, 'text/csv', '123456789_trekk-kred_2023-01-01.csv', 'CSV\0002'),
    (5, 3, 'application/pdf', '234567890_ref-arbg_2023-11-01.pdf', 'PDF\0003'),
    (6, 3, 'text/csv', '234567890_ref-arbg_2023-11-01.csv', 'CSV\0003'),
    (7, 4, 'application/pdf', '345678901_ref-arbg_2023-11-01.pdf', 'PDF\0004'),
    (8, 4, 'text/csv', '345678901_ref-arbg_2023-11-01.csv', 'CSV\0004'),
    (9, 5, 'application/pdf', '456789012_ref-arbg_2023-12-31.pdf', 'PDF\0005'),
    (10, 5, 'text/csv', '456789012_ref-arbg_2023-12-31.csv', 'CSV\0005'),
    (11, 6, 'application/pdf', '456789012_ref-arbg_2024-11-01.pdf', 'PDF\0006'),
    (12, 6, 'text/csv', '456789012_ref-arbg_2024-11-01.csv', 'CSV\0006')
;
