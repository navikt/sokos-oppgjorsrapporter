package no.nav.sokos.oppgjorsrapporter.rapport

import io.micrometer.core.instrument.Tags
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking
import kotlinx.io.bytestring.ByteString
import kotliquery.TransactionalSession
import kotliquery.sessionOf
import kotliquery.using
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.auth.AutentisertBruker
import no.nav.sokos.oppgjorsrapporter.auth.EntraId
import no.nav.sokos.oppgjorsrapporter.auth.Systembruker
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import org.threeten.extra.Interval
import org.threeten.extra.LocalDateRange

abstract class DatabaseSupport(private val dataSource: DataSource) {
    protected fun <T> withTransaction(block: suspend (TransactionalSession) -> T): T =
        // Vi har her med vilje ikke eksponert en `withSession`, siden kotliquery (til i alle fall v1.9.1) vil gjøre
        // `Connection.autoCommit = true` på den underliggende connectioning - slik at kode a la:
        //
        //     sessionOf(dataSource) { session ->
        //       session.transaction {
        //         // Gjør noe transaksjonelt...
        //       }
        //       session.execute(query)
        //     }
        //
        // vil kjøre `query` med autocommit påskrudd, selv om man har bedt dataSource om å lage connections med autoCommit avskrudd.
        using(sessionOf(dataSource)) { it.transaction { tx -> runBlocking { block(tx) } } }
}

class RapportService(
    dataSource: DataSource,
    private val repository: RapportRepository,
    private val clock: Clock,
    private val metrics: Metrics,
) : DatabaseSupport(dataSource) {
    private val logger = KotlinLogging.logger {}
    private val systemBrukernavn = "system"

    fun lagreBestilling(kilde: String, rapportType: RapportType, dokument: String): RapportBestilling = withTransaction {
        repository.lagreBestilling(
            it,
            UlagretRapportBestilling(mottatt = Instant.now(clock), mottattFra = kilde, dokument = dokument, genererSom = rapportType),
        )
    }

    fun <T> prosesserBestilling(process: suspend (TransactionalSession, RapportBestilling) -> T): Result<T>? = withTransaction { tx ->
        val savepoint = tx.connection.underlying.setSavepoint()
        repository.finnUprosessertBestilling(tx)?.let { bestilling ->
            runCatching {
                    // TODO: Er det innafor å la caller bestemme hele prosesserings-oppførselen?  Det gjør jo testing lettere, men...
                    val res = process(tx, bestilling)
                    repository.markerBestillingProsessert(tx, bestilling.id)
                    metrics.tellBestillingsProsessering(rapportType = bestilling.genererSom, kilde = bestilling.mottattFra, feilet = false)
                    res
                }
                .onFailure { e ->
                    logger.error { "Prosessering av '${bestilling.genererSom}'-bestilling #${bestilling.id.raw} feilet" }
                    logger.error(TEAM_LOGS_MARKER, e.cause) { "Prosessering av $bestilling feilet" }
                    // Rull tilbake evt. database-endringer som ble gjort av `process` før ting feilet
                    tx.connection.underlying.rollback(savepoint)

                    metrics.tellBestillingsProsessering(rapportType = bestilling.genererSom, kilde = bestilling.mottattFra, feilet = true)
                    repository.markerBestillingProsesseringFeilet(tx, bestilling.id)
                }
        }
    }

    fun metrikkForUprosesserteBestillinger(): Iterable<Pair<Tags, Long>> = withTransaction {
        repository.metrikkForUprosesserteBestillinger(it)
    }

    fun lagreRapport(tx: TransactionalSession, rapport: UlagretRapport): Rapport =
        repository.lagreRapport(tx, rapport).also { rapport ->
            val rapportEvent =
                RapportAudit(
                    RapportAudit.Id(0),
                    rapport.id,
                    null,
                    Instant.now(clock),
                    RapportAudit.Hendelse.RAPPORT_OPPRETTET,
                    systemBrukernavn,
                    null,
                )
            repository.finnBestilling(tx, rapport.bestillingId)?.let { bestilling ->
                val bestillingEvent =
                    rapportEvent.copy(tidspunkt = bestilling.mottatt, hendelse = RapportAudit.Hendelse.RAPPORT_BESTILLING_MOTTATT)
                repository.audit(tx, bestillingEvent)
            }
            repository.audit(tx, rapportEvent)
        }

    fun finnRapport(id: Rapport.Id): Rapport? = withTransaction { repository.finnRapport(it, id) }

    fun listRapporter(kriterier: RapportKriterier): List<Rapport> = withTransaction { repository.listRapporter(it, kriterier) }

    fun markerRapportArkivert(
        bruker: AutentisertBruker,
        id: Rapport.Id,
        harTilgang: suspend (Rapport) -> Boolean,
        skalArkiveres: Boolean,
    ): Rapport? = withTransaction { tx ->
        repository
            .finnRapport(tx, id)
            ?.takeIf { harTilgang(it) }
            ?.let { rapport ->
                if (skalArkiveres != rapport.erArkivert) {
                    repository.markerRapportArkivert(tx, id, skalArkiveres)
                    repository.audit(
                        tx,
                        RapportAudit(
                            RapportAudit.Id(0),
                            rapport.id,
                            null,
                            Instant.now(clock),
                            if (skalArkiveres) {
                                RapportAudit.Hendelse.RAPPORT_ARKIVERT
                            } else {
                                RapportAudit.Hendelse.RAPPORT_DEARKIVERT
                            },
                            brukernavn(bruker),
                            null,
                        ),
                    )
                    repository.finnRapport(tx, id)
                } else {
                    rapport
                }
            }
    }

    fun lagreVariant(tx: TransactionalSession, variant: UlagretVariant): Variant =
        repository.lagreVariant(tx, variant).also {
            repository.audit(
                tx,
                RapportAudit(
                    RapportAudit.Id(0),
                    it.rapportId,
                    it.id,
                    Instant.now(clock),
                    RapportAudit.Hendelse.VARIANT_OPPRETTET,
                    systemBrukernavn,
                    null,
                ),
            )
        }

    fun listVarianter(rapportId: Rapport.Id): List<Variant> = withTransaction { repository.listVarianter(it, rapportId) }

    fun <T : Any> hentInnhold(
        bruker: AutentisertBruker,
        rapportId: Rapport.Id,
        format: VariantFormat,
        harTilgang: suspend (Rapport) -> Boolean,
        process: suspend (Variant, ByteString) -> T,
    ): T? = withTransaction { tx ->
        repository
            .finnRapport(tx, rapportId)
            ?.takeIf { harTilgang(it) }
            ?.let { rapport ->
                repository.hentInnhold(tx, rapportId, format)?.let { (variant, innhold) ->
                    when (bruker) {
                        // TODO: ID-porten-brukere skal, når vi får støtte for sånne, også regnes som eksterne her
                        is Systembruker ->
                            if (!repository.tidligereLastetNedAvEksternBruker(tx, rapportId)) {
                                metrics.rapportAlderVedForsteNedlasting
                                    .withTags("rapporttype", rapport.type.name, "format", variant.format.contentType)
                                    .record(Duration.between(rapport.opprettet, Instant.now(clock)).toSeconds().toDouble())
                            }
                        is EntraId -> {}
                    }
                    repository.audit(
                        tx,
                        RapportAudit(
                            RapportAudit.Id(0),
                            rapportId,
                            variant.id,
                            Instant.now(clock),
                            RapportAudit.Hendelse.VARIANT_NEDLASTET,
                            brukernavn(bruker),
                            null,
                        ),
                    )
                    process(variant, innhold)
                }
            }
    }

    fun hentAuditLog(kriterier: RapportAuditKriterier): List<RapportAudit> = withTransaction { repository.hentAuditlog(it, kriterier) }

    private fun brukernavn(bruker: AutentisertBruker) =
        listOf(
                bruker.authType,
                when (bruker) {
                    is EntraId -> "NAVident=${bruker.navIdent}"
                    is Systembruker -> "system=${bruker.systemId} userOrg=${bruker.userOrg.raw} id=${bruker.userId}"
                },
            )
            .joinToString(":")
}

sealed interface RapportKriterier {
    val rapportTyper: Set<RapportType>
    val inkluderArkiverte: Boolean
}

sealed interface DatoRangeKriterier : RapportKriterier {
    val periode: LocalDateRange
}

data class InkluderOrgKriterier(
    val inkluderte: Set<OrgNr>,
    override val rapportTyper: Set<RapportType>,
    override val periode: LocalDateRange,
    override val inkluderArkiverte: Boolean,
) : DatoRangeKriterier {
    init {
        require(inkluderte.isNotEmpty()) { "Mangler organisasjon" }
        require(rapportTyper.isNotEmpty()) { "Mangler rapporttype" }
    }
}

data class EkskluderOrgKriterier(
    val ekskluderte: Set<OrgNr>,
    val bankkonto: Bankkonto?,
    override val rapportTyper: Set<RapportType>,
    override val periode: LocalDateRange,
    override val inkluderArkiverte: Boolean,
) : DatoRangeKriterier

data class EtterIdKriterier(
    val orgnr: OrgNr,
    val etterId: Rapport.Id,
    override val rapportTyper: Set<RapportType>,
    override val inkluderArkiverte: Boolean,
) : RapportKriterier

data class RapportAuditKriterier(val rapportId: Rapport.Id, val variantId: Variant.Id? = null, val periode: Interval? = null)
