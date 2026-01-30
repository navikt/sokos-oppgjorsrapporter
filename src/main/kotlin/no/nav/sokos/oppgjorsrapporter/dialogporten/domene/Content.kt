package no.nav.sokos.oppgjorsrapporter.dialogporten.domene

import kotlinx.serialization.Serializable

@Serializable
data class Content(val title: Value, val summary: Value? = null, val additionalInfo: Value? = null) {
    companion object {
        fun create(title: String, summary: String?, additionalInfo: String?): Content =
            Content(title.toContentValue(), summary?.toContentValue(), additionalInfo?.toContentValue("text/markdown"))
    }

    @Serializable
    data class Value(val value: List<Item>, val mediaType: String) {
        @Serializable data class Item(val value: String, val languageCode: String = "nb")
    }
}

fun String.toContentValue(mediaType: String = "text/plain"): Content.Value =
    Content.Value(value = listOf(Content.Value.Item(this)), mediaType)
