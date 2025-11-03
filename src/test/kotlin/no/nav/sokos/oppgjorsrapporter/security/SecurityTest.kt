package no.nav.sokos.oppgjorsrapporter.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import no.nav.security.mock.oauth2.withMockOAuth2Server
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.withTestApplication

class SecurityTest :
    FunSpec({
        context("test-container") {
            val container = TestContainer.postgres

            test("test http GET endepunkt uten token bør returnere 401") {
                withMockOAuth2Server {
                    withTestApplication(container, TestContainer.mq) {
                        val response = client.get("/api/rapport/v1")
                        response.status shouldBe HttpStatusCode.Unauthorized
                    }
                }
            }

            test("test http GET endepunkt med token bør returnere 200") {
                withMockOAuth2Server {
                    val mockOAuth2Server = this
                    withTestApplication(container, TestContainer.mq) {
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
                            client.get("/api/rapport/v1?orgnr=987654321") {
                                header(
                                    "Authorization",
                                    "Bearer ${mockOAuth2Server.tokenFromDefaultProvider(mapOf("NAVident" to "user", "groups" to listOf("group")))}",
                                )
                                contentType(ContentType.Application.Json)
                            }

                        response.status shouldBe HttpStatusCode.OK
                    }
                }
            }
        }
    })

private fun MockOAuth2Server.tokenFromDefaultProvider(claims: Map<String, Any> = emptyMap()): String =
    issueToken(issuerId = "default", clientId = "default", tokenCallback = DefaultOAuth2TokenCallback(claims = claims)).serialize()
