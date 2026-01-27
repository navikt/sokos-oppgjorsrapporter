package no.nav.sokos.oppgjorsrapporter.rapport.varsel

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import mu.KLogger
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState

class VarselProsessor(private val applicationState: ApplicationState, private val varselService: VarselService) {
    private val logger: KLogger = KotlinLogging.logger {}

    suspend fun run() {
        logger.trace { "VarselProsessor.run()" }
        val baseDelay = 1.seconds
        val maxDelay = 5.minutes
        var t = baseDelay
        while (true) {
            if (applicationState.disableBackgroundJobs) {
                logger.trace { "BestillingProsessor.run() disablet" }
                delay(baseDelay)
            } else {
                currentCoroutineContext().ensureActive()
                val resultat = varselService.sendVarsel()
                if (resultat == null) {
                    logger.debug { "Fant ingen uprosesserte varsler å prosessere; venter $t før neste forsøk" }
                    delay(t)
                    t = minOf((t * 1.5), maxDelay)
                } else {
                    resultat.fold(
                        { (varsel, _) ->
                            logger.info { "Varsel for ${varsel.rapportId} prosessert; vil se etter flere varsler å prosessere umiddelbart" }
                            t = baseDelay
                        },
                        {
                            logger.info { "Fant et varsel, men prosessering feilet; venter $baseDelay før neste forsøk" }
                            delay(baseDelay)
                        },
                    )
                }
            }
        }
    }
}
