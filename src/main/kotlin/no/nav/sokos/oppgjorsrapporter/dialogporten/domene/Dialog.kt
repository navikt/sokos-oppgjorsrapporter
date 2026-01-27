package no.nav.sokos.oppgjorsrapporter.dialogporten.domene

import kotlinx.serialization.Serializable

@Serializable
data class Dialog(
    val idempotentKey: String? = null,
    val serviceResource: String,
    val party: String,
    val progress: Int? = null,
    val externalReference: String,
    val isApiOnly: Boolean = true,
    val status: Status? = null,
    val content: Content,
    val transmissions: List<Transmission>,
    val guiActions: List<GuiAction>? = null,
    val apiActions: List<ApiAction>? = null,
) {
    init {
        progress?.let { require(it >= 1 && it <= 100) { "'progress' må være i intervallet 1-100" } }
    }

    @Serializable
    enum class Status {
        New,
        InProgress,
        Draft,
        Sent,
        RequiresAttention,
        Completed,
        NotApplicable,
        Awaiting,
    }
}

@Serializable
sealed interface PatchOperation {
    val op: Operation
    val path: String
}

@Serializable
enum class Operation {
    add,
    remove,
    replace,
    move,
    copy,
    test,
}

@Serializable
data class SetSystemLabel(val value: SystemLabel, override val op: Operation = Operation.add, override val path: String = "/systemLabel") :
    PatchOperation {
    @Serializable
    enum class SystemLabel {
        Default,
        Archive,
    }
}
