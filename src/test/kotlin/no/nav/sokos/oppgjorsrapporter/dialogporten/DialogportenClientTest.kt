@file:OptIn(ExperimentalUuidApi::class)

package no.nav.sokos.oppgjorsrapporter.dialogporten

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.SimpleRequest
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.json
import java.net.URI
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.test.runTest
import net.javacrumbs.jsonunit.assertj.assertThatJson
import no.nav.sokos.oppgjorsrapporter.dialogporten.domene.Action
import no.nav.sokos.oppgjorsrapporter.dialogporten.domene.Content
import no.nav.sokos.oppgjorsrapporter.dialogporten.domene.CreateDialogRequest
import no.nav.sokos.oppgjorsrapporter.dialogporten.domene.GuiAction
import no.nav.sokos.oppgjorsrapporter.rapport.OrgNr
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class DialogportenClientTest {
    val dialogportenApiValidator: OpenApiInteractionValidator =
        OpenApiInteractionValidator.createForSpecificationUrl("https://platform.tt02.altinn.no/dialogporten/swagger/v1/swagger.json")
            .build()

    val baseUrl = URI("dialogporten-v1-swagger.json")

    private fun mockHttpClient(
        responseStatus: HttpStatusCode,
        responseBody: String,
        extraRequestChecks: (HttpRequestData, String) -> Unit = { _, _ -> },
    ) =
        MockEngine { req ->
                dialogportenApiValidator.checkRequest(req, extraRequestChecks)
                respond(content = responseBody, status = responseStatus, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            .let { engine ->
                HttpClient(engine) {
                    install(ContentNegotiation) { json(DialogportenHttpClientSetup.jsonConfig) }
                    install(Auth) { bearer { loadTokens { BearerTokens("dummy-token", null) } } }
                }
            }

    @Test
    fun `opprettDialog skal sende en velformet request`() = runTest {
        val opprettetDialogGuid = Uuid.generateV7()
        val httpClient =
            mockHttpClient(HttpStatusCode.Created, "\"$opprettetDialogGuid\"") { _, body ->
                assertThatJson(body)
                    .isEqualTo(
                        """
                        {
                          "content": {
                            "title": {
                              "mediaType": "text/plain",
                              "value": [
                                {
                                  "languageCode": "nb",
                                  "value": "tittel"
                                }
                              ]
                            },
                            "summary": {
                              "mediaType": "text/plain",
                              "value": [
                                {
                                  "languageCode": "nb",
                                  "value": "oppsummering"
                                }
                              ]
                            }
                          },
                          "externalReference": "extRef",
                          "idempotentKey": "idempotentKey",
                          "isApiOnly": false,
                          "party": "urn:altinn:organization:identifier-no:123456789",
                          "progress": 100,
                          "serviceResource": "urn:altinn:resource:nav_utbetaling_oppgjorsrapport-refusjon-arbeidsgiver",
                          "transmissions": [],
                          "guiActions": [
                            {
                              "title": [
                                {
                                  "value": "Navigate",
                                  "languageCode": "nb"
                                }
                              ],
                              "url": "https://example.com/gui/",
                              "priority": "Primary",
                              "action": "access"
                            }
                          ],
                          "apiActions": []
                        }
                        """
                            .trimIndent()
                    )
            }

        val sut = DialogportenClient(baseUrl, httpClient)

        val request =
            CreateDialogRequest(
                rapportType = RapportType.`ref-arbg`,
                orgnr = OrgNr("123456789"),
                title = "tittel",
                summary = "oppsummering",
                externalReference = "extRef",
                idempotentKey = "idempotentKey",
                isApiOnly = false,
                transmissions = emptyList(),
                guiActions =
                    listOf(
                        GuiAction(
                            title = listOf(Content.Value.Item("Navigate")),
                            url = "https://example.com/gui/",
                            priority = GuiAction.Priority.Primary,
                            action = Action.access,
                        )
                    ),
                apiActions = emptyList(),
            )

        assertThat(sut.opprettDialog(request)).isEqualTo(opprettetDialogGuid.toJavaUuid())
    }

    @Test
    fun `arkiverDialog skal sende en velformet request`() = runTest {
        val dialogGuid = Uuid.generateV7()
        val httpClient =
            mockHttpClient(HttpStatusCode.NoContent, "") { _, body ->
                assertThatJson(body)
                    .isEqualTo(
                        """
                        [
                            { "op": "add", "path": "/systemLabel", "value": "Archive" }
                        ]
                        """
                            .trimIndent()
                    )
            }
        val sut = DialogportenClient(baseUrl, httpClient)

        sut.arkiverDialog(dialogGuid.toJavaUuid(), arkivert = true)
    }
}

fun OpenApiInteractionValidator.checkRequest(reqData: HttpRequestData, extraRequestChecks: (HttpRequestData, String) -> Unit) {
    withClue("${reqData.method} ${reqData.url}") {
        // Feil dersom vi ikke klarer å tolke body som en string
        val bodyAsText =
            (reqData.body as? OutgoingContent.ByteArrayContent)?.let { body -> String(body.bytes()) }
                ?: Assertions.fail("Vet ikke hvordan vi kan sjekke body av type ${reqData.body.javaClass.canonicalName}")

        val report =
            validateRequest(
                SimpleRequest.Builder(reqData.method.value, reqData.url.fullPath)
                    .apply {
                        // Kopier alle headere fra reqData
                        reqData.headers.entries().forEach { entry -> withHeader(entry.key, entry.value) }
                        // Av en eller annen grunn så ser det ikke ut til at "Content-Type" er med i reqData.headers
                        if (reqData.body.contentType != null && !reqData.headers.contains(HttpHeaders.ContentType)) {
                            withHeader(HttpHeaders.ContentType, reqData.body.contentType.toString())
                        }
                        // Kopier body fra bodyAsText
                        withBody(bodyAsText)
                    }
                    .build()
            )
        withClue(report.messages.joinToString("\n")) { report.hasErrors().shouldBeFalse() }
        extraRequestChecks(reqData, bodyAsText)
    }
}
