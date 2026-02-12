package no.nav.sokos.oppgjorsrapporter.auth

import no.nav.helsearbeidsgiver.utils.wrapper.Fnr
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import no.nav.sokos.oppgjorsrapporter.rapport.OrgNr

fun MockOAuth2Server.tokenFromDefaultProvider(claims: Map<String, Any> = emptyMap()): String =
    issueToken(issuerId = "default", clientId = "default", tokenCallback = DefaultOAuth2TokenCallback(claims = claims)).serialize()

fun MockOAuth2Server.hentToken(issuerId: String, audience: String, claims: Map<String, Any>): String =
    this.issueToken(issuerId, audience = audience, claims = claims).serialize()

fun MockOAuth2Server.ugyldigTokenManglerSystembruker() =
    hentToken(
        issuerId = "maskinporten",
        audience = "nav:utbetaling/oppgjorsrapporter",
        claims =
            mapOf(
                "scope" to "nav:utbetaling/oppgjorsrapporter",
                "consumer" to mapOf("authority" to "iso6523-actorid-upis", "ID" to "0192:810007842"),
            ),
    )

fun MockOAuth2Server.gyldigSystembrukerAuthToken(orgnr: OrgNr): String =
    hentToken(
        issuerId = AuthClientIdentityProvider.MASKINPORTEN.name,
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
        issuerId = AuthClientIdentityProvider.TOKEN_X.name,
        audience = "dev-gcp:okonomi:sokos-oppgjorsrapporter",
        claims =
            mapOf(
                "sub" to pid.verdi,
                "iss" to "https://tokenx.dev-gcp.nav.cloud.nais.io",
                "client_amr" to "private_key_jwt",
                "pid" to pid,
                "client_id" to "dev-gcp:nais:tokenx-token-generator",
                "aud" to "dev-gcp:helsearbeidsgiver:sykepenger-im-lps-api",
                "acr" to acr,
                "nbf" to 1770869948,
                "idp" to "https://test.idporten.no",
                "scope" to "openid",
                "exp" to 1770873548,
                "iat" to 1770869948,
                "jti" to "a54bc023-9c2f-43ba-af0b-a6e9c30d4a4b",
                "consumer" to mapOf("authority" to "iso6523-actorid-upis", "ID" to "0192:889640782"),
            ),
    )
