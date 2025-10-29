package no.nav.sokos.oppgjorsrapporter.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import kotlin.collections.get
import no.nav.security.token.support.core.context.TokenValidationContext
import no.nav.security.token.support.core.jwt.JwtTokenClaims
import no.nav.security.token.support.v3.TokenValidationContextPrincipal
import no.nav.sokos.oppgjorsrapporter.config.AuthenticationType
import no.nav.sokos.oppgjorsrapporter.rapport.OrgNr

suspend fun RoutingContext.tokenValidationContext(): TokenValidationContext {
    val principal = call.principal<TokenValidationContextPrincipal>()
    val tokenValidationContext = principal?.context
    if (tokenValidationContext == null) {
        call.respond(HttpStatusCode.Unauthorized, "Uautorisert tilgang")
        throw IllegalStateException("Teknisk feil - mangler tokenValidationContext")
    }
    return tokenValidationContext
}

fun TokenValidationContext.claimsFor(authType: AuthenticationType): JwtTokenClaims = this.getClaims(authType.name)

fun TokenValidationContext.maskinportenAuthDetails() = runCatching {
    (this.claimsFor(AuthenticationType.API_INTEGRASJON_ALTINN_SYSTEMBRUKER).get("authorization_details") as List<*>)
        .filterIsInstance<Map<*, *>>()
        .single()
}

fun TokenValidationContext.getSystembruker(): Result<Systembruker> = runCatching {
    val authDetails = this.maskinportenAuthDetails().getOrThrow()
    val systemBrukerId = (authDetails["systemuser_id"] as List<*>).filterIsInstance<String>().single()
    val systemBrukerOrgMap = authDetails["systemuser_org"] as Map<*, *>
    val systemBrukerOrgnr = requireNotNull(systemBrukerOrgMap.extractOrgnummer())
    Systembruker(systemBrukerId, OrgNr(systemBrukerOrgnr), authDetails["system_id"] as String)
}

fun TokenValidationContext.gyldigScope(scope: String): Boolean =
    this.claimsFor(AuthenticationType.API_INTEGRASJON_ALTINN_SYSTEMBRUKER).get("scope").toString() == scope

fun TokenValidationContext.gyldigSystembrukerOgConsumer(): Boolean {
    val systembruker = this.getSystembruker()
    val consumerOrgnr = this.getConsumerOrgnr()
    return consumerOrgnr.gyldigOrgnr() && systembruker.getOrThrow().userOrg.raw.gyldigOrgnr()
}

fun String.gyldigOrgnr(): Boolean = this.matches(Regex("\\d{9}"))

fun TokenValidationContext.getConsumerOrgnr(): String {
    val consumer = this.claimsFor(AuthenticationType.API_INTEGRASJON_ALTINN_SYSTEMBRUKER).get("consumer") as Map<*, *>
    return requireNotNull(consumer.extractOrgnummer())
}

private fun Map<*, *>.extractOrgnummer(): String? = (get("ID") as? String)?.split(":")?.get(1)

fun TokenValidationContext.getEntraId(): Result<EntraId> = runCatching {
    val claims = this.claimsFor(AuthenticationType.INTERNE_BRUKERE_AZUREAD_JWT)
    val navIdent = requireNotNull(claims.get("NAVident") as String)
    val groups = requireNotNull((claims.get("groups") as List<*>).filterIsInstance<String>())
    EntraId(navIdent, groups)
}

sealed interface AutentisertBruker

data class Systembruker(val userId: String, val userOrg: OrgNr, val systemId: String) : AutentisertBruker

data class EntraId(val navIdent: String, val groups: List<String>) : AutentisertBruker
