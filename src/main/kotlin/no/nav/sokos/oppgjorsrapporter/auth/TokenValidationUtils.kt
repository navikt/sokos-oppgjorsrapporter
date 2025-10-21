package no.nav.sokos.oppgjorsrapporter.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import kotlin.collections.get
import no.nav.security.token.support.core.context.TokenValidationContext
import no.nav.security.token.support.v3.TokenValidationContextPrincipal
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

fun TokenValidationContext.getSystembruker(): Result<Systembruker> = runCatching {
    val authDetails = this.getAuthDetails()
    val systemBrukerIdListe = (authDetails.first()["systemuser_id"] as List<*>).filterIsInstance<String>()
    val systemBrukerOrgMap = authDetails.first()["systemuser_org"] as? Map<*, *>
    val systemBrukerOrgnr = systemBrukerOrgMap?.extractOrgnummer()
    require(systemBrukerOrgnr != null)
    Systembruker(systemBrukerIdListe.first(), OrgNr(systemBrukerOrgnr))
}

fun TokenValidationContext.gyldigScope(scope: String): Boolean = this.getClaims("maskinporten").get("scope").toString() == scope

fun TokenValidationContext.gyldigSystembrukerOgConsumer(): Boolean {
    val systembruker = this.getSystembruker()
    val consumerOrgnr = this.getConsumerOrgnr()
    return consumerOrgnr.gyldigOrgnr() && systembruker.getOrThrow().orgNr.raw.gyldigOrgnr()
}

fun String.gyldigOrgnr(): Boolean = this.matches(Regex("\\d{9}"))

fun TokenValidationContext.getConsumerOrgnr(): String {
    val consumer = this.getClaims("maskinporten").get("consumer") as? Map<*, *>
    val orgnr = consumer?.extractOrgnummer()
    require(orgnr != null)
    return orgnr
}

fun TokenValidationContext.getAuthDetails() =
    (this.getClaims("maskinporten").get("authorization_details") as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()

private fun Map<*, *>.extractOrgnummer(): String? = (get("ID") as? String)?.split(":")?.get(1)

data class Systembruker(val id: String, val orgNr: OrgNr)
