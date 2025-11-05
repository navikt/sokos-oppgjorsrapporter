@file:UseSerializers(
    LocalDateAsStringSerializer::class,
    InstantAsStringSerializer::class,
    BigDecimalSerializer::class,
    LocalDateAsNullableStringSerializer::class,
)

package no.nav.sokos.oppgjorsrapporter.mq

import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json.Default.decodeFromString
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.rapport.RapportRepository
import no.nav.sokos.oppgjorsrapporter.serialization.BigDecimalSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.InstantAsStringSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.LocalDateAsNullableStringSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.LocalDateAsStringSerializer

class RapportMottak(
    private val applicationState: ApplicationState,
    private val refusjonMqConsumer: MqConsumer,
    private val rapportRepository: RapportRepository,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun run() {
        // TODO: prosesser mottate rapporter
        hentBestillinger { process(it) }
    }

    fun process(bestilling: RefusjonsRapportBestilling) {
        rapportRepository.lagreBestilling(bestilling)
        logger.info { "Hentet rapport-bestilling: $bestilling" }
    }

    private suspend fun hentBestillinger(block: suspend (RefusjonsRapportBestilling) -> Unit) {
        do {
            refusjonMqConsumer.receive()?.let { dokument ->
                logger.debug(TEAM_LOGS_MARKER) { "Melding mottatt: $dokument" }
                try {
                    val bestilling = decodeFromString<RefusjonsRapportBestilling>(dokument)
                    logger.info { "Hentet bestilling på refusjons-rapport fra MQ" }
                    block(bestilling)
                    refusjonMqConsumer.commit()
                } catch (ex: Exception) {
                    refusjonMqConsumer.rollback()
                    logger.error(TEAM_LOGS_MARKER, ex) { "Noe gikk galt; lesing av meldingen er rullet tilbake (kanskje til BOQ)" }
                }
            }
        } while (applicationState.alive)
    }
}

// TODO: mulig at det kommer endringer på denne når vi vet hvordan strukturen til det Trond leverer blir
@Serializable data class RefusjonsRapportBestilling(val header: Header, val datarec: List<Data>)

@Serializable
data class Header(val orgnr: Long, val bankkonto: String, val sumBelop: BigDecimal, val dkSumBelop: String, val valutert: LocalDate)

@Serializable
data class Data(
    val navenhet: Int,
    val bedriftsnummer: Long,
    val kode: String,
    val tekst: String,
    val fnr: String,
    val navn: String,
    val belop: BigDecimal,
    val dk: String,
    val fraDato: LocalDate?,
    val tilDato: LocalDate?,
    val maxDato: LocalDate?,
)
