package no.nav.sokos.oppgjorsrapporter.rapport

import io.opentelemetry.instrumentation.annotations.WithSpan
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.io.bytestring.ByteString
import mu.KLogger
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.BakgrunnsJobb
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.ereg.EregService
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.mq.RefusjonsRapportBestilling
import no.nav.sokos.oppgjorsrapporter.mq.TrekkKredRapportBestilling
import no.nav.sokos.oppgjorsrapporter.mq.xmlMapper
import no.nav.sokos.oppgjorsrapporter.rapport.generator.RefusjonsRapportGenerator
import no.nav.sokos.oppgjorsrapporter.rapport.generator.TrekkKredRapportGenerator
import no.nav.sokos.oppgjorsrapporter.rapport.varsel.VarselService
import tools.jackson.module.kotlin.readValue

class BestillingProsessor(
    private val metrics: Metrics,
    private val refusjonsRapportGenerator: RefusjonsRapportGenerator,
    private val trekkKredRapportGenerator: TrekkKredRapportGenerator,
    private val rapportService: RapportService,
    private val eregService: EregService,
    private val varselService: VarselService,
    applicationState: ApplicationState,
) : BakgrunnsJobb(applicationState) {
    private val logger: KLogger = KotlinLogging.logger {}

    override suspend fun run() {
        logger.trace { "BestillingProsessor.run()" }
        val baseDelay = 1.seconds
        val maxDelay = 5.minutes
        var t = baseDelay
        while (true) {
            whenEnabled {
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

    @WithSpan
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
                                orgnr = refusjonsRapportBestilling.header.orgnr,
                                orgNavn = OrgNavn(organisasjonsNavnOgAdresse.navn),
                                type = bestilling.genererSom,
                                datoValutert = refusjonsRapportBestilling.header.valutert,
                                bankkonto = refusjonsRapportBestilling.header.bankkonto,
                                antallRader = refusjonsRapportBestilling.datarec.size,
                                antallUnderenheter = refusjonsRapportBestilling.datarec.distinctBy { it.bedriftsnummer }.size,
                                antallPersoner = refusjonsRapportBestilling.datarec.distinctBy { it.fnr }.size,
                            ),
                            suspend { variant: VariantFormat ->
                                when (variant) {
                                    VariantFormat.Pdf ->
                                        refusjonsRapportGenerator.genererPdfInnhold(refusjonsRapportBestilling, organisasjonsNavnOgAdresse)
                                    VariantFormat.Csv -> refusjonsRapportGenerator.genererCsvInnhold(refusjonsRapportBestilling)
                                }
                            },
                        )
                    }
                    RapportType.`trekk-kred` -> {
                        val trekkKredBestilling = xmlMapper.readValue<TrekkKredRapportBestilling>(bestilling.dokument)
                        val mottaker = trekkKredBestilling.brukerData.mottaker
                        val urData = trekkKredBestilling.brukerData.brevinfo.variableFelter.ur
                        val organisasjonsNavnOgAdresse = eregService.hentOrganisasjonsNavnOgAdresse(urData.orgnummer)
                        Pair(
                            UlagretRapport(
                                bestillingId = bestilling.id,
                                orgnr = OrgNr(urData.orgnummer),
                                orgNavn = OrgNavn(mottaker.navn.fulltNavn),
                                type = bestilling.genererSom,
                                datoValutert = trekkKredBestilling.dato,
                                antallRader = urData.arkivRefList.sumOf { it.enhetList.sumOf { it.trekkLinjeList.size } },
                                bankkonto = Bankkonto(urData.kontonummer),
                                antallUnderenheter = null,
                                antallPersoner =
                                    urData.arkivRefList
                                        .flatMap { it.enhetList.flatMap { it.trekkLinjeList.map { it.fnr } } }
                                        .distinct()
                                        .size,
                            ),
                            suspend { variant: VariantFormat ->
                                when (variant) {
                                    VariantFormat.Pdf ->
                                        trekkKredRapportGenerator.genererPdfInnhold(trekkKredBestilling, organisasjonsNavnOgAdresse)
                                    VariantFormat.Csv -> trekkKredRapportGenerator.genererCsvInnhold(trekkKredBestilling)
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
                varselService.registrerVarsel(tx, rapport)
            }
        }
}
