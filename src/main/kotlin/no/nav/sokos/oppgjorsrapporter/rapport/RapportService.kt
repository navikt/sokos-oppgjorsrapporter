package no.nav.sokos.oppgjorsrapporter.rapport

import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.Serializable
import kotliquery.TransactionalSession
import kotliquery.sessionOf
import kotliquery.using
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.auth.AutentisertBruker
import no.nav.sokos.oppgjorsrapporter.auth.EntraId
import no.nav.sokos.oppgjorsrapporter.auth.Systembruker
import no.nav.sokos.oppgjorsrapporter.auth.TokenX
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.rapport.varsel.VarselRepository
import no.nav.sokos.oppgjorsrapporter.serialization.InstantAsStringSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.VariantFormatSerializer
import no.nav.sokos.utils.Bankkonto
import no.nav.sokos.utils.Fnr
import no.nav.sokos.utils.OrgNr
import no.nav.sokos.utils.handleCancellationException
import no.nav.sokos.utils.runBlockingIgnoringRogueCancellationException
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
        using(sessionOf(dataSource)) { it.transaction { tx -> runBlockingIgnoringRogueCancellationException { block(tx) } } }
}

class RapportService(
    dataSource: DataSource,
    private val repository: RapportRepository,
    private val varselRepository: VarselRepository,
    private val clock: Clock,
    private val metrics: Metrics,
) : DatabaseSupport(dataSource) {
    private val logger = KotlinLogging.logger {}

    fun lagreBestilling(kilde: String, rapportType: RapportType, dokument: String): RapportBestilling? = withTransaction { tx ->
        val ulagret =
            UlagretRapportBestilling(mottatt = Instant.now(clock), mottattFra = kilde, dokument = dokument, genererSom = rapportType)
        val lagret = repository.lagreBestilling(tx, ulagret)
        if (lagret == null) {
            val tidligere = repository.finnBestillingFraHash(tx, ulagret.genererSom, ulagret.dokument)
            if (tidligere?.dokument == ulagret.dokument) {
                val message =
                    "Duplikat '${rapportType.name}'-bestilling mottatt; denne vil bli kastet (tidligere lagret som bestilling ${tidligere.id.raw})"
                logger.warn { message }
                logger.warn(TEAM_LOGS_MARKER) { message }
                // Ingen throw her, slik at meldingen vi har fått fra MQ vil bli kvittert som lest.
            } else {
                // Vi har mottatt en bestilling hvis dokument har samme sjekksum som et vi hadde fra før - men selv om sjekksummen
                // matcher, så er dokumentene forskjellige (i.e. sjekksum-matchen er en falsk positiv).
                // Logg dette tydelig og re-throw, slik at meldingen blir flyttet til backout-kø og kan håndteres manuelt derfra.
                val message =
                    """
                    Mottatt '${rapportType.name}'-bestilling som sjekksum-matcher med bestilling ${tidligere?.id?.raw} - men dokumentene er forskjellige.

                    Bestillings-meldingen vil bli flyttet til fra backout-kø.  Som følge av sjekksum-matchen vil meldingen ikke kunne prosesseres som den er.

                    For å gjøre den prosesserbar, må man enten endre hvordan applikasjonen sjekker for duplikater, eller endre på meldingen (f.eks. ved å
                    legge til insignifikant whitespace) slik at den får en annen sjekksum.
                    Etter at applikasjonen eller meldingen er endret kan man forsøke å flytte meldingen fra backout- tilbake på input-kø.
                    """
                        .trimIndent()
                val e = IllegalStateException(message)
                logger.error { message }
                logger.error(TEAM_LOGS_MARKER, e) { message }
                throw e
            }
        }
        lagret
    }

    fun finnBestillingFraHash(rapportType: RapportType, dokument: String): RapportBestilling? = withTransaction {
        repository.finnBestillingFraHash(it, rapportType, dokument)
    }

    fun <T> prosesserBestilling(process: suspend (TransactionalSession, RapportBestilling) -> T): Result<T>? = withTransaction { tx ->
        val savepoint = tx.connection.underlying.setSavepoint()
        repository.finnUprosessertBestilling(tx)?.let { bestilling ->
            runCatching {
                // TODO: Er det innafor å la caller bestemme hele prosesserings-oppførselen?  Det gjør jo testing lettere, men...
                val res = process(tx, bestilling)
                repository.markerBestillingProsessert(tx, bestilling.id)
                metrics.tellBestillingsProsessering(rapportType = bestilling.genererSom, feilet = false)
                res
            }
                .handleCancellationException()
                .onFailure { e ->
                    logger.error { "Prosessering av '${bestilling.genererSom}'-bestilling #${bestilling.id.raw} feilet" }
                    logger.error(TEAM_LOGS_MARKER, e) { "Prosessering av $bestilling feilet: $e" }
                    // Rull tilbake evt. database-endringer som ble gjort av `process` før ting feilet
                    tx.connection.underlying.rollback(savepoint)

                    metrics.tellBestillingsProsessering(rapportType = bestilling.genererSom, feilet = true)
                    repository.markerBestillingProsesseringFeilet(tx, bestilling.id)
                }
        }
    }

    fun metrikkForUprosesserteBestillinger(): Iterable<Pair<Tags, Long>> = withTransaction {
        repository.metrikkForUprosesserteBestillinger(it)
    }

    fun oppdaterMultiGauges() {
        withTransaction { tx ->
            repository
                .metrikkForUprosesserteBestillinger(tx)
                .map { (tags, verdi) -> MultiGauge.Row.of(tags, verdi) }
                .let { rows -> metrics.oppdaterUprosesserteBestillinger(rows) }

            repository
                .metrikkForRapporter(tx)
                .map { (tags, verdi) -> MultiGauge.Row.of(tags, verdi) }
                .let { rows -> metrics.oppdaterRapportData(rows) }

            varselRepository
                .metrikkForUprosesserteVarsler(tx)
                .map { (tags, verdi) -> MultiGauge.Row.of(tags, verdi) }
                .let { rows -> metrics.oppdaterUprosesserteVarsler(rows) }
        }
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
                    RapportAudit.systemBrukernavn,
                    null,
                )
            repository.finnBestilling(tx, rapport.bestillingId)?.let { bestilling ->
                val bestillingEvent =
                    rapportEvent.copy(tidspunkt = bestilling.mottatt, hendelse = RapportAudit.Hendelse.RAPPORT_BESTILLING_MOTTATT)
                repository.audit(tx, bestillingEvent)
            }
            repository.audit(tx, rapportEvent)
        }

    fun settNevntInfo(tx: TransactionalSession, rapportId: Rapport.Id, nevntInfo: List<UlagretRapport.NevntInfo>) =
        repository.settNevntInfo(tx, rapportId, nevntInfo)

    fun finnRapport(id: Rapport.Id): Rapport? = withTransaction { repository.finnRapport(it, id) }

    fun listRapporter(kriterier: RapportKriterier): List<Rapport> = withTransaction { repository.listRapporter(it, kriterier) }

    fun listRapporterMedEksternNedlastingsinfo(id: Rapport.Id): List<RapportMedNedlastingsinfo> = withTransaction {
        repository.finnRapport(it, id)?.let { rapport ->
            repository.listRapporterMedNedlastingsinfo(it, rapport.orgnr, rapport.type, EntraId)
        } ?: emptyList()
    }

    fun listRapporterMedEksternNedlastingsinfo(orgnr: OrgNr, rapportType: RapportType) = withTransaction { tx ->
        repository.listRapporterMedNedlastingsinfo(tx = tx, orgNr = orgnr, type = rapportType, ignorerNedlastingerAvBrukerType = EntraId)
    }

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
                    RapportAudit.systemBrukernavn,
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
                        is Systembruker,
                        is TokenX ->
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

    fun hentAuditLog(kriterier: RapportAuditKriterier): List<AuditEvent> = withTransaction { tx ->
        val varianter = repository.listVarianter(tx, kriterier.rapportId).associate { it.id to it.format }
        repository.hentAuditlog(tx, kriterier).map { AuditEvent(it, varianter) }
    }

    private fun brukernavn(bruker: AutentisertBruker) =
        listOf(
                bruker.authType,
                when (bruker) {
                    is EntraId -> "NAVident=${bruker.navIdent}"
                    is TokenX -> "pid=${bruker.pid}"
                    is Systembruker -> "system=${bruker.systemId} userOrg=${bruker.userOrg.raw} id=${bruker.userId}"
                },
            )
            .joinToString(":")

    fun rapportSoek(fnr: Fnr, periode: LocalDateRange, inkluderArkiverte: Boolean, rapportType: RapportType): List<Rapport> =
        withTransaction { tx ->
            repository.rapportSoek(tx, fnr, periode, inkluderArkiverte, rapportType)
        }

    fun rapportSoek(underenhet: OrgNr, periode: LocalDateRange, inkluderArkiverte: Boolean, rapportType: RapportType): List<Rapport> =
        withTransaction { tx ->
            repository.rapportSoek(tx, underenhet, periode, inkluderArkiverte, rapportType)
        }

    fun <T> prosesserManglendeNevntInfo(process: suspend (TransactionalSession, Rapport, RapportBestilling) -> T): Result<T>? =
        withTransaction { tx ->
            val savepoint = tx.connection.underlying.setSavepoint()
            repository.finnRapportSomManglerNevntInfo(tx)?.let { rapport ->
                runCatching {
                    val bestilling = repository.finnBestilling(tx, rapport.bestillingId)!!
                    process(tx, rapport, bestilling)
                }
                    .handleCancellationException()
                    .onFailure { e ->
                        logger.error { "Feil under backfilling av nevnt_info for $rapport" }
                        logger.error(TEAM_LOGS_MARKER, e) { "Feil under backfilling av nevnt_info for $rapport: $e" }

                        // Rull tilbake evt. database-endringer som ble gjort av `process` før ting feilet
                        tx.connection.underlying.rollback(savepoint)

                        repository.markerBestillingProsesseringFeilet(tx, rapport.bestillingId)
                    }
            }
        }
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

data class SpesifikkeIderKriterier(
    val ider: List<Rapport.Id>,
    override val rapportTyper: Set<RapportType>,
    override val inkluderArkiverte: Boolean,
) : RapportKriterier

data class RapportAuditKriterier(val rapportId: Rapport.Id, val variantId: Variant.Id? = null, val periode: Interval? = null)

@Serializable
data class AuditEvent(
    val rapportId: Rapport.Id,
    @Serializable(with = InstantAsStringSerializer::class) val tidspunkt: Instant,
    val hendelse: RapportAudit.Hendelse,
    @Serializable(with = VariantFormatSerializer::class) val format: VariantFormat?,
    val brukernavn: String,
    val tekst: String?,
) {
    constructor(
        e: RapportAudit,
        varianter: Map<Variant.Id, VariantFormat>,
    ) : this(e.rapportId, e.tidspunkt, e.hendelse, e.variantId?.let { varianter[it] }, e.brukernavn, e.tekst)
}
