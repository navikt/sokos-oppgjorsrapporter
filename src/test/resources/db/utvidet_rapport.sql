SET SEARCH_PATH TO rapport;

-- Tre bestillinger for samme orgnr+type (ref-arbg), én for en annen type
INSERT INTO rapport_bestilling (id, mottatt, mottatt_fra, dokument, generer_som, ferdig_prosessert)
VALUES
    (1, '2026-01-01T00:00:00+0100', 'MQ', 'todo', 'ref-arbg',   '2026-01-01T00:00:01+0100'),
    (2, '2026-02-01T00:00:00+0100', 'MQ', 'todo', 'ref-arbg',   '2026-02-01T00:00:01+0100'),
    (3, '2026-03-01T00:00:00+0100', 'MQ', 'todo', 'ref-arbg',   '2026-03-01T00:00:01+0100'),
    (4, '2026-01-01T00:00:00+0100', 'MQ', 'todo', 'trekk-kred', '2026-01-01T00:00:01+0100')
;

-- Rapport 1: ingen nedlastinger  → sistLastetNed = null for begge varianter
-- Rapport 2: én nedlasting (tokenx) på pdf, ingen på csv
-- Rapport 3: flere nedlastinger på pdf (velg siste), entraid-nedlasting ignoreres
-- Rapport 4: annen type (trekk-kred) for samme orgnr – skal ikke dukke opp i ref-arbg-søk
INSERT INTO rapport (id, bestilling_id, orgnr, org_navn, type, belop, dato_valutert, antall_rader)
VALUES
    (1, 1, '111222333', 'Test Org', 'ref-arbg',   101.01, '2026-01-31', 1),
    (2, 2, '111222333', 'Test Org', 'ref-arbg',   527.00, '2026-02-28', 1),
    (3, 3, '111222333', 'Test Org', 'ref-arbg',   400.00, '2026-03-31', 1),
    (4, 4, '111222333', 'Test Org', 'trekk-kred', 182.67, '2026-01-31', 1)
;

INSERT INTO rapport_variant (id, rapport_id, format, filnavn, innhold)
VALUES
    -- Rapport 1: 0 nedlastinger
    (1, 1, 'application/pdf', '111222333_ref-arbg_2026-01-31.pdf', 'PDF'),
    (2, 1, 'text/csv',        '111222333_ref-arbg_2026-01-31.csv', 'CSV'),
    -- Rapport 2: 1 nedlasting
    (3, 2, 'application/pdf', '111222333_ref-arbg_2026-02-28.pdf', 'PDF'),
    (4, 2, 'text/csv',        '111222333_ref-arbg_2026-02-28.csv', 'CSV'),
    -- Rapport 3: flere nedlastinger + entraid
    (5, 3, 'application/pdf', '111222333_ref-arbg_2026-03-31.pdf', 'PDF'),
    (6, 3, 'text/csv',        '111222333_ref-arbg_2026-03-31.csv', 'CSV'),
    -- Rapport 4: annen type
    (7, 4, 'application/pdf', '111222333_trekk-kred_2026-01-31.pdf', 'PDF'),
    (8, 4, 'text/csv',        '111222333_trekk-kred_2026-01-31.csv', 'CSV')
;

INSERT INTO rapport_audit (rapport_id, variant_id, tidspunkt, hendelse, brukernavn)
VALUES
    -- Rapport 2: én tokenx-nedlasting på pdf (variant 3), ingen på csv (variant 4)
    (2, 3, '2026-03-01T10:00:00Z', 'VARIANT_NEDLASTET', 'tokenx:sub=12345678901'),

    -- Rapport 3 pdf (variant 5): tre nedlastinger — siste skal brukes
    (3, 5, '2026-04-01T08:00:00Z', 'VARIANT_NEDLASTET', 'systembruker:system=mitt-system'),
    (3, 5, '2026-04-02T09:00:00Z', 'VARIANT_NEDLASTET', 'tokenx:sub=12345678901'),
    (3, 5, '2026-04-03T11:00:00Z', 'VARIANT_NEDLASTET', 'systembruker:system=mitt-system'),  -- nyeste
    -- Rapport 3 pdf: entraid-nedlasting skal ignoreres
    (3, 5, '2026-04-04T12:00:00Z', 'VARIANT_NEDLASTET', 'entraid:NAVident=X123456'),

    -- Rapport 3 csv (variant 6): kun entraid — skal gi sistLastetNed = null
    (3, 6, '2026-04-01T08:00:00Z', 'VARIANT_NEDLASTET', 'entraid:NAVident=X123456')
;
