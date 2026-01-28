package no.nav.sokos.oppgjorsrapporter.dialogporten

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import java.net.URI
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import mu.KLogger
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.HttpClientSetup
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.config.commonJsonConfig
import no.nav.sokos.oppgjorsrapporter.dialogporten.domene.Content
import no.nav.sokos.oppgjorsrapporter.dialogporten.domene.CreateDialogRequest
import no.nav.sokos.oppgjorsrapporter.dialogporten.domene.Dialog
import no.nav.sokos.oppgjorsrapporter.dialogporten.domene.PatchOperation
import no.nav.sokos.oppgjorsrapporter.dialogporten.domene.SetSystemLabel

class DialogportenClient(baseUrl: URI, private val httpClient: HttpClient) {
    private val dialogportenUrl = baseUrl.resolve("/dialogporten/api/v1/serviceowner/dialogs").toString()
    private val logger: KLogger = KotlinLogging.logger {}

    suspend fun opprettDialog(request: CreateDialogRequest): UUID {
        val dialog = byggDialogFraRequest(request)
        return runCatching {
                val response =
                    httpClient
                        .post(dialogportenUrl) {
                            contentType(ContentType.Application.Json)
                            accept(ContentType.Application.Json)
                            setBody(dialog)
                        }
                        .body<String>()
                UUID.fromString(response.removeSurrounding("\""))
            }
            .getOrElse { e -> logAndThrow("Feil ved oppretting av dialog", e) }
    }

    suspend fun arkiverDialog(dialogId: UUID, arkivert: Boolean = false) {
        updateDialog(
            dialogId,
            listOf(SetSystemLabel(if (arkivert) SetSystemLabel.SystemLabel.Archive else SetSystemLabel.SystemLabel.Default)),
        )
    }

    private suspend fun updateDialog(dialogId: UUID, patchOperations: List<PatchOperation>) {
        runCatching {
                httpClient.patch("$dialogportenUrl/$dialogId") {
                    header(HttpHeaders.ContentType, "application/json")
                    //                    header(HttpHeaders.ContentType, "application/json-patch+json")
                    setBody(patchOperations)
                }
            }
            .onFailure { e -> logAndThrow("Feil ved oppdatering av dialog", e) }
    }

    private fun logAndThrow(msg: String, e: Throwable): Nothing {
        logger.error(msg)
        logger.error(TEAM_LOGS_MARKER, e) { msg }
        throw DialogportenException(msg)
    }

    private fun byggDialogFraRequest(createDialogRequest: CreateDialogRequest): Dialog =
        Dialog(
            idempotentKey = createDialogRequest.idempotentKey,
            serviceResource = "urn:altinn:resource:${createDialogRequest.rapportType.altinnRessurs}",
            party = "urn:altinn:organization:identifier-no:${createDialogRequest.orgnr.raw}",
            progress = 100,
            externalReference = createDialogRequest.externalReference,
            isApiOnly = createDialogRequest.isApiOnly,
            content = Content.create(title = createDialogRequest.title, summary = createDialogRequest.summary),
            transmissions = createDialogRequest.transmissions,
            guiActions = createDialogRequest.guiActions,
            apiActions = createDialogRequest.apiActions,
        )

    companion object : HttpClientSetup {
        @OptIn(ExperimentalSerializationApi::class)
        override val jsonConfig: Json =
            Json(commonJsonConfig) {
                explicitNulls = false
                classDiscriminatorMode = ClassDiscriminatorMode.NONE
            }
    }
}

class DialogportenException(message: String) : Exception(message)
