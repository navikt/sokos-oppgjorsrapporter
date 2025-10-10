package no.nav.sokos.oppgjorsrapporter.config

import com.nimbusds.jose.util.DefaultResourceRetriever
import io.ktor.server.application.Application
import io.ktor.server.auth.authentication
import mu.KotlinLogging
import no.nav.security.token.support.core.configuration.ProxyAwareResourceRetriever.Companion.DEFAULT_HTTP_CONNECT_TIMEOUT
import no.nav.security.token.support.core.configuration.ProxyAwareResourceRetriever.Companion.DEFAULT_HTTP_READ_TIMEOUT
import no.nav.security.token.support.core.configuration.ProxyAwareResourceRetriever.Companion.DEFAULT_HTTP_SIZE_LIMIT
import no.nav.security.token.support.v3.IssuerConfig
import no.nav.security.token.support.v3.RequiredClaims
import no.nav.security.token.support.v3.TokenSupportConfig
import no.nav.security.token.support.v3.tokenValidationSupport
import no.nav.sokos.oppgjorsrapporter.auth.gyldigScope
import no.nav.sokos.oppgjorsrapporter.auth.gyldigSystembrukerOgConsumer

private val logger = KotlinLogging.logger {}

enum class AuthenticationType {
    INTERNE_BRUKERE_AZUREAD_JWT,
    EKSTERNE_BRUKERE_TOKENX,
    API_INTEGRASJON_ALTINN_SYSTEMBRUKER,
}

fun Application.securityConfig(config: PropertiesConfig.Configuration) {
    authentication {
        tokenValidationSupport(
            name = AuthenticationType.INTERNE_BRUKERE_AZUREAD_JWT.name,
            config =
                TokenSupportConfig(
                    IssuerConfig(
                        name = "azureAd",
                        discoveryUrl = config.securityProperties.azureAdProperties.wellKnownUrl,
                        acceptedAudience = listOf(config.securityProperties.azureAdProperties.clientId),
                    )
                ),
        )
        tokenValidationSupport(
            name = AuthenticationType.API_INTEGRASJON_ALTINN_SYSTEMBRUKER.name,
            config =
                TokenSupportConfig(
                    IssuerConfig(
                        name = "maskinporten",
                        discoveryUrl = config.securityProperties.maskinportenProperties.wellKnownUrl,
                        acceptedAudience = listOf(config.securityProperties.maskinportenProperties.eksponertScope),
                        optionalClaims = listOf("aud", "sub"),
                    )
                ),
            requiredClaims = RequiredClaims(issuer = "maskinporten", claimMap = arrayOf("authorization_details", "consumer", "scope")),
            additionalValidation = {
                it.gyldigScope(config.securityProperties.maskinportenProperties.eksponertScope) && it.gyldigSystembrukerOgConsumer()
            },
            resourceRetriever = DefaultResourceRetriever(DEFAULT_HTTP_CONNECT_TIMEOUT, DEFAULT_HTTP_READ_TIMEOUT, DEFAULT_HTTP_SIZE_LIMIT),
        )
    }
}
