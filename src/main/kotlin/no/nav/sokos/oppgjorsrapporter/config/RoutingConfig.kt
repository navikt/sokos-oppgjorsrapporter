package no.nav.sokos.oppgjorsrapporter.config

import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.routing
import no.nav.sokos.oppgjorsrapporter.rapport.rapportApi

fun Application.routingConfig() {
    val applicationState: ApplicationState by dependencies
    routing {
        internalNaisRoutes(applicationState)
        authenticate(AuthenticationType.INTERNE_BRUKERE_AZUREAD_JWT.name, AuthenticationType.API_INTEGRASJON_ALTINN_SYSTEMBRUKER.name) {
            rapportApi()
        }
    }
}
