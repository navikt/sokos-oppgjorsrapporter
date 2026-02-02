package no.nav.sokos.oppgjorsrapporter.rapport.varsel

import java.time.Clock
import java.time.Duration
import java.time.Instant
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
import no.nav.sokos.oppgjorsrapporter.rapport.RapportRepository
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType
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

    fun registrerVarsel(tx: TransactionalSession, forRapport: Rapport) {
        VarselSystem.entries.forEach {
            val _ = repository.lagre(tx, UlagretVarsel(forRapport.id, it, Instant.now(clock)))
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
                    logger.error(TEAM_LOGS_MARKER, err) { "Feil ved sending av $varsel:" }
                    val (antall, neste) =
                        with(varsel) {
                            val maxDelay = Duration.ofMinutes(5)
                            val base = 1.5
                            val baseDelay = Duration.ofMillis(1_000)
                            val maxJitter = Duration.ofMillis(500)
                            val vent =
                                minOf(
                                    maxDelay,
                                    baseDelay
                                        .multipliedBy(base.pow(antallForsok).toLong())
                                        .plusMillis(Random.nextLong(maxJitter.toMillis())),
                                )
                            Pair(antallForsok + 1, nesteForsok.plus(vent))
                        }
                    val _ = repository.oppdater(tx, varsel.copy(antallForsok = antall, nesteForsok = neste))
                }
        }
    }

    private suspend fun opprettDialog(rapport: Rapport): UUID =
        dialogportenClient.opprettDialog(
            CreateDialogRequest(
                rapportType = rapport.type,
                orgnr = rapport.orgnr,
                title =
                    when (rapport.type) {
                        RapportType.`ref-arbg` -> "${rapport.type.fulltNavn} (utbetalt ${rapport.datoValutert.tilNorskFormat()})"
                        RapportType.`trekk-hend` -> rapport.type.fulltNavn
                        RapportType.`trekk-kred` -> rapport.type.fulltNavn
                    },
                // TODO: Rydde vekk denne advarselen når Altinn 2 Correspondence skrus av ved utgangen av mai 2026.
                summary =
                    """
                    OBS, unngå doble nedlastinger!
                    Rapporten kommer fra Navs nye system for oppgjørsrapporter.
                    Til og med mai 2026 vil den gamle meldingen "${rapport.type.gammelTittel}" komme som duplikat.
                    """
                        .trimIndent(),
                // TODO: Skal URL her byttes ut med generell URL for en Oppgjørsrapporter-side (hvis det dukker opp en slik)?
                additionalInfo =
                    """
                    Les mer om *${rapport.type.fulltNavn}* (tidligere kalt ${rapport.type.gammelKode}) på
                    [Navs infoside om oppgjørsrapporter](https://www.nav.no/arbeidsgiver/rapporter).
                    """
                        .trimIndent(),
                externalReference = rapport.uuid.toString(),
                idempotentKey = rapport.uuid.toString(),
                isApiOnly = false,
                guiActions =
                    listOf(
                        GuiAction(
                            // TODO: Ta bort "virker ikke ennå" når ekstern-frontenden vår er klar
                            title = listOf(Content.Value.Item("Se rapporten på nav.no (virker ikke ennå)")),
                            // TODO: Korrigere link når URL-namespace for ekstern-frontenden vår lander
                            url = config.applicationProperties.guiBaseUri.resolve("/rapport/${rapport.id.raw}").toString(),
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
                                    config.applicationProperties.apiBaseUri.let { baseUri ->
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
