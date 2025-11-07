@file:UseSerializers(LocalDateAsStringSerializer::class, InstantAsStringSerializer::class, BigDecimalSerializer::class)
@file:OptIn(ExperimentalSerializationApi::class)

package no.nav.sokos.oppgjorsrapporter.mq

import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.rapport.RapportRepository
import no.nav.sokos.oppgjorsrapporter.serialization.BigDecimalSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.InstantAsStringSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.LocalDateAsStringSerializer

class RapportMottak(
    private val applicationState: ApplicationState,
    private val refusjonMqConsumer: MqConsumer,
    private val rapportRepository: RapportRepository,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun run() {
        while (applicationState.ready) {
            hentBestilling { process(it) }
        }
    }

    fun process(dokument: String) {
        val bestilling = RefusjonsRapportBestilling.json.decodeFromString<RefusjonsRapportBestilling>(dokument)
        logger.info(TEAM_LOGS_MARKER) { "Hentet rapport-bestilling: $bestilling" }
        // TODO: prosesser mottate rapporter
        rapportRepository.lagreBestilling(bestilling)
    }

    private suspend fun hentBestilling(block: suspend (String) -> Unit) {
        refusjonMqConsumer.receive()?.let { dokument ->
            logger.info(TEAM_LOGS_MARKER) { "Melding mottatt: $dokument" }
            try {
                block(dokument)
                refusjonMqConsumer.commit()
            } catch (ex: Exception) {
                refusjonMqConsumer.rollback()
                logger.error(TEAM_LOGS_MARKER, ex) { "Noe gikk galt; lesing av meldingen er rullet tilbake (kanskje til BOQ)" }
            }
        }
    }
}

// TODO: mulig at det kommer endringer på denne når vi vet hvordan strukturen til det Trond leverer blir
@Serializable
data class RefusjonsRapportBestilling(val header: Header, val datarec: List<Data>) {
    companion object {
        val json = Json { explicitNulls = false }
    }
}

@Serializable
data class Header(
    val orgnr: Long,
    val bankkonto: String,
    val sumBelop: BigDecimal,
    val valutert: LocalDate,
    // Antall elementer i `datarec: List<Data>`.  Trengs egentlig ikke av vår applikasjon, men JSON-genereringen på sender-siden har ikke
    // noen enkel måte å utelate den på.
    val linjer: Long,
)

@Serializable
data class Data(
    val navenhet: Int,
    val bedriftsnummer: Long,
    val kode: String,
    val tekst: String,
    val fnr: String,
    val navn: String,
    val belop: BigDecimal,
    val fraDato: LocalDate?,
    val tilDato: LocalDate?,
    val maxDato: LocalDate?,
)
