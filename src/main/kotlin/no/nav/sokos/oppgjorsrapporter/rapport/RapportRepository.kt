package no.nav.sokos.oppgjorsrapporter.rapport

import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import java.time.Clock
import java.time.Instant
import kotlinx.io.bytestring.ByteString
import kotliquery.TransactionalSession
import kotliquery.queryOf
import no.nav.sokos.oppgjorsrapporter.auth.EntraId

class RapportRepository(private val clock: Clock) {
    fun lagreBestilling(tx: TransactionalSession, bestilling: UlagretRapportBestilling): RapportBestilling =
        queryOf(
                """
                INSERT INTO rapport.rapport_bestilling(mottatt, mottatt_fra, dokument, generer_som)
                VALUES (:mottatt, :mottatt_fra, :dokument, CAST(:generer_som AS rapport.rapport_type))
                RETURNING *
                """
                    .trimIndent(),
                mapOf(
                    "mottatt" to bestilling.mottatt,
                    "mottatt_fra" to bestilling.mottattFra,
                    "dokument" to bestilling.dokument,
                    "generer_som" to bestilling.genererSom.name,
                ),
            )
            .map { row -> RapportBestilling(row) }
            .asSingle
            .let { tx.run(it)!! }

    fun finnBestilling(tx: TransactionalSession, id: RapportBestilling.Id): RapportBestilling? =
        queryOf("SELECT * FROM rapport.rapport_bestilling WHERE id = :id", mapOf("id" to id.raw))
            .map { RapportBestilling(it) }
            .asSingle
            .let { tx.run(it) }

    fun finnUprosessertBestilling(tx: TransactionalSession): RapportBestilling? =
        queryOf(
                """
                SELECT *
                FROM rapport.rapport_bestilling
                WHERE ferdig_prosessert IS NULL
                  AND ( prosessering_feilet IS NULL OR
                        prosessering_feilet + '10 minutes'::interval <= now() )
                ORDER BY prosessering_feilet NULLS FIRST, id
                LIMIT 1
                FOR NO KEY UPDATE SKIP LOCKED
                """
                    .trimIndent()
            )
            .map { RapportBestilling(it) }
            .asSingle
            .let { tx.run(it) }

    fun markerBestillingProsesseringFeilet(tx: TransactionalSession, id: RapportBestilling.Id) {
        queryOf(
                """
                UPDATE rapport.rapport_bestilling
                SET prosessering_feilet = now()
                WHERE id = :id AND ferdig_prosessert IS NULL
                """
                    .trimIndent(),
                mapOf("id" to id.raw),
            )
            .asUpdate
            .let { tx.run(it) }
    }

    fun markerBestillingProsessert(tx: TransactionalSession, id: RapportBestilling.Id) {
        queryOf(
                """
                UPDATE rapport.rapport_bestilling
                SET ferdig_prosessert = now()
                WHERE id = :id AND ferdig_prosessert IS NULL
                """
                    .trimIndent(),
                mapOf("id" to id.raw),
            )
            .asUpdate
            .let { tx.run(it) }
    }

    fun lagreRapport(tx: TransactionalSession, rapport: UlagretRapport): Rapport =
        queryOf(
                """
                INSERT INTO rapport.rapport(bestilling_id, orgnr, org_navn, type, dato_valutert, bankkonto, antall_rader, antall_underenheter, antall_personer)
                VALUES (:bestilling_id, :orgnr, :org_navn, CAST(:type AS rapport.rapport_type), :dato_valutert, :bankkonto, :antall_rader, :antall_underenheter, :antall_personer)
                RETURNING *
                """
                    .trimIndent(),
                mapOf(
                    "bestilling_id" to rapport.bestillingId.raw,
                    "orgnr" to rapport.orgnr.raw,
                    "type" to rapport.type.name,
                    "dato_valutert" to rapport.datoValutert,
                    "bankkonto" to rapport.bankkonto?.raw,
                    "antall_rader" to rapport.antallRader,
                    "antall_underenheter" to rapport.antallUnderenheter,
                    "antall_personer" to rapport.antallPersoner,
                ),
            )
            .map { row -> Rapport(row) }
            .asSingle
            .let { tx.run(it)!! }

    fun finnRapport(tx: TransactionalSession, id: Rapport.Id): Rapport? =
        queryOf(
                """
                SELECT id, bestilling_id, orgnr, org_navn, type, dato_valutert, bankkonto, antall_rader, antall_underenheter, antall_personer, opprettet, arkivert
                FROM rapport.rapport
                WHERE id = :id
                """
                    .trimIndent(),
                mapOf("id" to id.raw),
            )
            .map { row -> Rapport(row) }
            .asSingle
            .let { tx.run(it) }

    fun listRapporter(tx: TransactionalSession, kriterier: RapportKriterier): List<Rapport> =
        when (kriterier) {
                is DatoRangeKriterier -> {
                    val (orgnrWhere, orgnummere) =
                        when (kriterier) {
                            is InkluderOrgKriterier -> "orgnr = ANY(:orgnummere)" to kriterier.inkluderte
                            is EkskluderOrgKriterier ->
                                if (kriterier.bankkonto == null) {
                                    "(NOT orgnr = ANY(:orgnummere))" to kriterier.ekskluderte
                                } else {
                                    "(NOT orgnr = ANY(:orgnummere)) AND bankkonto = :bankkonto" to kriterier.ekskluderte
                                }
                        }
                    queryOf(
                        """
                            SELECT id, bestilling_id, orgnr, org_navn, type, dato_valutert, bankkonto, antall_rader, antall_underenheter, antall_personer, opprettet, arkivert
                            FROM rapport.rapport
                            WHERE type = ANY(CAST(:rapportType AS rapport.rapport_type[]))
                              AND (arkivert IS NULL OR :inkluderArkiverte)
                              AND $orgnrWhere
                              AND dato_valutert BETWEEN :fraDato AND :tilDato
                            """
                            .trimIndent(),
                        mapOf(
                            "rapportType" to kriterier.rapportTyper.map { it.name }.toTypedArray(),
                            "inkluderArkiverte" to kriterier.inkluderArkiverte,
                            "orgnummere" to orgnummere.map { it.raw }.toTypedArray(),
                            "fraDato" to kriterier.periode.start,
                            "tilDato" to kriterier.periode.end,
                            "bankkonto" to (kriterier as? EkskluderOrgKriterier)?.bankkonto?.raw,
                        ),
                    )
                }

                is EtterIdKriterier -> {
                    queryOf(
                        """
                        SELECT id, bestilling_id, orgnr, org_navn, type, dato_valutert, bankkonto, antall_rader, antall_underenheter, antall_personer, opprettet, arkivert
                        FROM rapport.rapport
                        WHERE type = ANY(CAST(:rapportType AS rapport.rapport_type[]))
                          AND (arkivert IS NULL OR :inkluderArkiverte)
                          AND orgnr = :orgnummer
                          AND id > :etter_id
                        """
                            .trimIndent(),
                        mapOf(
                            "rapportType" to kriterier.rapportTyper.map { it.name }.toTypedArray(),
                            "inkluderArkiverte" to kriterier.inkluderArkiverte,
                            "orgnummer" to kriterier.orgnr.raw,
                            "etter_id" to kriterier.etterId.raw,
                        ),
                    )
                }
            }
            .map { row -> Rapport(row) }
            .asList
            .let { tx.run(it) }

    fun markerRapportArkivert(tx: TransactionalSession, rapportId: Rapport.Id, skalArkiveres: Boolean) {
        queryOf(
                "UPDATE rapport.rapport SET arkivert = :arkivert WHERE id = :id",
                mapOf(
                    "id" to rapportId.raw,
                    "arkivert" to
                        if (skalArkiveres) {
                            Instant.now(clock)
                        } else {
                            null
                        },
                ),
            )
            .asUpdate
            .let { tx.run(it) }
    }

    fun tidligereLastetNedAvEksternBruker(tx: TransactionalSession, id: Rapport.Id): Boolean =
        queryOf(
                """
                SELECT CASE
                           WHEN EXISTS (SELECT 1
                                        FROM rapport.rapport_audit
                                        WHERE rapport_id = :rapport_id
                                          AND hendelse = :hendelse
                                          AND brukernavn NOT LIKE '${EntraId.authType}:%')
                               THEN TRUE
                           ELSE FALSE
                           END AS row_exists
                """
                    .trimIndent(),
                mapOf("rapport_id" to id.raw, "hendelse" to RapportAudit.Hendelse.VARIANT_NEDLASTET.name),
            )
            .map { it.boolean("row_exists") }
            .asSingle
            .let { tx.run(it)!! }

    fun lagreVariant(tx: TransactionalSession, variant: UlagretVariant): Variant =
        queryOf(
                """
                INSERT INTO rapport.rapport_variant (rapport_id, format, filnavn, innhold)
                VALUES (:rapport_id, CAST(:format AS rapport.rapport_format), :filnavn, :innhold)
                RETURNING id, rapport_id, format, filnavn, octet_length(innhold) AS bytes
                """
                    .trimIndent(),
                mapOf(
                    "rapport_id" to variant.rapportId.raw,
                    "format" to variant.format.contentType,
                    "filnavn" to variant.filnavn,
                    "innhold" to variant.innhold.toByteArray(),
                ),
            )
            .map { row -> Variant(row) }
            .asSingle
            .let { tx.run(it)!! }

    fun listVarianter(tx: TransactionalSession, rapportId: Rapport.Id): List<Variant> =
        queryOf(
                """
                SELECT id, rapport_id, format, filnavn, octet_length(innhold) AS bytes
                FROM rapport.rapport_variant
                WHERE rapport_id = :rapportId
                """
                    .trimIndent(),
                mapOf("rapportId" to rapportId.raw),
            )
            .map { row -> Variant(row) }
            .asList
            .let { tx.run(it) }

    fun hentInnhold(tx: TransactionalSession, rapportId: Rapport.Id, format: VariantFormat): Pair<Variant, ByteString>? =
        queryOf(
                """
                SELECT *, octet_length(innhold) AS bytes
                FROM rapport.rapport_variant
                WHERE rapport_id = :rapportId
                  AND format = CAST(:format AS rapport.rapport_format)
                """
                    .trimIndent(),
                mapOf("rapportId" to rapportId.raw, "format" to format.contentType),
            )
            .map { row -> Pair(Variant(row), ByteString(row.bytes("innhold"))) }
            .asSingle
            .let { tx.run(it) }

    fun audit(tx: TransactionalSession, data: RapportAudit) {
        tx.execute(
            queryOf(
                """
                INSERT INTO rapport.rapport_audit (rapport_id, variant_id, tidspunkt, hendelse, brukernavn, tekst)
                VALUES (:rapportId, :variantId, :tidspunkt, :hendelse, :brukernavn, :tekst)
                """
                    .trimIndent(),
                mapOf(
                    "rapportId" to data.rapportId.raw,
                    "variantId" to data.variantId?.raw,
                    "tidspunkt" to data.tidspunkt,
                    "hendelse" to data.hendelse.name,
                    "brukernavn" to data.brukernavn,
                    "tekst" to data.tekst,
                ),
            )
        )
    }

    fun hentAuditlog(tx: TransactionalSession, kriterier: RapportAuditKriterier): List<RapportAudit> =
        queryOf(
                """
                SELECT * FROM rapport.rapport_audit
                WHERE rapport_id = :rapportId
                  AND ( (:variantId :: BIGINT) IS NULL OR variant_id = :variantId )
                  AND ( (:start :: TIMESTAMPTZ) IS NULL OR tidspunkt BETWEEN :start AND :end )
                ORDER BY id ASC
                """
                    .trimIndent(),
                mapOf(
                    "rapportId" to kriterier.rapportId.raw,
                    "variantId" to kriterier.variantId?.raw,
                    "start" to kriterier.periode?.start,
                    "end" to kriterier.periode?.end,
                ),
            )
            .map { RapportAudit(it) }
            .asList
            .let { tx.run(it) }

    fun metrikkForUprosesserteBestillinger(tx: TransactionalSession): Iterable<Pair<Tags, Long>> =
        queryOf(
                """
                WITH kilder AS (SELECT DISTINCT CASE
                                                    WHEN mottatt_fra LIKE 'REST%' THEN 'REST'
                                                    ELSE mottatt_fra
                                                    END AS kilde
                                FROM rapport.rapport_bestilling),
                     dimensjoner AS (SELECT rapport_type, k.kilde, har_feilet
                                     FROM unnest(enum_range(null::rapport.rapport_type)) AS rapport_type,
                                          kilder k,
                                          (VALUES (true), (false)) AS t1(har_feilet)),
                     uferdig AS (SELECT generer_som                     AS rapport_type,
                                        CASE
                                            WHEN mottatt_fra LIKE 'REST%' THEN 'REST'
                                            ELSE mottatt_fra
                                            END                         AS kilde,
                                        prosessering_feilet IS NOT NULL AS har_feilet
                                 FROM rapport.rapport_bestilling
                                 WHERE ferdig_prosessert IS NULL)
                SELECT COUNT(uferdig.*) AS antall,
                       rapport_type,
                       kilde,
                       har_feilet
                FROM dimensjoner d
                         LEFT JOIN uferdig USING (rapport_type, kilde, har_feilet)
                GROUP BY rapport_type, kilde, har_feilet
                """
                    .trimIndent()
            )
            .map {
                Pair(
                    Tags.of(
                        Tag.of("rapporttype", it.string("rapport_type")),
                        Tag.of("kilde", it.string("kilde")),
                        Tag.of("feilet", it.boolean("har_feilet").toString()),
                    ),
                    it.long("antall"),
                )
            }
            .asList
            .let { tx.run(it) }

    fun metrikkForRapporter(tx: TransactionalSession): Iterable<Pair<Tags, Long>> =
        queryOf(
                """
                WITH rtyp AS (SELECT * FROM unnest(enum_range(null::rapport.rapport_type)) AS rapport_type),
                     dim0 AS (SELECT *
                              FROM rtyp,
                                   (VALUES ('0')) AS t1(antall_nedlastinger),
                                   (VALUES ('n/a')) AS t2(auth_type)),
                     dimensjoner AS (SELECT *
                                     FROM rtyp,
                                          (VALUES ('1'), ('2+')) AS t1(antall_nedlastinger),
                                          (VALUES ('entraid'), ('systembruker')) AS t2(auth_type)
                                     UNION
                                     SELECT *
                                     FROM dim0),
                     telling AS (SELECT r.id    AS rapport_id,
                                        r.type  AS rapport_type,
                                        CASE
                                            WHEN COUNT(a.id) <= 1 THEN COUNT(a.id)::text
                                            ELSE '2+'
                                            END AS antall_nedlastinger,
                                        CASE
                                            WHEN brukernavn IS NULL THEN 'n/a'
                                            WHEN brukernavn LIKE 'entraid:%'
                                                OR brukernavn LIKE 'azure:%' THEN 'entraid'
                                            ELSE 'systembruker'
                                            END AS auth_type
                                 FROM rapport.rapport r
                                          LEFT JOIN rapport.rapport_audit a
                                                    ON a.rapport_id = r.id AND a.hendelse = :hendelse
                                 GROUP BY r.id, r.type, a.brukernavn)
                SELECT COUNT(DISTINCT t.rapport_id) AS antall_rapporter, rapport_type, antall_nedlastinger, auth_type
                FROM dimensjoner d
                         LEFT JOIN telling t USING (rapport_type, antall_nedlastinger, auth_type)
                GROUP BY (rapport_type, antall_nedlastinger, auth_type)
                """
                    .trimIndent(),
                mapOf("hendelse" to RapportAudit.Hendelse.VARIANT_NEDLASTET.name),
            )
            .map {
                Pair(
                    Tags.of(
                        Tag.of("rapporttype", it.string("rapport_type")),
                        Tag.of("auth_type", it.string("auth_type")),
                        Tag.of("nedlastinger", it.string("antall_nedlastinger")),
                    ),
                    it.long("antall_rapporter"),
                )
            }
            .asList
            .let { tx.run(it) }
}
