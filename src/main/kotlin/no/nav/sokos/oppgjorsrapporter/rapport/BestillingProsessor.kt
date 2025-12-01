package no.nav.sokos.oppgjorsrapporter.rapport

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import mu.KLogger
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.mq.RefusjonsRapportBestilling

class BestillingProsessor(val rapportService: RapportService, val applicationState: ApplicationState) {
    private val logger: KLogger = KotlinLogging.logger {}

    suspend fun run() {
        logger.trace { "BestillingProsessor.run()" }
        val baseDelay = 1.seconds
        val maxDelay = 5.minutes
        var t = baseDelay
        while (true) {
            if (applicationState.disableBackgroundJobs) {
                logger.trace { "BestillingProsessor.run() disablet" }
                delay(baseDelay)
            } else {
                currentCoroutineContext().ensureActive()
                if (prosesserEnBestilling() == null) {
                    logger.debug { "Fant ingen uprosesserte bestillinger å prosessere; venter $t før neste forsøk" }
                    delay(t)
                    t = minOf((t * 1.5), maxDelay)
                } else {
                    logger.info { "Bestilling prosessert; vil se etter flere bestillinger å prosessere umiddelbart" }
                    // Vi fant en bestilling å prosessere; resett eksponensiell backoff.
                    t = baseDelay
                }
            }
        }
    }

    fun prosesserEnBestilling(): Rapport? =
        rapportService.prosesserBestilling { bestilling ->
            val (ulagret: UlagretRapport?, generator: ((VariantFormat) -> ByteString?)) =
                when (bestilling.genererSom) {
                    RapportType.`ref-arbg` -> {
                        val data = RefusjonsRapportBestilling.json.decodeFromString<RefusjonsRapportBestilling>(bestilling.dokument)
                        Pair(
                            UlagretRapport(
                                bestillingId = bestilling.id,
                                orgnr = OrgNr(data.header.orgnr),
                                type = bestilling.genererSom,
                                datoValutert = data.header.valutert,
                                bankkonto = Bankkonto(data.header.bankkonto),
                            ),
                            { variant: VariantFormat ->
                                when (variant) {
                                    VariantFormat.Pdf -> null
                                    VariantFormat.Csv -> data.tilCSV().encodeToByteString()
                                }
                            },
                        )
                    }

                    else -> error("Vet ikke hvordan bestilling av type ${bestilling.genererSom} skal prosesseres ennå")
                }
            ulagret?.let {
                val rapport = rapportService.lagreRapport(ulagret)
                VariantFormat.entries.forEach { format ->
                    val bytes = generator.invoke(format)
                    if (bytes != null) {
                        rapportService.lagreVariant(
                            UlagretVariant(
                                rapport.id,
                                format,
                                "${rapport.orgnr.raw}_${rapport.type.name}_${rapport.datoValutert}.${format.name.lowercase()}",
                                bytes,
                            )
                        )
                    }
                }
                rapport
            }
        }
}
