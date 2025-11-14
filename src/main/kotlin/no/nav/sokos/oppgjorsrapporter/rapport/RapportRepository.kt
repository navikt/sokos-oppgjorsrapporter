package no.nav.sokos.oppgjorsrapporter.rapport

import kotliquery.TransactionalSession
import kotliquery.queryOf

class RapportRepository {
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
                    ORDER BY id
                    LIMIT 1
                    FOR NO KEY UPDATE SKIP LOCKED
                """
                    .trimIndent()
            )
            .map { RapportBestilling(it) }
            .asSingle
            .let { tx.run(it) }

    fun markerBestillingProsessert(tx: TransactionalSession, id: RapportBestilling.Id) =
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

    fun antallUprosesserteBestillinger(tx: TransactionalSession, rapportType: RapportType): Long =
        queryOf(
                """
                    SELECT COUNT(*) AS antall 
                    FROM rapport.rapport_bestilling
                    WHERE ferdig_prosessert IS NULL
                      AND generer_som = CAST(:rapportType AS rapport.rapport_type)
                """
                    .trimIndent(),
                mapOf("rapportType" to rapportType.name),
            )
            .map { it.long("antall") }
            .asSingle
            .let { tx.run(it)!! }

    fun lagreRapport(tx: TransactionalSession, rapport: UlagretRapport): Rapport =
        queryOf(
                """
                    INSERT INTO rapport.rapport(bestilling_id, orgnr, type, tittel, dato_valutert)
                    VALUES (:bestilling_id, :orgnr, CAST(:type AS rapport.rapport_type), :tittel, :dato_valutert)
                    RETURNING *
                """
                    .trimIndent(),
                mapOf(
                    "bestilling_id" to rapport.bestillingId.raw,
                    "orgnr" to rapport.orgNr.raw,
                    "type" to rapport.type.name,
                    "tittel" to rapport.tittel,
                    "dato_valutert" to rapport.datoValutert,
                ),
            )
            .map { row -> Rapport(row) }
            .asSingle
            .let { tx.run(it)!! }

    fun finnRapport(tx: TransactionalSession, id: Rapport.Id): Rapport? =
        queryOf(
                """
                    SELECT id, bestilling_id, orgnr, type, tittel, dato_valutert, opprettet, arkivert
                    FROM rapport.rapport
                    WHERE id = :id
                """
                    .trimIndent(),
                mapOf("id" to id.raw),
            )
            .map { row -> Rapport(row) }
            .asSingle
            .let { tx.run(it) }

    fun listRapporterForOrg(tx: TransactionalSession, orgNr: OrgNr): List<Rapport> =
        queryOf(
                """
                    SELECT id, bestilling_id, orgnr, type, tittel, dato_valutert, opprettet, arkivert
                    FROM rapport.rapport
                    WHERE orgnr = :orgnr
                """
                    .trimIndent(),
                mapOf("orgnr" to orgNr.raw),
            )
            .map { row -> Rapport(row) }
            .asList
            .let { tx.run(it) }

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

    fun hentInnhold(tx: TransactionalSession, rapportId: Rapport.Id, format: VariantFormat): Triple<Rapport, Variant.Id, ByteArray>? =
        queryOf(
                """
                |SELECT r.*, v.id AS variant_id, v.innhold
                | FROM rapport.rapport_variant v
                | JOIN rapport.rapport r ON r.id = v.rapport_id
                |WHERE v.rapport_id = :rapportId
                |  AND v.format = CAST(:format AS rapport.rapport_format)"""
                    .trimMargin(),
                mapOf("rapportId" to rapportId.raw, "format" to format.contentType),
            )
            .map { row -> Triple(Rapport(row), Variant.Id(row.long("variant_id")), row.bytes("innhold")) }
            .asSingle
            .let { tx.run(it) }

    fun audit(tx: TransactionalSession, data: RapportAudit) =
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
