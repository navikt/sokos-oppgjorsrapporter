package no.nav.sokos.oppgjorsrapporter.dialogporten.domene

import java.util.UUID

abstract class TransmissionRequest {
    abstract val extendedType: String
    abstract val dokumentId: UUID
    abstract val tittel: String
    abstract val sammendrag: String?
    abstract val type: Transmission.TransmissionType
    abstract val relatedTransmissionId: UUID?
    abstract val attachments: List<Attachment>
}

fun TransmissionRequest.toTransmission(): Transmission =
    Transmission(
        type = type,
        extendedType = extendedType,
        externalReference = dokumentId.toString(),
        sender = Transmission.Sender("ServiceOwner"),
        relatedTransmissionId = relatedTransmissionId,
        content = Content.create(title = tittel, summary = sammendrag),
        attachments = attachments,
    )
