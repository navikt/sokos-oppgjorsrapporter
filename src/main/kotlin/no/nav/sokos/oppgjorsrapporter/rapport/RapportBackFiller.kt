package no.nav.sokos.oppgjorsrapporter.rapport

import java.time.Duration
import java.time.Instant.now
import kotlinx.coroutines.delay
import mu.KLogger
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.BakgrunnsJobb
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.mq.RefusjonsRapportBestilling
import no.nav.sokos.oppgjorsrapporter.mq.TrekkHendBestilling
import no.nav.sokos.oppgjorsrapporter.mq.TrekkKredRapportBestilling

class RapportBackFiller(private val rapportService: RapportService, applicationState: ApplicationState) : BakgrunnsJobb(applicationState) {
    private val logger: KLogger = KotlinLogging.logger {}

    override suspend fun run() {
        logger.trace { "${javaClass.simpleName}.run()" }
        var done = false
        while (!done) {
            val start = now()
            whenEnabled {
                val res =
                    rapportService.prosesserManglendeNevntInfo<Unit> { tx, rapport, bestilling ->
                        val nevntInfo =
                            when (bestilling.genererSom) {
                                RapportType.`ref-arbg` -> RefusjonsRapportBestilling.decode(bestilling.dokument)
                                RapportType.`trekk-hend` -> TrekkHendBestilling.decode(bestilling.dokument)
                                RapportType.`trekk-kred` -> TrekkKredRapportBestilling.decode(bestilling.dokument)
                            }.nevntInfo()
                        val oppdatert = rapportService.settNevntInfo(tx, rapport.id, nevntInfo)
                        if (oppdatert != 0) {
                            logger.debug {
                                "Har fylt inn nevnt_info for rapport #${rapport.id.raw}/type=${rapport.type}: endret $oppdatert rad, lagt inn ${nevntInfo.size} info-elementer"
                            }
                        }
                    }
                if (res == null) {
                    logger.info("Fant ikke flere (ikke-feilede) rapporter å fylle inn nevnt_info på, avslutter denne sub-jobben")
                    done = true
                }
            }
            if (!done) {
                val end = now()
                delay(timeMillis = Duration.between(start, end).toMillis() / 2)
            }
        }
    }
}
