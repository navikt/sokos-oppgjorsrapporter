package no.nav.sokos.oppgjorsrapporter.dialogporten.domene

import io.ktor.http.ContentType
import kotlinx.serialization.Serializable

@Serializable
data class Attachment(val displayName: List<ContentValueItem>, val urls: List<Url>) {
    @Serializable
    data class Url(val url: String, val mediaType: String, val consumerType: AttachmentUrlConsumerType) {
        @Serializable
        enum class AttachmentUrlConsumerType {
            Gui,
            Api,
        }
    }
}

fun createAttachment(
    displayName: String,
    url: String,
    mediaType: String,
    consumerType: Attachment.Url.AttachmentUrlConsumerType,
): Attachment =
    Attachment(
        displayName = listOf(ContentValueItem(displayName)),
        urls = listOf(Attachment.Url(url = url, mediaType = mediaType, consumerType = consumerType)),
    )

fun createApiAttachment(displayName: String, url: String, mediaType: String = ContentType.Application.Json.toString()): Attachment =
    createAttachment(
        displayName = displayName,
        url = url,
        mediaType = mediaType,
        consumerType = Attachment.Url.AttachmentUrlConsumerType.Api,
    )

fun createGuiAttachment(displayName: String, url: String, mediaType: String = ContentType.Text.Html.toString()): Attachment =
    createAttachment(
        displayName = displayName,
        url = url,
        mediaType = mediaType,
        consumerType = Attachment.Url.AttachmentUrlConsumerType.Gui,
    )
