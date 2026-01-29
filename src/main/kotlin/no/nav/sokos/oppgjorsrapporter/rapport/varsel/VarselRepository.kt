package no.nav.sokos.oppgjorsrapporter.rapport.varsel

import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import java.time.Instant
import kotliquery.TransactionalSession
import kotliquery.queryOf

class VarselRepository {
    fun lagre(tx: TransactionalSession, ulagret: UlagretVarsel): Varsel =
        queryOf(
                """
                INSERT INTO rapport.varsel_behov (rapport_id, system, opprettet, antall_forsok, neste_forsok)
                VALUES (:rapport_id, CAST(:system AS rapport.varsel_system), :opprettet, 0, :opprettet)
                RETURNING *
                """
                    .trimIndent(),
                mapOf("rapport_id" to ulagret.rapportId.raw, "system" to ulagret.system.name, "opprettet" to ulagret.opprettet),
            )
            .map { row -> Varsel(row) }
            .asSingle
            .let { tx.run(it)!! }

    fun finnUprosessertVarsel(tx: TransactionalSession, etter: Instant): Varsel? =
        queryOf(
                """
                SELECT *
                FROM rapport.varsel_behov
                WHERE neste_forsok <= :etter
                ORDER BY neste_forsok ASC
                LIMIT 1
                FOR NO KEY UPDATE SKIP LOCKED
                """
                    .trimIndent(),
                mapOf("etter" to etter),
            )
            .map { row -> Varsel(row) }
            .asSingle
            .let { tx.run(it) }

    fun oppdater(tx: TransactionalSession, varsel: Varsel) =
        queryOf(
                """
                UPDATE rapport.varsel_behov
                  SET antall_forsok = :antall_forsok, neste_forsok = :neste_forsok
                WHERE id = :id
                """
                    .trimIndent(),
                mapOf("id" to varsel.id.raw, "antall_forsok" to varsel.antallForsok, "neste_forsok" to varsel.nesteForsok),
            )
            .asUpdate
            .let { tx.run(it) }

    fun slett(tx: TransactionalSession, varselId: Varsel.Id) {
        return queryOf("DELETE FROM rapport.varsel_behov WHERE id=:id", mapOf("id" to varselId.raw)).asExecute.let { tx.run(it) }
    }

    fun metrikkForUprosesserteVarsler(tx: TransactionalSession): Iterable<Pair<Tags, Long>> =
        queryOf(
                """
                WITH dimensjoner AS (SELECT rapport_type, system, har_feilet
                                     FROM unnest(enum_range(null::rapport.rapport_type)) AS rapport_type,
                                          unnest(enum_range(null::rapport.varsel_system)) AS system,
                                          (VALUES (true), (false)) AS t1(har_feilet)),
                     uferdig AS (SELECT r.id                 AS rapport_id,
                                        r.type               AS rapport_type,
                                        v.system             AS system,
                                        v.antall_forsok >= 1 AS har_feilet
                                 FROM rapport.varsel_behov v
                                 JOIN rapport.rapport r ON r.id = v.rapport_id)
                SELECT COUNT(uferdig.*) AS antall,
                       rapport_type,
                       system,
                       har_feilet
                FROM dimensjoner d
                         LEFT JOIN uferdig USING (rapport_type, system, har_feilet)
                GROUP BY rapport_type, system, har_feilet
                """
                    .trimIndent()
            )
            .map {
                Pair(
                    Tags.of(
                        Tag.of("rapporttype", it.string("rapport_type")),
                        Tag.of("system", it.string("system")),
                        Tag.of("feilet", it.boolean("har_feilet").toString()),
                    ),
                    it.long("antall"),
                )
            }
            .asList
            .let { tx.run(it) }
}
