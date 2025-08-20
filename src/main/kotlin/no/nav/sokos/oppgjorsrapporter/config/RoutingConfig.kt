package no.nav.sokos.oppgjorsrapporter.config

import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing

import no.nav.sokos.oppgjorsrapporter.api.dummyApi

fun Application.routingConfig(applicationState: ApplicationState) {
    routing {
        internalNaisRoutes(applicationState)
        authenticate(AUTHENTICATION_NAME) {
            dummyApi()
        }
    }
}
