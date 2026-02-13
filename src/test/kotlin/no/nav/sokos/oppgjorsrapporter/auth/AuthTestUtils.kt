package no.nav.sokos.oppgjorsrapporter.auth

import no.nav.helsearbeidsgiver.utils.wrapper.Fnr
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import no.nav.sokos.oppgjorsrapporter.rapport.OrgNr

fun MockOAuth2Server.tokenFromDefaultProvider(claims: Map<String, Any> = emptyMap()): String =
    issueToken(issuerId = "default", clientId = "default", tokenCallback = DefaultOAuth2TokenCallback(claims = claims)).serialize()

fun MockOAuth2Server.hentToken(issuer: AuthClientIdentityProvider, audience: String, claims: Map<String, Any>): String =
    this.issueToken(issuerId = issuer.verdi, audience = audience, claims = claims).serialize()

fun MockOAuth2Server.ugyldigTokenManglerSystembruker() =
    hentToken(
        issuer = AuthClientIdentityProvider.MASKINPORTEN,
        audience = "nav:utbetaling/oppgjorsrapporter",
        claims =
            mapOf(
                "scope" to "nav:utbetaling/oppgjorsrapporter",
                "consumer" to mapOf("authority" to "iso6523-actorid-upis", "ID" to "0192:810007842"),
            ),
    )

fun MockOAuth2Server.gyldigSystembrukerAuthToken(orgnr: OrgNr): String =
    hentToken(
        issuer = AuthClientIdentityProvider.MASKINPORTEN,
        audience = "nav:utbetaling/oppgjorsrapporter",
        claims =
            mapOf(
                "authorization_details" to
                    listOf(
                        mapOf(
                            "type" to "urn:altinn:systemuser",
                            "systemuser_id" to listOf("a_unique_identifier_for_the_systemuser"),
                            "systemuser_org" to mapOf("authority" to "iso6523-actorid-upis", "ID" to "0192:${orgnr.raw}"),
                            "system_id" to "315339138_tigersys",
                        )
                    ),
                "scope" to "nav:utbetaling/oppgjorsrapporter", // TODO sjekk om scope faktisk blir validert av tokensupport
                "consumer" to mapOf("authority" to "iso6523-actorid-upis", "ID" to "0192:991825827"),
            ),
    )

fun MockOAuth2Server.gyldigTokenXAuthToken(pid: Fnr, acr: String): String =
    hentToken(
        issuer = AuthClientIdentityProvider.TOKEN_X,
        audience = "dev-gcp:oppgjorsrapporter:sokos-oppgjorsrapporter",
        claims = mapOf("pid" to pid.verdi, "acr" to acr),
    )
