package no.nav.sokos.oppgjorsrapporter.service

import mu.KotlinLogging

import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.domain.DummyDomain
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics

private val logger = KotlinLogging.logger {}

class DummyService {
    fun sayHello(): DummyDomain {
        Metrics.exampleCounter.inc()
        logger.info(marker = TEAM_LOGS_MARKER) { "Dette havner i team logs!" }
        return DummyDomain("This is a template for Team Motta og Beregne")
    }
}
