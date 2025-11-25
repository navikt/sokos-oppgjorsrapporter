package no.nav.sokos.oppgjorsrapporter.rapport

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import no.nav.sokos.oppgjorsrapporter.mq.RefusjonsRapportBestilling

class BestillingProsessor(val rapportService: RapportService) {

    suspend fun run() {
        val baseDelay = 1.seconds
        val maxDelay = 5.minutes
        var t = baseDelay
        while (true) {
            currentCoroutineContext().ensureActive()
            if (prosesserEnBestilling() == null) {
                delay(t)
                t = minOf((t * 1.5), maxDelay)
            } else {
                // Vi fant en bestilling å prosessere; resett eksponensiell backoff.
                t = baseDelay
            }
        }
    }

    fun prosesserEnBestilling(): Rapport? =
        rapportService.prosesserBestilling { bestilling ->
            val (ulagret: UlagretRapport?, generator: ((VariantFormat) -> ByteString?)) =
                when (bestilling.genererSom) {
                    RapportType.K27 -> {
                        val data = RefusjonsRapportBestilling.json.decodeFromString<RefusjonsRapportBestilling>(bestilling.dokument)
                        Pair(
                            UlagretRapport(
                                bestilling.id,
                                OrgNr(data.header.orgnr),
                                bestilling.genererSom,
                                "en tittel",
                                data.header.valutert,
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
                        rapportService.lagreVariant(UlagretVariant(rapport.id, format, "et filnavn", bytes))
                    }
                }
                rapport
            }
        }

    data class UkjentRapportTypeException(override val message: String, override val cause: Throwable) : RuntimeException(message, cause)
}
