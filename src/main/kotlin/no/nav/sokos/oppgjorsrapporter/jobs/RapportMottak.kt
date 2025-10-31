package no.nav.sokos.oppgjorsrapporter.jobs

import com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS
import com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY
import com.fasterxml.jackson.annotation.JsonInclude.Value.construct
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.mq.MqConsumer
import org.slf4j.MDC

class RapportMottak(private val applicationState: ApplicationState, private val mqConsumer: MqConsumer) {

    private val logger = KotlinLogging.logger {}

    suspend fun start() {
        // TODO: prosesser mottate rapporter
        hentRapporter { logger.info { "Hentet rapporter fra ur: $it" } }
    }

    private suspend fun hentRapporter(block: suspend (List<RapportUr>) -> Unit) {
        do try {
            // TODO: Vi trenger vel ikke å begrense oss på den måten her? Dette er kopiert fra utbetalingsmelding
            val mqRapporter = mqConsumer.receiveMessages(maxMessages = 1000)
            if (mqRapporter.isNotEmpty()) {
                // TODO: trenger vi å opprette correlation id? Håndteres ikke dette av autoinustrumentering i Nais?
                MDC.put("x-correlation-id", UUID.randomUUID().toString())
                withContext(MDCContext()) {
                    val rapporter = mqRapporter.mapNotNull { RapportUr.fromXml(it) }
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
data class RapportUr(val header: Header, val data: Data, val trailer: Trailer) {

    companion object {
        private val logger = KotlinLogging.logger {}

        // TODO: dette er egentlig feil, bør være fromJson - venter til vi får se formattet fra Trond
        fun fromXml(message: String): RapportUr? =
            try {
                xmlMapper.readValue<RapportUr>(message)
            } catch (ex: Exception) {
                logger.error { "Mottok melding fra UR som ikke kunne leses. Meldingen logges i sikker logg." }
                logger.error(TEAM_LOGS_MARKER) { "Kunne ikke lese melding: ${ex.message}" }
                null
            }

        private val xmlMapper =
            XmlMapper(JacksonXmlModule().apply { setDefaultUseWrapper(false) }).apply {
                registerKotlinModule()
                registerModule(JavaTimeModule())
                disable(SerializationFeature.INDENT_OUTPUT)
                enable(SerializationFeature.WRAP_ROOT_VALUE)
                setDefaultPropertyInclusion(construct(NON_EMPTY, ALWAYS))
                disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                enable(ToXmlGenerator.Feature.WRITE_XML_DECLARATION)
            }
    }
}

data class Header(
    val header: String,
    val orgnr: String,
    val bankkonto: String,
    val sumbeløp: String,
    val periodeFom: String,
    val periodeTom: String,
)

data class Data(
    val navEnhet: String,
    val bedriftsnummer: String,
    val kode: String,
    val kodeTekst: String,
    val fodselsnr: String,
    val fraDato: String,
    val navnPerson: String,
    val tilDato: String,
    val belop: String,
    val maksDato: String,
)

data class Trailer(val antall: String)
