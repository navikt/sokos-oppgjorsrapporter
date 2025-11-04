@file:UseSerializers(LocalDateAsStringSerializer::class, InstantAsStringSerializer::class, BigDecimalSerializer::class)

package no.nav.sokos.oppgjorsrapporter.jobs

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json.Default.decodeFromString
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.mq.MqConsumer
import no.nav.sokos.oppgjorsrapporter.serialization.BigDecimalSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.InstantAsStringSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.LocalDateAsStringSerializer
import org.slf4j.MDC

class RapportMottak(private val applicationState: ApplicationState, private val mqConsumer: MqConsumer) {

    private val logger = KotlinLogging.logger {}

    suspend fun start() {
        // TODO: prosesser mottate rapporter
        hentRapporter { logger.info { "Hentet rapporter fra ur: $it" } }
    }

    private suspend fun hentRapporter(block: suspend (List<RefusjonsRapportBestilling>) -> Unit) {
        do try {
            // TODO: Vi trenger vel ikke å begrense oss på den måten her? Dette er kopiert fra utbetalingsmelding
            val mqRapporter = mqConsumer.receiveMessages(maxMessages = 1000)
            if (mqRapporter.isNotEmpty()) {
                // TODO: trenger vi å opprette correlation id? Håndteres ikke dette av autoinustrumentering i Nais?
                MDC.put("x-correlation-id", UUID.randomUUID().toString())
                withContext(MDCContext()) {
                    val rapporter = mqRapporter.map { decodeFromString<RefusjonsRapportBestilling>(it) }
                    logger.info { "Hentet ${rapporter.size} rapporter fra MQ" }
                    block(rapporter)
                }
                mqConsumer.commit()
            } else delay(timeMillis = 500L)
        } catch (ex: Exception) {
            val antallRapporter = mqConsumer.messagesInTransaction()
            mqConsumer.rollback()
            logger.error(ex) { "Noe gikk galt. Legger $antallRapporter rapporter på BOQ." }
        } while (applicationState.alive)
    }
}

// TODO: mulig at det kommer endringer på denne når vi vet hvordan strukturen til det Trond leverer blir
@Serializable data class RefusjonsRapportBestilling(val header: Header, val datarec: List<Data>)

@Serializable data class Header(val orgnr: Long, val bankkonto: Long, val sumBelop: BigDecimal, val dkSumBelop: String, val valutert: LocalDate)

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
    val fraDato: LocalDate,
    val tilDato: LocalDate,
    val maxdato: LocalDate?,
)
