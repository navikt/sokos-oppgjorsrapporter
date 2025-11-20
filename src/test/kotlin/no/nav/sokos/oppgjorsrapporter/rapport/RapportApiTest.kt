package no.nav.sokos.oppgjorsrapporter.rapport

import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.kotest.extensions.testcontainers.toDataSource
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.port
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.di.dependencies
import io.restassured.RestAssured
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.javacrumbs.jsonunit.assertj.assertThatJson
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.OAuth2Config
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.TestUtil.testApplicationConfig
import no.nav.sokos.oppgjorsrapporter.auth.tokenFromDefaultProvider
import no.nav.sokos.oppgjorsrapporter.module
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class FullTestServer {
    protected val dbContainer = TestContainer.postgres

    private lateinit var _mockOAuth2Server: MockOAuth2Server
    protected val mockOAuth2Server: MockOAuth2Server
        get() = _mockOAuth2Server

    private lateinit var _server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>

    protected val embeddedServerPort: Int by lazy { runBlocking { _server.engine.resolvedConnectors().single().port } }

    @BeforeAll
    fun init() {
        _mockOAuth2Server = MockOAuth2Server(OAuth2Config()).apply { start() }
        _server =
            embeddedServer(Netty, 0) {
                    dependencyOverrides()
                    module(testApplicationConfig(dbContainer = dbContainer, mqContainer = null, server = _mockOAuth2Server))
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

    open fun Application.dependencyOverrides() {
        dependencies {}
    }
}

class RapportApiTest : FullTestServer() {
    override protected val defaultClaims: Map<String, Any> = mapOf("NAVident" to "user", "groups" to listOf("group"))

    val openApiValidationFilter = OpenApiValidationFilter("openapi/rapport-v1.yaml")

    fun client() =
        RestAssured.given()
            .filter(openApiValidationFilter)
            .header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            .header(HttpHeaders.Authorization, "Bearer ${tokenFromDefaultProvider()}")
            .port(embeddedServerPort)

    @Test
    fun `svarer riktig på GET _api_rapport_v1 (uten query params)`() {
        TestUtil.loadDataSet("db/RapportServiceTest/multiple.sql", dbContainer.toDataSource())
        val response = client().get("/api/rapport/v1").then().assertThat().statusCode(HttpStatusCode.OK.value).extract().response()!!
        assertThatJson(response.body().prettyPrint()).isEqualTo("[]")
    }

    @Test
    fun `svarer riktig på GET _api_rapport_v1 (for 2024)`() {
        TestUtil.loadDataSet("db/RapportServiceTest/multiple.sql", dbContainer.toDataSource())
        val response =
            client()
                .queryParam("aar", "2024")
                .get("/api/rapport/v1")
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
                            "tittel": "K27 for Luskende Ulv 2024-01-01",
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
    fun `svarer riktig på GET _api_rapport_v1 (for spesifikk rapport-type i 2023)`() {
        val response =
            client()
                .queryParam("aar", "2023")
                .queryParam("rapportType", "T14")
                .get("/api/rapport/v1")
                .then()
                .assertThat()
                .statusCode(HttpStatusCode.OK.value)
                .extract()
                .response()!!
        TestUtil.loadDataSet("db/RapportServiceTest/multiple.sql", dbContainer.toDataSource())
        assertThatJson(response.body().prettyPrint())
            .isEqualTo(
                """
                    [
                        {
                            "id": 2,
                            "bestillingId": 2,
                            "orgNr": "123456789",
                            "type": "T14",
                            "tittel": "T14 for Skinnende Padde 2023-01-01",
                            "datoValutert": "2023-01-01",
                            "opprettet": "2023-01-01T08:37:52Z",
                            "arkivert": null
                        }
                    ]
                """
                    .trimIndent()
            )
    }
}
