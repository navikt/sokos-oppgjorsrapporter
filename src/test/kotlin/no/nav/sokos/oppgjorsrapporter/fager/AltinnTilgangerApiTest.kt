package no.nav.sokos.oppgjorsrapporter.fager

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.restassured.RestAssured
import io.restassured.specification.RequestSpecification
import java.time.Instant
import java.time.ZoneOffset
import net.javacrumbs.jsonunit.assertj.assertThatJson
import no.nav.sokos.oppgjorsrapporter.auth.gyldigTokenXAuthToken
import no.nav.sokos.oppgjorsrapporter.rapport.FullTestServer
import no.nav.sokos.utils.Fnr
import no.nav.sokos.utils.genererGyldig
import org.junit.jupiter.api.Test
import org.threeten.extra.MutableClock

class AltinnTilgangerApiTest : FullTestServer(MutableClock.of(Instant.parse("2025-11-22T12:00:00Z"), ZoneOffset.UTC)) {
    fun client(authToken: String = tokenFromDefaultProvider()): RequestSpecification =
        RestAssured.given()
            .header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            .header(HttpHeaders.Authorization, "Bearer $authToken")
            .port(embeddedServerPort)

    @Test
    fun `GET _api_organisasjoner (innlogget riktig med tokenX) svarer riktig`() {
        val response =
            client(authToken = mockOAuth2Server.gyldigTokenXAuthToken(pid = Fnr.genererGyldig().somUvalidert(), acr = "Level4"))
                .get("/api/organisasjoner")
                .then()
                .assertThat()
                .statusCode(HttpStatusCode.OK.value)
                .extract()
                .response()!!

        assertThatJson(response.body().prettyPrint())
            .isEqualTo(
                """
                            [
                	{
                                 "tilgang": "nav_utbetaling_oppgjorsrapport-refusjon-arbeidsgiver",
                                 "virksomheter": [
                			{
                                      "orgnr": "987654321",
                                      "navn": "Organisasjon",
                				"underenheter": [
                					{
                						"orgnr": "123456789",
                						"navn": "Bedrift",
                						"underenheter": []
                					}
                				]
                                     }
                		]
                             }
                ]
                """
                    .trimIndent()
            )
    }

    @Test
    fun `GET _api_organisasjoner (dersom man ikke er logget inn med tokenX) gir feilmelding`() {
        client(authToken = tokenFromDefaultProvider())
            .get("/api/organisasjoner")
            .then()
            .assertThat()
            .statusCode(HttpStatusCode.Unauthorized.value)
    }
}
