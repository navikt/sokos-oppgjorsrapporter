package no.nav.sokos.oppgjorsrapporter.rapport.varsel

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
}
