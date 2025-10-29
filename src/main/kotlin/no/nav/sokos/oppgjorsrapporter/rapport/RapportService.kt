package no.nav.sokos.oppgjorsrapporter.rapport

import java.time.Instant
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking
import kotliquery.sessionOf
import kotliquery.using
import no.nav.sokos.oppgjorsrapporter.auth.AutentisertBruker
import no.nav.sokos.oppgjorsrapporter.auth.EntraId
import no.nav.sokos.oppgjorsrapporter.auth.Systembruker

class RapportService(private val dataSource: DataSource, private val repository: RapportRepository) {
    private val systemBrukernavn = "system"

    fun insert(rapport: UlagretRapport): Rapport =
        using(sessionOf(dataSource)) {
            it.transaction { tx ->
                repository.insert(tx, rapport).also {
                    repository.audit(
                        tx,
                        RapportAudit(
                            RapportAudit.Id(0),
                            it.id,
                            null,
                            Instant.now(),
                            RapportAudit.Hendelse.RAPPORT_OPPRETTET,
                            systemBrukernavn,
                            null,
                        ),
                    )
                }
            }
        }

    fun findById(id: Rapport.Id): Rapport? = using(sessionOf(dataSource)) { it.transaction { tx -> repository.findById(tx, id) } }

    fun listForOrg(orgNr: OrgNr): List<Rapport> = using(sessionOf(dataSource)) { it.transaction { tx -> repository.listForOrg(tx, orgNr) } }

    fun insertVariant(variant: UlagretVariant): Variant =
        using(sessionOf(dataSource)) {
            it.transaction { tx ->
                repository.insertVariant(tx, variant).also {
                    repository.audit(
                        tx,
                        RapportAudit(
                            RapportAudit.Id(0),
                            it.rapportId,
                            it.id,
                            Instant.now(),
                            RapportAudit.Hendelse.VARIANT_OPPRETTET,
                            systemBrukernavn,
                            null,
                        ),
                    )
                }
            }
        }

    fun listVariants(rapportId: Rapport.Id): List<Variant> =
        using(sessionOf(dataSource)) { it.transaction { tx -> repository.listVariants(tx, rapportId) } }

    fun <T> hentInnhold(
        bruker: AutentisertBruker,
        rapportId: Rapport.Id,
        format: VariantFormat,
        process: suspend (Rapport, ByteArray) -> T,
    ): T? =
        using(sessionOf(dataSource)) {
            it.transaction { tx ->
                repository.hentInnhold(tx, rapportId, format)?.let { (rapport, variantId, innhold) ->
                    repository.audit(
                        tx,
                        RapportAudit(
                            RapportAudit.Id(0),
                            rapport.id,
                            variantId,
                            Instant.now(),
                            RapportAudit.Hendelse.VARIANT_NEDLASTET,
                            brukernavn(bruker),
                            null,
                        ),
                    )
                    runBlocking { process(rapport, innhold) }
                }
            }
        }

    private fun brukernavn(bruker: AutentisertBruker) =
        when (bruker) {
            is EntraId -> "azure:NAVident=${bruker.navIdent}"
            is Systembruker -> "systembruker:system=${bruker.systemId} org=${bruker.userOrg} id=${bruker.userId}"
        }
}
