@file:UseSerializers(LocalDateAsStringSerializer::class, InstantAsStringSerializer::class, BigDecimalSerializer::class)
@file:OptIn(ExperimentalSerializationApi::class)

package no.nav.sokos.oppgjorsrapporter.mq

import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.UseSerializers
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.BakgrunnsJobb
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.ereg.EregService
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.mq.refusjon.RefusjonsRapportBestilling
import no.nav.sokos.oppgjorsrapporter.mq.trekk_kred.TrekkKredRapportBestilling
import no.nav.sokos.oppgjorsrapporter.mq.trekk_kred.xmlMapper
import no.nav.sokos.oppgjorsrapporter.rapport.RapportService
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType
import no.nav.sokos.oppgjorsrapporter.serialization.BigDecimalSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.InstantAsStringSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.LocalDateAsStringSerializer
import no.nav.sokos.utils.OrgNr
import tools.jackson.module.kotlin.readValue

class BestillingMottak(
    private val consumers: List<MqConsumer>,
    private val eregService: EregService,
    private val rapportService: RapportService,
    private val metrics: Metrics,
    private val clock: Clock,
    applicationState: ApplicationState,
) : BakgrunnsJobb(applicationState) {
    private val logger = KotlinLogging.logger {}

    override suspend fun run() {
        coroutineScope {
            consumers
                .map { consumer ->
                    launch {
                        while (true) {
                            whenEnabled { hentBestilling(consumer) { process(it) } }
                        }
                    }
                }
                .joinAll()
        }
    }

    suspend fun process(melding: Melding, ekstraSjekk: Boolean = false) {
        val (bestilling, rader) =
            when (melding.rapportType) {
                RapportType.`ref-arbg` -> {
                    val bestilling = RefusjonsRapportBestilling.json.decodeFromString<RefusjonsRapportBestilling>(melding.data)
                    if (ekstraSjekk) {
                        bestilling.valider()
                    }
                    sjekkAktivOrg(bestilling.header.orgnr)
                    bestilling to bestilling.datarec.size
                }
                RapportType.`trekk-kred` -> {
                    val bestilling = xmlMapper.readValue<TrekkKredRapportBestilling>(melding.data)

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

    private suspend fun sjekkAktivOrg(orgnr: OrgNr) {
        val org =
            try {
                eregService.hentNoekkelInfo(orgnr)
            } catch (e: Exception) {
                val error = "Bestilling for $orgnr avvist: Feil ved uthenting av nøkkelinfo fra ereg: $e"
                logger.error(TEAM_LOGS_MARKER, e) { error }
                throw RuntimeException(error, e)
            }
        org.opphoersdato
            ?.takeUnless { it.isAfter(LocalDate.now(clock)) }
            ?.let { opphoert ->
                val error = "Bestilling for $orgnr avvist: Organisasjon er opphørt (fra $opphoert)"
                logger.error(TEAM_LOGS_MARKER, error)
                throw IllegalArgumentException(error)
            }
    }

    private suspend fun hentBestilling(consumer: MqConsumer, block: suspend (Melding) -> Unit) {
        consumer.receive()?.let { melding ->
            logger.info(TEAM_LOGS_MARKER) { "Melding mottatt: $melding" }
            try {
                block(melding)
                consumer.commit()
            } catch (ex: Exception) {
                consumer.rollback()
                logger.error(TEAM_LOGS_MARKER, ex) { "Noe gikk galt; lesing av meldingen er rullet tilbake (kanskje til BOQ)" }
            }
        }
    }
}
