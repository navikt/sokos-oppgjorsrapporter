package no.nav.sokos.oppgjorsrapporter.rapport

import java.io.InputStream
import java.time.Instant
import javax.sql.DataSource
import kotliquery.TransactionalSession
import kotliquery.queryOf
import kotliquery.sessionOf
import kotliquery.using

class RapportService(private val dataSource: DataSource) {
    fun insert(rapport: UlagretRapport): Rapport =
        using(sessionOf(dataSource)) { session ->
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
            session.transaction { tx ->
                tx.run(query)!!.also {
                    audit(
                        tx,
                        RapportAudit(
                            RapportAudit.Id(0),
                            it.id,
                            null,
                            Instant.now(),
                            RapportAudit.Hendelse.RAPPORT_OPPRETTET,
                            currentUser(),
                            null,
                        ),
                    )
                }
            }
        }

    fun findById(id: Rapport.Id): Rapport? =
        using(sessionOf(dataSource)) { session ->
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
            session.transaction { tx -> tx.run(query) }
        }

    fun listForOrg(orgNr: OrgNr): List<Rapport> =
        using(sessionOf(dataSource)) { session ->
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
            session.transaction { tx -> tx.run(query) }
        }

    fun insertVariant(variant: UlagretVariant): Variant =
        using(sessionOf(dataSource)) { session ->
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
            session.transaction { tx ->
                tx.run(query)!!.also {
                    audit(
                        tx,
                        RapportAudit(
                            RapportAudit.Id(0),
                            it.rapportId,
                            it.id,
                            Instant.now(),
                            RapportAudit.Hendelse.VARIANT_OPPRETTET,
                            currentUser(),
                            null,
                        ),
                    )
                }
            }
        }

    fun listVariants(rapportId: Rapport.Id): List<Variant> =
        using(sessionOf(dataSource)) { session ->
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
            session.transaction { tx -> tx.run(query) }
        }

    fun <T> hentInnhold(variantId: Variant.Id, process: (InputStream) -> T): T? =
        using(sessionOf(dataSource)) { session ->
            session.transaction { tx ->
                val query =
                    queryOf(
                            "SELECT rapport_id, innhold FROM rapport.rapport_variant WHERE id = :variantId",
                            mapOf("variantId" to variantId.raw),
                        )
                        .map { row ->
                            audit(
                                tx,
                                RapportAudit(
                                    RapportAudit.Id(0),
                                    Rapport.Id(row.long("rapport_id")),
                                    null,
                                    Instant.now(),
                                    RapportAudit.Hendelse.VARIANT_NEDLASTET,
                                    currentUser(),
                                    null,
                                ),
                            )

                            process(row.binaryStream("innhold"))
                        }
                        .asSingle

                tx.run(query)
            }
        }

    private fun currentUser() = "auth_not_implemented_yet"

    private fun audit(tx: TransactionalSession, data: RapportAudit) =
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
