@file:UseSerializers(LocalDateAsStringSerializer::class, InstantAsStringSerializer::class, BigDecimalSerializer::class)
@file:OptIn(ExperimentalSerializationApi::class)

package no.nav.sokos.oppgjorsrapporter.mq

import io.opentelemetry.instrumentation.annotations.SpanAttribute
import io.opentelemetry.instrumentation.annotations.WithSpan
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.UseSerializers
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.BakgrunnsJobb
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.rapport.RapportService
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType
import no.nav.sokos.oppgjorsrapporter.serialization.BigDecimalSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.InstantAsStringSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.LocalDateAsStringSerializer
import tools.jackson.module.kotlin.readValue

class BestillingMottak(
    private val consumers: List<MqConsumer>,
    private val rapportService: RapportService,
    private val metrics: Metrics,
    applicationState: ApplicationState,
) : BakgrunnsJobb(applicationState) {
    private val logger = KotlinLogging.logger {}

    override suspend fun run() {
        coroutineScope {
            consumers
                .map { consumer ->
                    launch {
                        while (true) {
                            whenEnabled { hentBestilling(consumer, consumer.queueName) { process(it) } }
                        }
                    }
                }
                .joinAll()
        }
    }

    @WithSpan
    fun process(melding: Melding, ekstraSjekk: Boolean = false) {
        val (bestilling, rader) =
            when (melding.rapportType) {
                RapportType.`ref-arbg` -> {
                    val bestilling = RefusjonsRapportBestilling.json.decodeFromString<RefusjonsRapportBestilling>(melding.data)
                    if (ekstraSjekk) {
                        bestilling.valider()
                    }
                    bestilling to bestilling.datarec.size
                }
                RapportType.`trekk-kred` -> {
                    val bestilling = TrekkKredRapportBestilling.xmlMapper.readValue<TrekkKredRapportBestilling>(melding.data)
                    // TODO Skal vi implementere "ekstraSjekk" eller er dette strengt tatt nødvendig for T14?
                    bestilling to
                        bestilling.brukerData.brevinfo.variableFelter.ur.arkivRefList.sumOf {
                            it.enhetList.sumOf { it.trekkLinjeList.size }
                        }
                }
                else -> error("Vet ikke hvordan mottatte bestillinger for rapportType ${melding.rapportType} skal håndteres")
            }
        logger.info(TEAM_LOGS_MARKER) { "Hentet rapport-bestilling: $bestilling" }
        val _ = rapportService.lagreBestilling(melding.kilde, melding.rapportType, melding.data)
        metrics.tellMottak(melding.rapportType, melding.kilde, rader)
    }

    @WithSpan
    private suspend fun hentBestilling(consumer: MqConsumer, @SpanAttribute queueName: String, block: suspend (Melding) -> Unit) {
        consumer.receive()?.let { melding ->
            logger.info(TEAM_LOGS_MARKER) { "Melding mottatt fra $queueName: $melding" }
            try {
                block(melding)
                consumer.commit()
            } catch (ex: Exception) {
                consumer.rollback()
                logger.error(TEAM_LOGS_MARKER, ex) {
                    "Noe gikk galt; lesing av meldingen fra $queueName er rullet tilbake (kanskje til BOQ)"
                }
            }
        }
    }
}
