package no.nav.sokos.oppgjorsrapporter.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import no.nav.security.mock.oauth2.withMockOAuth2Server
import no.nav.sokos.oppgjorsrapporter.API_BASE_PATH
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.config.CompositeApplicationConfig
import no.nav.sokos.oppgjorsrapporter.domain.DummyDomain
import no.nav.sokos.oppgjorsrapporter.module
import no.nav.sokos.oppgjorsrapporter.service.DummyService

val dummyService: DummyService = mockk()

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
                        val response = client.get("$API_BASE_PATH/hello")
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

                        every { dummyService.sayHello() } returns DummyDomain("Hello")

                        val response =
                            client.get("$API_BASE_PATH/hello") {
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
