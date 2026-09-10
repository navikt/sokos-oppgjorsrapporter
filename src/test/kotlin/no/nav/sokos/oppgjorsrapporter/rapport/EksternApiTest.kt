package no.nav.sokos.oppgjorsrapporter.rapport

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.restassured.RestAssured
import io.restassured.specification.RequestSpecification
import java.time.Instant
import java.time.ZoneOffset
import net.javacrumbs.jsonunit.assertj.assertThatJson
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.TestUtil.EntraIdGroup
import no.nav.sokos.oppgjorsrapporter.auth.gyldigTokenXAuthToken
import no.nav.sokos.oppgjorsrapporter.auth.tokenFromDefaultProvider
import no.nav.sokos.oppgjorsrapporter.toDataSource
import no.nav.sokos.utils.Fnr
import no.nav.sokos.utils.OrgNr
import no.nav.sokos.utils.genererGyldig
import org.junit.jupiter.api.Test
import org.threeten.extra.MutableClock

class EksternApiTest : FullTestServer(MutableClock.of(Instant.parse("2025-11-22T12:00:00Z"), ZoneOffset.UTC)) {
    fun client(authToken: String = tokenFromDefaultProvider()): RequestSpecification =
        RestAssured.given()
            .header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            .header(HttpHeaders.Authorization, "Bearer $authToken")
            .port(embeddedServerPort)

    @Test
    fun `GET _api_ekstern_v1_$id (for feil innloggingstype) gir feilmelding`() {
        client(
                authToken =
                    mockOAuth2Server.tokenFromDefaultProvider(mapOf("NAVident" to "user", "groups" to listOf(EntraIdGroup.RANDOM_GROUP)))
            )
            .get("/api/ekstern/v1/1")
            .then()
            .assertThat()
            .statusCode(HttpStatusCode.Unauthorized.value)
    }

    @Test
    fun `GET _api_ekstern_v1_$id (for id som ikke finnes) gir feilmelding`() {
        TestUtil.loadDataSet("db/utvidet_rapport.sql", dbContainer.toDataSource())
        val NON_EXISTENT_ID = 4711

        client(authToken = mockOAuth2Server.gyldigTokenXAuthToken(Fnr.genererGyldig().somUvalidert(), "Level3"))
            .get("/api/ekstern/v1/$NON_EXISTENT_ID")
            .then()
            .assertThat()
            .statusCode(HttpStatusCode.NotFound.value)
    }

    @Test
    fun `GET _api_ekstern_v1_$id returnerer alle rapporter for orgnr og type med variant-nedlastingsinfo for id som finnes`() {
        TestUtil.loadDataSet("db/utvidet_rapport.sql", dbContainer.toDataSource())
        val response =
            client(authToken = mockOAuth2Server.gyldigTokenXAuthToken(Fnr.genererGyldig().somUvalidert(), "Level3"))
                .get("/api/ekstern/v1/2")
                .then()
                .assertThat()
                .statusCode(HttpStatusCode.OK.value)
                .extract()
                .response()!!
        assertThatJson(response.body().prettyPrint())
            .isEqualTo(
                """
                {
                    "forespurtRapportId": 2,
                    "orgnr": "111222333",
                    "orgNavn": "Test Org",
                    "type": "ref-arbg",
                    "rapporter": [
                        {
                            "id": 3,
                            "datoValutert": "2026-03-31",
                            "belop": "400.00",
                            "varianterMedNedlastingsinfo": [
                                {
                                    "format": "pdf",
                                    "filnavn": "111222333_ref-arbg_2026-03-31.pdf",
                                    "nedlastingsinfo": {
                                        "sistLastetNed": "2026-04-03T11:00:00Z",
                                        "sistLastetNedAv": "systembruker"
                                    }
                                },
                                {
                                    "format": "csv",
                                    "filnavn": "111222333_ref-arbg_2026-03-31.csv",
                                    "nedlastingsinfo": null
                                }
                            ]
                        },
                        {
                            "id": 2,
                            "datoValutert": "2026-02-28",
                            "belop": "527.00",
                            "varianterMedNedlastingsinfo": [
                                {
                                    "format": "pdf",
                                    "filnavn": "111222333_ref-arbg_2026-02-28.pdf",
                                    "nedlastingsinfo": {
                                        "sistLastetNed": "2026-03-01T10:00:00Z",
                                        "sistLastetNedAv": "tokenx"
                                    }
                                },
                                {
                                    "format": "csv",
                                    "filnavn": "111222333_ref-arbg_2026-02-28.csv",
                                    "nedlastingsinfo": null
                                }
                            ]
                        },
                        {
                            "id": 1,
                            "datoValutert": "2026-01-31",
                            "belop": "101.01",
                            "varianterMedNedlastingsinfo": [
                                {
                                    "format": "pdf",
                                    "filnavn": "111222333_ref-arbg_2026-01-31.pdf",
                                    "nedlastingsinfo": null
                                },
                                {
                                    "format": "csv",
                                    "filnavn": "111222333_ref-arbg_2026-01-31.csv",
                                    "nedlastingsinfo": null
                                }
                            ]
                        }
                    ]
                }
                """
                    .trimIndent()
            )
    }

    @Test
    fun `POST _api_ekstern_v1 (finnes rapporter for orgnr og rapporttype) returnerer forventet resultat`() {
        TestUtil.loadDataSet("db/utvidet_rapport.sql", dbContainer.toDataSource())

        val response =
            client(authToken = mockOAuth2Server.gyldigTokenXAuthToken(Fnr.genererGyldig().somUvalidert(), "Level3"))
                .body(EksternApi.EksternRapportListeFilterRequest(orgnr = OrgNr("111222333"), rapportType = RapportType.`ref-arbg`))
                .post("/api/ekstern/v1")
                .then()
                .assertThat()
                .statusCode(HttpStatusCode.OK.value)
                .extract()
                .response()
        val rapporter = response.body().`as`(Api.TilgrensendeRapporterDTO::class.java) as Api.TilgrensendeRapporterDTO
        rapporter.forespurtRapportId shouldBe Rapport.Id(0)
        rapporter.orgnr shouldBe OrgNr("111222333")
        rapporter.type shouldBe RapportType.`ref-arbg`
        rapporter.rapporter shouldHaveSize 3
    }

    @Test
    fun `POST _api_ekstern_v1 (finnes rapporter for orgnr, men ikke med rapporttype) returnerer NotFound`() {
        TestUtil.loadDataSet("db/utvidet_rapport.sql", dbContainer.toDataSource())

        client(authToken = mockOAuth2Server.gyldigTokenXAuthToken(Fnr.genererGyldig().somUvalidert(), "Level3"))
            .body(EksternApi.EksternRapportListeFilterRequest(orgnr = OrgNr("111222333"), rapportType = RapportType.`trekk-hend`))
            .post("/api/ekstern/v1")
            .then()
            .assertThat()
            .statusCode(HttpStatusCode.NotFound.value)
            .extract()
            .response()
    }

    @Test
    fun `POST _api_ekstern_v1 (finnes ikke rapporter for orgnr) returnerer NotFound`() {
        TestUtil.loadDataSet("db/utvidet_rapport.sql", dbContainer.toDataSource())

        client(authToken = mockOAuth2Server.gyldigTokenXAuthToken(Fnr.genererGyldig().somUvalidert(), "Level3"))
            .body(EksternApi.EksternRapportListeFilterRequest(orgnr = OrgNr("333222111"), rapportType = RapportType.`trekk-hend`))
            .post("/api/ekstern/v1")
            .then()
            .assertThat()
            .statusCode(HttpStatusCode.NotFound.value)
            .extract()
            .response()
    }
}
