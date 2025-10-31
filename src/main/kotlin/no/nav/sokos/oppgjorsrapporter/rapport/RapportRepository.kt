package no.nav.sokos.oppgjorsrapporter.rapport

import kotliquery.TransactionalSession
import kotliquery.queryOf

class RapportRepository {
    fun insert(tx: TransactionalSession, rapport: UlagretRapport): Rapport {
        val query =
            queryOf(
                    """
                        INSERT INTO rapport.rapport(orgnr, type, tittel, dato_valutert)
                        VALUES (:orgnr, CAST(:type as rapport.rapport_type), :tittel, :dato_valutert)
                        RETURNING *
                    """
                        .trimIndent(),
                    mapOf(
                        "orgnr" to rapport.orgNr.raw,
                        "type" to rapport.type.name,
                        "tittel" to rapport.tittel,
                        "dato_valutert" to rapport.datoValutert,
                    ),
                )
                .map { row -> Rapport(row) }
                .asSingle
        return tx.run(query)!!
    }

    fun findById(tx: TransactionalSession, id: Rapport.Id): Rapport? {
        val query =
            queryOf(
                    """
                        SELECT id, orgnr, type, tittel, dato_valutert, opprettet, arkivert
                        FROM rapport.rapport
                        WHERE id = :id
                    """
                        .trimIndent(),
                    mapOf("id" to id.raw),
                )
                .map { row -> Rapport(row) }
                .asSingle
        return tx.run(query)
    }

    fun listForOrg(tx: TransactionalSession, orgNr: OrgNr): List<Rapport> {
        val query =
            queryOf(
                    """
                        SELECT id, orgnr, type, tittel, dato_valutert, opprettet, arkivert
                        FROM rapport.rapport
                        WHERE orgnr = :orgnr
                    """
                        .trimIndent(),
                    mapOf("orgnr" to orgNr.raw),
                )
                .map { row -> Rapport(row) }
                .asList
        return tx.run(query)
    }

    fun insertVariant(tx: TransactionalSession, variant: UlagretVariant): Variant {
        val query =
            queryOf(
                    """
                        INSERT INTO rapport.rapport_variant (rapport_id, format, filnavn, innhold) 
                        VALUES (:rapport_id, CAST(:format as rapport.rapport_format), :filnavn, :innhold)
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
        return tx.run(query)!!
    }

    fun listVariants(tx: TransactionalSession, rapportId: Rapport.Id): List<Variant> {
        val query =
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
        return tx.run(query)
    }

    fun hentInnhold(tx: TransactionalSession, rapportId: Rapport.Id, format: VariantFormat): Triple<Rapport, Variant.Id, ByteArray>? {
        val query =
            queryOf(
                    """
                    |SELECT r.*, v.id AS variant_id, v.innhold
                    | FROM rapport.rapport_variant v
                    | JOIN rapport.rapport r ON r.id = v.rapport_id
                    |WHERE v.rapport_id = :rapportId
                    |  AND v.format = CAST(:format as rapport.rapport_format)"""
                        .trimMargin(),
                    mapOf("rapportId" to rapportId.raw, "format" to format.contentType),
                )
                .map { row -> Triple(Rapport(row), Variant.Id(row.long("variant_id")), row.bytes("innhold")) }
                .asSingle
        return tx.run(query)
    }

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
