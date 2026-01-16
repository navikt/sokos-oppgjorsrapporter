package no.nav.sokos.oppgjorsrapporter.rapport

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.io.bytestring.ByteString
import mu.KLogger
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.ereg.EregService
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.mq.RefusjonsRapportBestilling
import no.nav.sokos.oppgjorsrapporter.rapport.generator.RapportGenerator

class BestillingProsessor(
    private val applicationState: ApplicationState,
    private val metrics: Metrics,
    private val rapportGenerator: RapportGenerator,
    private val rapportService: RapportService,
    private val eregService: EregService,
) {
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
                val resultat = prosesserEnBestilling()
                if (resultat == null) {
                    logger.debug { "Fant ingen uprosesserte bestillinger å prosessere; venter $t før neste forsøk" }
                    delay(t)
                    t = minOf((t * 1.5), maxDelay)
                } else if (resultat.isFailure) {
                    logger.info { "Fant en bestilling, men prosessering feilet; venter $baseDelay før neste forsøk" }
                    // At prosessering av en bestilling feilet betyr ikke nødvendigvis at prosessering av andre bestillinger vil feile, så
                    // vi gjør ikke eksponensiell backoff her - men det kan være lurt å legge inn *litt* venting mellom
                    // prosesseringsforsøkene, for å unngå at vår overivrighet er det som faktisk fremprovoserer ytelsesproblemer i
                    // systemene vi snakker med.
                    //
                    // Ventingen her må også se i sammenheng med hvor ofte RapportRepository.finnUprosessertBestilling vil anse en
                    // bestilling som gjen-prosesserbar etter at prosessering har feilet.
                    delay(baseDelay)
                } else {
                    logger.info { "Bestilling prosessert; vil se etter flere bestillinger å prosessere umiddelbart" }
                    // Vi fant en bestilling å prosessere; resett eksponensiell backoff.
                    t = baseDelay
                }
            }
        }
    }

    fun prosesserEnBestilling(): Result<Rapport>? =
        rapportService.prosesserBestilling { tx, bestilling ->
            val (ulagret: UlagretRapport, generator: (suspend (VariantFormat) -> ByteString?)) =
                when (bestilling.genererSom) {
                    RapportType.`ref-arbg` -> {
                        val refusjonsRapportBestilling =
                            RefusjonsRapportBestilling.json.decodeFromString<RefusjonsRapportBestilling>(bestilling.dokument)
                        val organisasjonsNavnOgAdresse = eregService.hentOrganisasjonsNavnOgAdresse(refusjonsRapportBestilling.header.orgnr)
                        Pair(
                            UlagretRapport(
                                bestillingId = bestilling.id,
                                orgnr = OrgNr(refusjonsRapportBestilling.header.orgnr),
                                orgNavn = OrgNavn(organisasjonsNavnOgAdresse.navn),
                                type = bestilling.genererSom,
                                datoValutert = refusjonsRapportBestilling.header.valutert,
                                bankkonto = Bankkonto(refusjonsRapportBestilling.header.bankkonto),
                                antallRader = refusjonsRapportBestilling.datarec.size,
                                antallUnderenheter = refusjonsRapportBestilling.datarec.distinctBy { it.bedriftsnummer }.size,
                                antallPersoner = refusjonsRapportBestilling.datarec.distinctBy { it.fnr }.size,
                            ),
                            suspend { variant: VariantFormat ->
                                when (variant) {
                                    VariantFormat.Pdf ->
                                        rapportGenerator.genererPdfInnhold(refusjonsRapportBestilling, organisasjonsNavnOgAdresse)
                                    VariantFormat.Csv -> rapportGenerator.genererCsvInnhold(refusjonsRapportBestilling)
                                }
                            },
                        )
                    }

                    else -> error("Vet ikke hvordan bestilling av type ${bestilling.genererSom} skal prosesseres ennå")
                }
            rapportService.lagreRapport(tx, ulagret).also { rapport ->
                VariantFormat.entries
                    .mapNotNull { format ->
                        metrics.coRecord({ result ->
                            metrics.rapportGenerertTimer.withTags(
                                "format",
                                format.contentType,
                                "rapporttype",
                                rapport.type.name,
                                "kilde",
                                bestilling.mottattFra,
                                "feilet",
                                result.isFailure.toString(),
                            )
                        }) {
                            generator.invoke(format)?.let { bytes ->
                                rapportService.lagreVariant(
                                    tx,
                                    UlagretVariant(
                                        rapport.id,
                                        format,
                                        "${rapport.orgnr.raw}_${rapport.type.name}_${rapport.datoValutert}_${rapport.id.raw}.${format.name.lowercase()}",
                                        bytes,
                                    ),
                                )
                            }
                        }
                    }
                    .forEach { variant -> metrics.tellGenerertRapportVariant(rapport.type, variant.format, variant.bytes) }
            }
        }
}
