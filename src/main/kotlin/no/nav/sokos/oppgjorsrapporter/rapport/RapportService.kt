package no.nav.sokos.oppgjorsrapporter.rapport

import java.time.Instant
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking
import kotliquery.Session
import kotliquery.TransactionalSession
import kotliquery.sessionOf
import kotliquery.using
import no.nav.sokos.oppgjorsrapporter.auth.AutentisertBruker
import no.nav.sokos.oppgjorsrapporter.auth.EntraId
import no.nav.sokos.oppgjorsrapporter.auth.Systembruker
import org.threeten.extra.LocalDateRange

abstract class DatabaseSupport(private val dataSource: DataSource) {
    protected fun <T> withSession(block: (Session) -> T): T = using(sessionOf(dataSource)) { block(it) }

    protected fun <T> withTransaction(block: (TransactionalSession) -> T): T = withSession { it.transaction { tx -> block(tx) } }
}

class RapportService(dataSource: DataSource, private val repository: RapportRepository) : DatabaseSupport(dataSource) {
    private val systemBrukernavn = "system"

    fun lagreBestilling(kilde: String, rapportType: RapportType, dokument: String): RapportBestilling = withTransaction {
        repository.lagreBestilling(
            it,
            UlagretRapportBestilling(mottatt = Instant.now(), mottattFra = kilde, dokument = dokument, genererSom = rapportType),
        )
    }

    fun <T> prosesserBestilling(block: (RapportBestilling) -> T): T? = withTransaction { tx ->
        repository.finnUprosessertBestilling(tx)?.let { bestilling ->
            // TODO: Er det innafor å la caller bestemme hele prosesserings-oppførselen?  Det gjør jo testing lettere, men...
            block(bestilling).also { repository.markerBestillingProsessert(tx, bestilling.id) }
        }
    }

    fun antallUprosesserteBestillinger(rapportType: RapportType): Long = withTransaction {
        repository.antallUprosesserteBestillinger(it, rapportType)
    }

    fun lagreRapport(rapport: UlagretRapport): Rapport = withTransaction { tx ->
        repository.lagreRapport(tx, rapport).also { rapport ->
            val event =
                RapportAudit(
                    RapportAudit.Id(0),
                    rapport.id,
                    null,
                    Instant.now(),
                    RapportAudit.Hendelse.RAPPORT_OPPRETTET,
                    systemBrukernavn,
                    null,
                )
            repository.finnBestilling(tx, rapport.bestillingId)?.let { bestilling ->
                repository.audit(
                    tx,
                    event.copy(tidspunkt = bestilling.mottatt, hendelse = RapportAudit.Hendelse.RAPPORT_BESTILLING_MOTTATT),
                )
            }
            repository.audit(tx, event)
        }
    }

    fun finnRapport(id: Rapport.Id): Rapport? = withTransaction { repository.finnRapport(it, id) }

    fun listRapporter(kriterier: RapportKriterier): List<Rapport> = withTransaction { repository.listRapporter(it, kriterier) }

    fun lagreVariant(variant: UlagretVariant): Variant = withTransaction { tx ->
        repository.lagreVariant(tx, variant).also {
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

    fun listVarianter(rapportId: Rapport.Id): List<Variant> = withTransaction { repository.listVarianter(it, rapportId) }

    fun <T : Any> hentInnhold(
        bruker: AutentisertBruker,
        rapportId: Rapport.Id,
        format: VariantFormat,
        process: suspend (Rapport, ByteArray) -> T?,
    ): T? = withTransaction { tx ->
        repository.hentInnhold(tx, rapportId, format)?.let { (rapport, variantId, innhold) ->
            runBlocking { process(rapport, innhold) }
                ?.apply {
                    // process() kan returnere null for å indikere at innholdet ikke ble sendt - f.eks. dersom den oppdaget at
                    // brukeren ikke hadde tilgang.  Kun hvis den returnerer non-null skal vi legge inn en audit-event.
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
                }
        }
    }

    private fun brukernavn(bruker: AutentisertBruker) =
        when (bruker) {
            is EntraId -> "azure:NAVident=${bruker.navIdent}"
            is Systembruker -> "systembruker:system=${bruker.systemId} org=${bruker.userOrg} id=${bruker.userId}"
        }
}

sealed interface RapportKriterier {
    val rapportTyper: Set<RapportType>
    val periode: LocalDateRange
    val inkluderArkiverte: Boolean
}

data class InkluderOrgKriterier(
    val inkluderte: Set<OrgNr>,
    override val rapportTyper: Set<RapportType>,
    override val periode: LocalDateRange,
    override val inkluderArkiverte: Boolean,
) : RapportKriterier

data class EkskluderOrgKriterier(
    val ekskluderte: Set<OrgNr>,
    override val rapportTyper: Set<RapportType>,
    override val periode: LocalDateRange,
    override val inkluderArkiverte: Boolean,
) : RapportKriterier
