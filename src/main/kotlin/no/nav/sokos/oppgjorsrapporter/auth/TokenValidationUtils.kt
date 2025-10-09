package no.nav.sokos.oppgjorsrapporter.auth

import no.nav.security.token.support.core.context.TokenValidationContext

fun TokenValidationContext.gyldigScope(scope: String): Boolean = this.getClaims("maskinporten").get("scope").toString() == scope

fun TokenValidationContext.gyldigSystembrukerOgConsumer(): Boolean {
    val systembrukerOrgnr = this.getSystembrukerOrgnr()
    val consumerOrgnr = this.getConsumerOrgnr()
    return consumerOrgnr.gyldigOrgnr() && systembrukerOrgnr.gyldigOrgnr()
}

fun String.gyldigOrgnr(): Boolean = this.matches(Regex("\\d{9}"))

fun TokenValidationContext.getSystembrukerOrgnr(): String {
    val authorizationDetails = this.getAuthDetails()
    val systemBrukerOrgMap = authorizationDetails.first().get("systemuser_org") as Map<String, String>
    val systemBrukerOrgnr = systemBrukerOrgMap.extractOrgnummer()
    require(systemBrukerOrgnr != null)
    return systemBrukerOrgnr
}

fun TokenValidationContext.getConsumerOrgnr(): String {
    val consumer = this.getClaims("maskinporten").get("consumer") as Map<String, String>
    val orgnr = consumer.extractOrgnummer()
    require(orgnr != null)
    return orgnr
}

fun TokenValidationContext.getAuthDetails() = this.getClaims("maskinporten").get("authorization_details") as List<Map<String, String>>

private fun Map<String, String>.extractOrgnummer(): String? = get("ID")?.split(":")?.get(1)
