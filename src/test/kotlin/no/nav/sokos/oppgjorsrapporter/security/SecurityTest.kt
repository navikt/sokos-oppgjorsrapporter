package no.nav.sokos.oppgjorsrapporter.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import no.nav.security.mock.oauth2.withMockOAuth2Server
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.auth.tokenFromDefaultProvider
import no.nav.sokos.oppgjorsrapporter.rapport.Api
import no.nav.sokos.oppgjorsrapporter.withTestApplication

class SecurityTest :
    FunSpec({
        context("test-container") {
            val container = TestContainer.postgres

            test("test http POST endepunkt uten token bør returnere 401") {
                withMockOAuth2Server {
                    withTestApplication(dbContainer = container) {
                        val response = client.post("/api/rapport/v1")
                        response.status shouldBe HttpStatusCode.Unauthorized
                    }
                }
            }

            test("test http POST endepunkt med token bør returnere 200") {
                withMockOAuth2Server {
                    val mockOAuth2Server = this
                    withTestApplication(dbContainer = container) {
                        val client = createClient {
                            install(ContentNegotiation) {
                                json(
                                    Json {
                                        prettyPrint = true
                                        ignoreUnknownKeys = true
                                        encodeDefaults = true
                                        explicitNulls = false
                                    }
                                )
                            }
                        }
                        val response =
                            client.post("/api/rapport/v1") {
                                header(
                                    "Authorization",
                                    "Bearer ${mockOAuth2Server.tokenFromDefaultProvider(mapOf("NAVident" to "user", "groups" to listOf("group")))}",
                                )
                                contentType(ContentType.Application.Json)
                                setBody(Api.RapportListeRequest(orgnr = "987654321"))
                            }

                        response.status shouldBe HttpStatusCode.OK
                    }
                }
            }
        }
    })
