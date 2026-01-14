package no.nav.sokos.oppgjorsrapporter.dialogporten.domene

import kotlinx.serialization.Serializable

@Serializable
sealed class PatchOperation {
    abstract val op: String
    abstract val path: String
}

@Serializable
data class AddTransmissions(val value: List<Transmission>, override val op: String = "add", override val path: String = "/transmissions") :
    PatchOperation()

@Serializable
data class AddStatus(val value: DialogStatus, override val op: String = "add", override val path: String = "/status") : PatchOperation()

@Serializable
data class AddApiActions(val value: List<ApiAction>, override val op: String = "add", override val path: String = "/apiActions") :
    PatchOperation()

@Serializable
data class AddGuiActions(val value: List<GuiAction>, override val op: String = "add", override val path: String = "/guiActions") :
    PatchOperation()

@Serializable data class RemoveApiAction(override val op: String = "remove", override val path: String = "/apiActions") : PatchOperation()

@Serializable data class RemoveGuiActions(override val op: String = "remove", override val path: String = "/guiActions") : PatchOperation()
