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

fun TokenValidationContext.maskinportenAuthDetails() =
    (this.claimsFor(AuthenticationType.API_INTEGRASJON_ALTINN_SYSTEMBRUKER).get("authorization_details") as? List<*>)?.filterIsInstance<
        Map<*, *>
    >() ?: emptyList()

fun TokenValidationContext.getSystembruker(): Result<Systembruker> = runCatching {
    val authDetails = this.maskinportenAuthDetails()
    val systemBrukerIdListe = (authDetails.first()["systemuser_id"] as List<*>).filterIsInstance<String>()
    val systemBrukerOrgMap = authDetails.first()["systemuser_org"] as? Map<*, *>
    val systemBrukerOrgnr = systemBrukerOrgMap?.extractOrgnummer()
    requireNotNull(systemBrukerOrgnr)
    Systembruker(systemBrukerIdListe.first(), OrgNr(systemBrukerOrgnr))
}

fun TokenValidationContext.gyldigScope(scope: String): Boolean =
    this.claimsFor(AuthenticationType.API_INTEGRASJON_ALTINN_SYSTEMBRUKER).get("scope").toString() == scope

fun TokenValidationContext.gyldigSystembrukerOgConsumer(): Boolean {
    val systembruker = this.getSystembruker()
    val consumerOrgnr = this.getConsumerOrgnr()
    return consumerOrgnr.gyldigOrgnr() && systembruker.getOrThrow().orgNr.raw.gyldigOrgnr()
}

fun String.gyldigOrgnr(): Boolean = this.matches(Regex("\\d{9}"))

fun TokenValidationContext.getConsumerOrgnr(): String {
    val consumer = this.claimsFor(AuthenticationType.API_INTEGRASJON_ALTINN_SYSTEMBRUKER).get("consumer") as? Map<*, *>
    val orgnr = consumer?.extractOrgnummer()
    requireNotNull(orgnr)
    return orgnr
}

private fun Map<*, *>.extractOrgnummer(): String? = (get("ID") as? String)?.split(":")?.get(1)

fun TokenValidationContext.getEntraId(): Result<EntraId> = runCatching {
    val navIdent = this.claimsFor(AuthenticationType.INTERNE_BRUKERE_AZUREAD_JWT).get("NAVident") as? String
    requireNotNull(navIdent)
    EntraId(navIdent)
}

sealed interface AutentisertBruker

data class Systembruker(val id: String, val orgNr: OrgNr) : AutentisertBruker

data class EntraId(val navIdent: String) : AutentisertBruker
