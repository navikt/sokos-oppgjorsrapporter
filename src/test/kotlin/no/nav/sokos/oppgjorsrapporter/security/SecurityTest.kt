package no.nav.sokos.oppgjorsrapporter.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import no.nav.security.mock.oauth2.withMockOAuth2Server
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.config.CompositeApplicationConfig
import no.nav.sokos.oppgjorsrapporter.module

class SecurityTest :
    FunSpec({
        context("test-container") {
            val container = TestContainer.postgres

            test("test http GET endepunkt uten token bør returnere 401") {
                withMockOAuth2Server {
                    testApplication {
                        environment {
                            config =
                                CompositeApplicationConfig(
                                    TestUtil.getOverrides(container),
                                    authConfigOverrides(),
                                    ApplicationConfig("application.conf"),
                                )
                        }
                        application { module() }
                        val response = client.get("/api/rapport/v1/rapport")
                        response.status shouldBe HttpStatusCode.Unauthorized
                    }
                }
            }

            test("test http GET endepunkt med token bør returnere 200") {
                withMockOAuth2Server {
                    val mockOAuth2Server = this
                    testApplication {
                        environment {
                            config =
                                CompositeApplicationConfig(
                                    TestUtil.getOverrides(container),
                                    authConfigOverrides(),
                                    ApplicationConfig("application.conf"),
                                )
                        }
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
                        application { module() }

                        val response =
                            client.get("/api/rapport/v1/rapport?orgnr=987654321") {
                                header("Authorization", "Bearer ${mockOAuth2Server.tokenFromDefaultProvider()}")
                                contentType(ContentType.Application.Json)
                            }

                        response.status shouldBe HttpStatusCode.OK
                    }
                }
            }
        }
    })

fun MockOAuth2Server.authConfigOverrides() =
    MapApplicationConfig().apply {
        put("AZURE_APP_CLIENT_ID", "default")
        put("AZURE_APP_WELL_KNOWN_URL", wellKnownUrl("default").toString())
    }

private fun MockOAuth2Server.tokenFromDefaultProvider() =
    issueToken(issuerId = "default", clientId = "default", tokenCallback = DefaultOAuth2TokenCallback()).serialize()
