package no.nav.sokos.oppgjorsrapporter.rapport

import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.kotest.extensions.testcontainers.toDataSource
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.restassured.RestAssured
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.io.bytestring.buildByteString
import net.javacrumbs.jsonunit.assertj.assertThatJson
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.OAuth2Config
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.TestUtil.testApplicationConfig
import no.nav.sokos.oppgjorsrapporter.auth.tokenFromDefaultProvider
import no.nav.sokos.oppgjorsrapporter.module
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.threeten.extra.MutableClock

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class FullTestServer(protected val testClock: Clock) {
    protected val dbContainer = TestContainer.postgres

    private lateinit var _mockOAuth2Server: MockOAuth2Server
    protected val mockOAuth2Server: MockOAuth2Server by this::_mockOAuth2Server

    private lateinit var _server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>

    protected val embeddedServerPort: Int by lazy { runBlocking { _server.engine.resolvedConnectors().single().port } }

    @BeforeAll
    fun init() {
        _mockOAuth2Server = MockOAuth2Server(OAuth2Config()).apply { start() }
        _server =
            embeddedServer(Netty, 0) {
                    module(
                        testApplicationConfig(dbContainer = dbContainer, mqContainer = null, server = _mockOAuth2Server),
                        clock = testClock,
                    )
                }
                .start()
    }

    @AfterAll
    fun tearDown() {
        _server.stop()
        _mockOAuth2Server.shutdown()
    }

    protected fun tokenFromDefaultProvider(claims: Map<String, Any>? = null): String =
        mockOAuth2Server.tokenFromDefaultProvider(claims ?: defaultClaims)

    open protected val defaultClaims: Map<String, Any> = emptyMap()
}

class RapportApiTest : FullTestServer(MutableClock.of(Instant.parse("2025-11-22T12:00:00Z"), ZoneOffset.UTC)) {
    override protected val defaultClaims: Map<String, Any> = mapOf("NAVident" to "user", "groups" to listOf("group"))

    val openApiValidationFilter = OpenApiValidationFilter("openapi/rapport-v1.yaml")

    fun client(validationFilter: OpenApiValidationFilter? = openApiValidationFilter) =
        RestAssured.given()
            .apply {
                if (validationFilter != null) {
                    filter(validationFilter)
                }
            }
            .header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            .header(HttpHeaders.Authorization, "Bearer ${tokenFromDefaultProvider()}")
            .port(embeddedServerPort)

    @Test
    fun `POST _api_rapport_v1 (med både aar og fraDato spesifisert) gir feilmelding`() {
        TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
        val response =
            client()
                .body(
                    """
                        {
                            "aar": 2025,
                            "fraDato": "2025-01-01"
                        }
                    """
                        .trimIndent()
                )
                .post("/api/rapport/v1")
                .then()
                .assertThat()
                .statusCode(HttpStatusCode.BadRequest.value)
                .extract()
                .response()!!
        assertThat(response.body().asString()).contains("aar kan ikke kombineres med fraDato")
    }

    @Test
    fun `POST _api_rapport_v1 (med både aar og tilDato spesifisert) gir feilmelding`() {
        TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
        val response =
            client()
                .body(
                    """
                        {
                            "aar": 2025,
                            "tilDato": "2025-01-01"
                        }
                    """
                        .trimIndent()
                )
                .post("/api/rapport/v1")
                .then()
                .assertThat()
                .statusCode(HttpStatusCode.BadRequest.value)
                .extract()
                .response()!!
        assertThat(response.body().asString()).contains("aar kan ikke kombineres med tilDato")
    }

    @Test
    fun `POST _api_rapport_v1 (hvis fraDato er etter tilDato) gir feilmelding`() {
        TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
        val response =
            client()
                .body(
                    """
                        {
                            "fraDato": "2025-01-02",
                            "tilDato": "2025-01-01"
                        }
                    """
                        .trimIndent()
                )
                .post("/api/rapport/v1")
                .then()
                .assertThat()
                .statusCode(HttpStatusCode.BadRequest.value)
                .extract()
                .response()!!
        assertThat(response.body().asString()).contains("fraDato kan ikke være etter tilDato")
    }

    @Test
    fun `POST _api_rapport_v1 (uten søkekriterier i body) svarer riktig`() {
        TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
        val response =
            client().body("{}").post("/api/rapport/v1").then().assertThat().statusCode(HttpStatusCode.OK.value).extract().response()!!
        assertThatJson(response.body().prettyPrint()).isEqualTo("[]")
    }

    @Test
    fun `POST _api_rapport_v1 (for 2024) svarer riktig`() {
        TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
        val response =
            client()
                .body(
                    """
                        {
                            "aar": 2024
                         }
                    """
                        .trimIndent()
                )
                .post("/api/rapport/v1")
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
                            "id": 6,
                            "bestillingId": 6,
                            "orgNr": "456789012",
                            "type": "K27",
                            "datoValutert": "2024-01-01",
                            "opprettet": "2023-12-31T23:13:54Z",
                            "arkivert": null
                        }
                    ]
                """
                    .trimIndent()
            )
    }

    @Test
    fun `POST _api_rapport_v1 (med fraDato lik tilDato) svarer riktig`() {
        TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
        val response =
            client()
                .body(
                    """
                        {
                            "fraDato": "2023-01-01",
                            "tilDato": "2023-01-01"
                        }
                    """
                        .trimIndent()
                )
                .post("/api/rapport/v1")
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
                            "id": 1,
                            "bestillingId": 1,
                            "orgNr": "123456789",
                            "type": "K27",
                            "datoValutert": "2023-01-01",
                            "opprettet": "2022-12-31T23:45:15Z",
                            "arkivert": null
                        },
                        {
                            "id": 2,
                            "bestillingId": 2,
                            "orgNr": "123456789",
                            "type": "T14",
                            "datoValutert": "2023-01-01",
                            "opprettet": "2023-01-01T08:37:52Z",
                            "arkivert": null
                        }
                    ]
                """
                    .trimIndent()
            )
    }

    @Test
    fun `POST _api_rapport_v1 (for perioden 2023-11-01 - 2023-12-31) svarer riktig`() {
        TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
        val response =
            client()
                .body(
                    """
                        {
                            "fraDato": "2023-01-01",
                            "tilDato": "2023-12-31"
                         }
                    """
                        .trimIndent()
                )
                .post("/api/rapport/v1")
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
                            "id": 1,
                            "bestillingId": 1,
                            "orgNr": "123456789",
                            "type": "K27",
                            "datoValutert": "2023-01-01",
                            "opprettet": "2022-12-31T23:45:15Z",
                            "arkivert": null
                        },
                        {
                            "id": 2,
                            "bestillingId": 2,
                            "orgNr": "123456789",
                            "type": "T14",
                            "datoValutert": "2023-01-01",
                            "opprettet": "2023-01-01T08:37:52Z",
                            "arkivert": null
                        },
                        {
                            "id": 3,
                            "bestillingId": 3,
                            "orgNr": "234567890",
                            "type": "K27",
                            "datoValutert": "2023-11-01",
                            "opprettet": "2023-11-01T10:57:21Z",
                            "arkivert": null
                        },
                        {
                            "id": 5,
                            "bestillingId": 5,
                            "orgNr": "456789012",
                            "type": "K27",
                            "datoValutert": "2023-12-31",
                            "opprettet": "2023-12-31T22:58:27Z",
                            "arkivert": null
                        },
                        {
                            "id": 6,
                            "bestillingId": 6,
                            "orgNr": "456789012",
                            "type": "K27",
                            "datoValutert": "2024-01-01",
                            "opprettet": "2023-12-31T23:13:54Z",
                            "arkivert": null
                        }
                    ]
                """
                    .trimIndent()
            )
    }

    @Test
    fun `POST _api_rapport_v1 (for perioden 2023-11-01 - 2023-12-31, inkludert arkiverte) svarer riktig`() {
        TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
        val response =
            client()
                .body(
                    """
                        {
                            "fraDato": "2023-11-01",
                            "tilDato": "2023-12-31",
                            "inkluderArkiverte": true
                         }
                    """
                        .trimIndent()
                )
                .post("/api/rapport/v1")
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
                            "id": 3,
                            "bestillingId": 3,
                            "orgNr": "234567890",
                            "type": "K27",
                            "datoValutert": "2023-11-01",
                            "opprettet": "2023-11-01T10:57:21Z",
                            "arkivert": null
                        },
                        {
                            "id": 4,
                            "bestillingId": 4,
                            "orgNr": "345678901",
                            "type": "K27",
                            "datoValutert": "2023-11-01",
                            "opprettet": "2023-11-01T10:57:21Z",
                            "arkivert": "2023-11-15T07:14:41Z"
                        },
                        {
                            "id": 5,
                            "bestillingId": 5,
                            "orgNr": "456789012",
                            "type": "K27",
                            "datoValutert": "2023-12-31",
                            "opprettet": "2023-12-31T22:58:27Z",
                            "arkivert": null
                        },
                        {
                            "id": 6,
                            "bestillingId": 6,
                            "orgNr": "456789012",
                            "type": "K27",
                            "datoValutert": "2024-01-01",
                            "opprettet": "2023-12-31T23:13:54Z",
                            "arkivert": null
                        }
                    ]
                """
                    .trimIndent()
            )
    }

    @Test
    fun `POST _api_rapport_v1 (for spesifikt orgnr i 2023) svarer riktig`() {
        TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
        val response =
            client()
                .body(
                    """
                        {
                            "aar": 2023,
                            "orgnr": "234567890"
                         }
                    """
                        .trimIndent()
                )
                .post("/api/rapport/v1")
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
                            "id": 3,
                            "bestillingId": 3,
                            "orgNr": "234567890",
                            "type": "K27",
                            "datoValutert": "2023-11-01",
                            "opprettet": "2023-11-01T10:57:21Z",
                            "arkivert": null
                        }
                    ]
                """
                    .trimIndent()
            )
    }

    @Test
    fun `POST _api_rapport_v1 (for spesifikk rapport-type i 2023) svarer riktig`() {
        val response =
            client()
                .body(
                    """
                        {
                            "aar": 2023,
                            "rapportTyper": ["T14"]
                         }
                    """
                        .trimIndent()
                )
                .post("/api/rapport/v1")
                .then()
                .assertThat()
                .statusCode(HttpStatusCode.OK.value)
                .extract()
                .response()!!
        TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
        assertThatJson(response.body().prettyPrint())
            .isEqualTo(
                """
                    [
                        {
                            "id": 2,
                            "bestillingId": 2,
                            "orgNr": "123456789",
                            "type": "T14",
                            "datoValutert": "2023-01-01",
                            "opprettet": "2023-01-01T08:37:52Z",
                            "arkivert": null
                        }
                    ]
                """
                    .trimIndent()
            )
    }

    @Test
    fun `GET _api_rapport_v1_$id (for id som ikke finnes) gir feilmelding`() {
        TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
        val NON_EXISTENT_ID = 4711
        client()
            .get("/api/rapport/v1/$NON_EXISTENT_ID")
            .then()
            .assertThat()
            .statusCode(HttpStatusCode.NotFound.value)
            .extract()
            .response()!!
    }

    @Test
    fun `GET _api_rapport_v1_$id (for id som finnes) svarer riktig`() {
        TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
        val response = client().get("/api/rapport/v1/2").then().assertThat().statusCode(HttpStatusCode.OK.value).extract().response()!!
        assertThatJson(response.body().prettyPrint())
            .isEqualTo(
                """
                    {
                        "id": 2,
                        "bestillingId": 2,
                        "orgNr": "123456789",
                        "type": "T14",
                        "datoValutert": "2023-01-01",
                        "opprettet": "2023-01-01T08:37:52Z",
                        "arkivert": null
                    }
                """
                    .trimIndent()
            )
    }

    @Test
    fun `GET _api_rapport_v1_$id_innhold (for id som ikke finnes) gir feilmelding`() {
        TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
        val NON_EXISTENT_ID = 4711
        client()
            .accept(ContentType.Text.CSV.toString())
            .get("/api/rapport/v1/$NON_EXISTENT_ID/innhold")
            .then()
            .assertThat()
            .statusCode(HttpStatusCode.NotFound.value)
            .extract()
            .response()!!
    }

    @Test
    fun `GET _api_rapport_v1_$id_innhold (for CSV-variant av id som finnes) svarer riktig`() {
        TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
        val response =
            client()
                .accept(ContentType.Text.CSV.toString())
                .get("/api/rapport/v1/2/innhold")
                .then()
                .assertThat()
                .statusCode(HttpStatusCode.OK.value)
                .extract()
                .response()!!
        assertThat(response.body().asByteArray())
            .isEqualTo(
                buildByteString {
                        append("CSV".toByteArray())
                        append(0.toByte())
                        append("2".toByteArray())
                    }
                    .toByteArray()
            )
    }

    @Test
    fun `GET _api_rapport_v1_$id_innhold (for PDF-variant av id som finnes) svarer riktig`() {
        TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
        val response =
            client()
                .accept(ContentType.Application.Pdf.toString())
                .get("/api/rapport/v1/2/innhold")
                .then()
                .assertThat()
                .statusCode(HttpStatusCode.OK.value)
                .extract()
                .response()!!
        assertThat(response.body().asByteArray())
            .isEqualTo(
                buildByteString {
                        append("PDF".toByteArray())
                        append(0.toByte())
                        append("2".toByteArray())
                    }
                    .toByteArray()
            )
    }

    @Test
    fun `GET _api_rapport_v1_$id_innhold (for ukjent variant av id som finnes) gir feilmelding`() {
        TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
        // Ønsker å teste at requests som ber om en respons-type som ikke tillates i OpenAPI-specen vår likevel ender opp med å gi en saklig
        // (feil-)respons
        client(validationFilter = null)
            .accept(ContentType.Text.Plain.toString())
            .get("/api/rapport/v1/2/innhold")
            .then()
            .assertThat()
            .statusCode(HttpStatusCode.NotAcceptable.value)
            .extract()
            .response()!!
    }

    @Test
    fun `PUT _api_rapport_v1_$id_arkiver (for id som ikke finnes) gir feilmelding`() {
        TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())
        val NON_EXISTENT_ID = 4711
        client()
            .put("/api/rapport/v1/$NON_EXISTENT_ID/arkiver")
            .then()
            .assertThat()
            .statusCode(HttpStatusCode.NotFound.value)
            .extract()
            .response()!!
    }

    @Test
    fun `PUT _api_rapport_v1_$id_arkiver (for id som finnes) svarer riktig`() {
        TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())

        assertThatJson(
                client()
                    .get("/api/rapport/v1/2")
                    .then()
                    .assertThat()
                    .statusCode(HttpStatusCode.OK.value)
                    .extract()
                    .response()!!
                    .body()
                    .prettyPrint()
            )
            .isEqualTo(
                """
                    {
                        "id": 2,
                        "bestillingId": 2,
                        "orgNr": "123456789",
                        "type": "T14",
                        "datoValutert": "2023-01-01",
                        "opprettet": "2023-01-01T08:37:52Z",
                        "arkivert": null
                    }
                """
                    .trimIndent()
            )

        client().put("/api/rapport/v1/2/arkiver").then().assertThat().statusCode(HttpStatusCode.NoContent.value).extract().response()!!

        assertThatJson(
                client()
                    .get("/api/rapport/v1/2")
                    .then()
                    .assertThat()
                    .statusCode(HttpStatusCode.OK.value)
                    .extract()
                    .response()!!
                    .body()
                    .prettyPrint()
            )
            .isEqualTo(
                """
                    {
                        "id": 2,
                        "bestillingId": 2,
                        "orgNr": "123456789",
                        "type": "T14",
                        "datoValutert": "2023-01-01",
                        "opprettet": "2023-01-01T08:37:52Z",
                        "arkivert": "2025-11-22T12:00:00Z"
                    }
                """
                    .trimIndent()
            )

        client()
            .queryParam("arkivert", "false")
            .put("/api/rapport/v1/2/arkiver")
            .then()
            .assertThat()
            .statusCode(HttpStatusCode.NoContent.value)
            .extract()
            .response()!!

        assertThatJson(
                client()
                    .get("/api/rapport/v1/2")
                    .then()
                    .assertThat()
                    .statusCode(HttpStatusCode.OK.value)
                    .extract()
                    .response()!!
                    .body()
                    .prettyPrint()
            )
            .isEqualTo(
                """
                    {
                        "id": 2,
                        "bestillingId": 2,
                        "orgNr": "123456789",
                        "type": "T14",
                        "datoValutert": "2023-01-01",
                        "opprettet": "2023-01-01T08:37:52Z",
                        "arkivert": null
                    }
                """
                    .trimIndent()
            )
    }
}
