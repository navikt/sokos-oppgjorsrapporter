@file:UseSerializers(UuidSerializer::class)

package no.nav.sokos.oppgjorsrapporter.dialogporten.domene

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import no.nav.helsearbeidsgiver.utils.json.serializer.UuidSerializer

@Serializable
data class Transmission(
    val type: TransmissionType,
    val extendedType: String,
    val externalReference: String?,
    val sender: Sender,
    val content: Content,
    val relatedTransmissionId: UUID? = null,
    val attachments: List<Attachment>? = null,
) {
    @Serializable data class Sender(val actorType: String)

    @Serializable
    enum class TransmissionType {
        // For general information, not related to any submissions
        Information,

        // Feedback/receipt accepting a previous submission
        Acceptance,

        // Feedback/error message rejecting a previous submission
        Rejection,

        // Question/request for more information
        Request,

        // Notification of important events
        Alert,

        // Formal decision related to a previous submission
        Decision,

        // Submission of data, e.g., a form submission
        Submission,

        // Update to previously submitted data
        Correction,
    }
}
