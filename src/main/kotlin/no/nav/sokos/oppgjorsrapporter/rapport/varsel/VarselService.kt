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
import no.nav.sokos.oppgjorsrapporter.rapport.DatabaseSupport
import no.nav.sokos.oppgjorsrapporter.rapport.Rapport
import no.nav.sokos.oppgjorsrapporter.rapport.RapportRepository

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

    fun sendVarsel(): Result<Pair<Varsel, UUID>>? = withTransaction { tx ->
        repository.finnUprosessertVarsel(tx, Instant.now(clock))?.let { varsel ->
            runCatching {
                    val rapport =
                        rapportRepository.finnRapport(tx, varsel.rapportId)
                            ?: throw IllegalStateException("Fant ikke ${varsel.rapportId} for ${varsel.id}")
                    when (varsel.system) {
                        VarselSystem.dialogporten -> {
                            val dialogUuid =
                                rapport.dialogportenUuid?.also { eksisterendeUuid ->
                                    dialogportenClient.arkiverDialog(eksisterendeUuid, rapport.erArkivert)
                                }
                                    ?: opprettDialog(rapport).also { nyDialogUuid ->
                                        if (rapportRepository.settDialogUuid(tx, rapport.id, nyDialogUuid) == 0) {
                                            logger.info { "Noe har satt dialogporten_uuid på ${rapport.id} underveis i jobben med $varsel" }
                                        } else {
                                            logger.debug { "Satt dialogporten_uuid på ${rapport.id} til $nyDialogUuid" }
                                        }
                                    }
                            varsel to dialogUuid
                        }
                    }
                }
                .onSuccess { (v, _) -> repository.slett(tx, v.id) }
                .onFailure { err ->
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
                title = "Oppgjørsrapport arbeidsgiver - refusjoner fra Nav",
                summary = "Ny Oppgjørsrapport arbeidsgiver - refusjoner fra Nav er tilgjengelig",
                externalReference = rapport.uuid.toString(),
                idempotentKey = rapport.uuid.toString(),
                isApiOnly = false,
                transmissions = emptyList(),
                guiActions =
                    listOf(
                        GuiAction(
                            title = listOf(Content.Value.Item("Gå til nav.no")),
                            url = config.applicationProperties.guiBaseUri.toString(), // TODO: Mer direkte link
                            priority = GuiAction.Priority.Primary,
                            action = Action.READ.value,
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
                            action = Action.READ.value,
                        )
                    ),
            )
        )
}
