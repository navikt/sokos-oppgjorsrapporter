package no.nav.sokos.oppgjorsrapporter.rapport.varsel

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import javax.sql.DataSource
import kotlin.math.pow
import kotlin.random.Random
import kotliquery.TransactionalSession
import mu.KLogger
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig
import no.nav.sokos.oppgjorsrapporter.config.SWAGGER_DOC_PATH
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.dialogporten.DialogportenClient
import no.nav.sokos.oppgjorsrapporter.dialogporten.domene.Action
import no.nav.sokos.oppgjorsrapporter.dialogporten.domene.ApiAction
import no.nav.sokos.oppgjorsrapporter.dialogporten.domene.Content
import no.nav.sokos.oppgjorsrapporter.dialogporten.domene.CreateDialogRequest
import no.nav.sokos.oppgjorsrapporter.dialogporten.domene.GuiAction
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.rapport.DatabaseSupport
import no.nav.sokos.oppgjorsrapporter.rapport.Rapport
import no.nav.sokos.oppgjorsrapporter.rapport.RapportAudit
import no.nav.sokos.oppgjorsrapporter.rapport.RapportRepository
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType
import no.nav.sokos.oppgjorsrapporter.rapport.SpesifikkeIderKriterier
import no.nav.sokos.oppgjorsrapporter.util.tilNorskFormat

enum class VarselSystem {
    dialogporten
}

class VarselService(
    dataSource: DataSource,
    private val config: PropertiesConfig.Configuration,
    private val repository: VarselRepository,
    private val rapportRepository: RapportRepository,
    private val dialogportenClient: DialogportenClient,
    private val clock: Clock,
    private val metrics: Metrics,
) : DatabaseSupport(dataSource) {
    private val logger: KLogger = KotlinLogging.logger {}

    fun registrerVarsel(forRapport: Rapport.Id) = withTransaction { tx ->
        val rapport = rapportRepository.finnRapport(tx, forRapport)!!
        registrerVarsel(tx, rapport)
    }

    fun registrerVarsel(tx: TransactionalSession, forRapport: Rapport, force: Boolean = false) {
        val varsleAlle = config.application.pilotProdOrgs.isEmpty()
        val pilotProd = config.application.pilotProdOrgs.any { it == forRapport.orgnr }
        if (force || varsleAlle || pilotProd) {
            if (!(varsleAlle || pilotProd)) {
                logger.warn(TEAM_LOGS_MARKER) {
                    "Foretar unntaksmessig varsling til ${forRapport.orgnr}, selv om denne ikke er med i pilot-produksjon"
                }
            }
            VarselSystem.entries.forEach {
                val _ = repository.lagre(tx, UlagretVarsel(forRapport.id, it, Instant.now(clock)))
            }
        } else {
            logger.info(TEAM_LOGS_MARKER) {
                "Varsling til ${forRapport.orgnr} vil ikke bli gjort pga. manglende deltakelse i pilot-produksjon"
            }
        }
    }

    fun sendVarsel(): Result<Varsel>? = withTransaction { tx ->
        repository.finnUprosessertVarsel(tx, Instant.now(clock))?.let { varsel ->
            var operasjon: String? = null
            var rapportType: RapportType? = null
            runCatching {
                    val rapport =
                        rapportRepository.finnRapport(tx, varsel.rapportId)
                            ?: throw IllegalStateException("Fant ikke ${varsel.rapportId} for ${varsel.id}")
                    rapportType = rapport.type
                    when (varsel.system) {
                        VarselSystem.dialogporten -> {
                            if (rapport.dialogportenUuid == null) {
                                operasjon = "opprette"
                                opprettDialog(rapport).let { nyDialogUuid ->
                                    varsel.audit(tx, RapportAudit.Hendelse.RAPPORT_VARSEL_SENDT, "Opprettet dialog med id $nyDialogUuid")

                                    if (rapportRepository.settDialogUuid(tx, rapport.id, nyDialogUuid) == 0) {
                                        logger.info { "Noe har satt dialogporten_uuid på ${rapport.id} underveis i jobben med $varsel" }
                                    } else {
                                        logger.debug { "Satt dialogporten_uuid på ${rapport.id} til $nyDialogUuid" }
                                    }
                                }
                            } else {
                                operasjon = if (rapport.erArkivert) "arkiver" else "dearkiver"
                                dialogportenClient.arkiverDialog(rapport.dialogportenUuid, rapport.erArkivert)
                            }
                        }
                    }
                    varsel
                }
                .onSuccess { v ->
                    metrics.tellVarselProsessering(varsel.system, rapportType, operasjon, feilet = false)
                    repository.slett(tx, v.id)
                }
                .onFailure { err ->
                    metrics.tellVarselProsessering(varsel.system, rapportType, operasjon, feilet = true)
                    if (varsel.antallForsok >= 15) {
                        logger.error(TEAM_LOGS_MARKER, err) { "Feil ved sending av $varsel; vil ikke forsøke flere ganger: $err" }
                        val now = Instant.now(clock)
                        varsel.audit(tx, RapportAudit.Hendelse.RAPPORT_VARSEL_OPPGITT)
                        val _ = repository.oppdater(tx, varsel.copy(oppgitt = now))
                    } else {
                        logger.error(TEAM_LOGS_MARKER, err) { "Feil ved sending av $varsel: $err" }
                        val _ = repository.oppdater(tx, exponentiallyBackedOff(varsel))
                    }
                }
        }
    }

    private fun Varsel.audit(tx: TransactionalSession, hendelse: RapportAudit.Hendelse, tekst: String? = null) {
        rapportRepository.audit(
            tx,
            RapportAudit(
                RapportAudit.Id(0),
                this.rapportId,
                null,
                Instant.now(clock),
                hendelse,
                RapportAudit.systemBrukernavn,
                tekst = tekst,
            ),
        )
    }

    private fun exponentiallyBackedOff(varsel: Varsel): Varsel {
        val (antall, neste) =
            with(varsel) {
                val maxDelay = Duration.ofMinutes(5)
                val base = 1.5
                val baseDelay = Duration.ofMillis(1_000)
                val maxJitter = Duration.ofMillis(500)
                val vent =
                    minOf(
                        maxDelay,
                        baseDelay.multipliedBy(base.pow(antallForsok).toLong()).plusMillis(Random.nextLong(maxJitter.toMillis())),
                    )
                Pair(antallForsok + 1, nesteForsok.plus(vent))
            }
        return varsel.copy(antallForsok = antall, nesteForsok = neste)
    }

    fun finnOppgitte(): List<Pair<Varsel, Rapport>> = withTransaction { tx ->
        val varsler = repository.finnOppgitte(tx)
        val rapporter =
            rapportRepository
                .listRapporter(tx, SpesifikkeIderKriterier(varsler.map { it.rapportId }, RapportType.entries.toSet(), true))
                .associateBy { it.id }
        varsler.map { v ->
            val r = rapporter.get(v.rapportId)
            checkNotNull(r) { "Fant ikke rapport for $v" }
            v to r
        }
    }

    private fun etterAltinn2(type: RapportType): Boolean =
        when (type) {
            RapportType.`ref-arbg` -> LocalDateTime.now(clock).isAfter(LocalDateTime.parse("2026-06-15T12:00"))
            RapportType.`trekk-kred` -> LocalDate.now(clock).isAfter(LocalDate.parse("2026-05-31"))
            RapportType.`trekk-hend` ->
                // Vi planlegger ikke å ha parallell-drift for denne rapport-typen.
                // Så snart vi begynner å produsere slike rapporter og varsler her, vil man allerede ha omkonfigurert hvor
                // Oppdrag sender bestillingsmeldingene - så da er vi "etter altinn2".
                true
        }

    private suspend fun opprettDialog(rapport: Rapport): UUID =
        dialogportenClient.opprettDialog(
            CreateDialogRequest(
                rapportType = rapport.type,
                orgnr = rapport.orgnr,
                title =
                    (if (etterAltinn2(rapport.type)) "" else "Ny Altinn3 - ") +
                        when (rapport.type) {
                            RapportType.`ref-arbg` -> "${rapport.type.fulltNavn} (utbetalt ${rapport.datoValutert.tilNorskFormat()})"
                            RapportType.`trekk-hend` -> rapport.type.fulltNavn
                            RapportType.`trekk-kred` -> "${rapport.type.fulltNavn} (generert ${rapport.datoValutert.tilNorskFormat()})"
                        },
                summary =
                    if (etterAltinn2(rapport.type)) "Ny rapport for utbetaling er tilgjengelig for nedlasting."
                    else
                        when (rapport.type) {
                            // UR genererer kun bestillinger på kveldstid.  I denne sammenhengen ignorerer vi derfor tidsrommet mellom
                            // midnatt og 12.00 den 15. juni (bl.a. fordi summary i en dialog maks kan være 255 tegn langt)
                            RapportType.`ref-arbg` -> "Til og med 14. juni 2026"
                            RapportType.`trekk-kred`,
                            RapportType.`trekk-hend` -> "Til og med mai 2026"
                        }.let { tilOgMedGrense ->
                            """
                            OBS, unngå doble nedlastinger!
                            Rapporten kommer fra Navs nye system for oppgjørsrapporter.
                            $tilOgMedGrense vil den gamle meldingen "${rapport.type.gammelTittel}" komme som duplikat.
                            """
                                .trimIndent()
                        },
                additionalInfo =
                    """
                    Les mer om *${rapport.type.fulltNavn}* (tidligere kalt ${rapport.type.gammelKode}) på
                    [Navs infoside om oppgjørsrapporten](${rapport.type.infoSide}).
                    """
                        .trimIndent(),
                externalReference = rapport.uuid.toString(),
                idempotentKey = rapport.uuid.toString(),
                isApiOnly = false,
                guiActions =
                    listOf(
                        GuiAction(
                            title = listOf(Content.Value.Item("Gå til nedlastingsside på nav.no")),
                            url = config.application.guiBaseUri.resolve("rapport/${rapport.id.raw}").toString(),
                            priority = GuiAction.Priority.Primary,
                            action = Action.access,
                        )
                    ),
                apiActions =
                    listOf(
                        ApiAction(
                            name = "last ned",
                            endpoints =
                                listOf(
                                    config.application.apiBaseUri.let { baseUri ->
                                        ApiAction.Endpoint(
                                            url = baseUri.resolve("/api/rapport/v1/${rapport.id.raw}/innhold").toString(),
                                            httpMethod = ApiAction.HttpMethod.GET,
                                            documentationUrl = baseUri.resolve(SWAGGER_DOC_PATH).toString(),
                                        )
                                    }
                                ),
                            action = Action.access,
                        )
                    ),
            )
        )
}
