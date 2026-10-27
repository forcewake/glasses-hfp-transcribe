package com.glasses.hfptranscribe.audio

data class ListedDevice(
    val group: String,
    val id: Int,
    val typeName: String,
    val source: Boolean,
    val sink: Boolean,
    val address: String,
    val productName: String,
)

data class OtherRecording(
    val packageName: String,
    val sourceName: String,
)

fun formatRouteDiagnostics(
    step: String,
    modeBefore: String,
    modeNow: String,
    scoOn: Boolean,
    activeCommunicationDevice: String,
    devices: List<ListedDevice>,
    otherRecordings: List<OtherRecording>,
    routeNote: String?,
): String {
    return buildString {
        appendLine("Could not open the glasses microphone.")
        appendLine("Step: $step")
        appendLine("Mode before: $modeBefore")
        appendLine("Mode now: $modeNow")
        appendLine("isBluetoothScoOn: $scoOn")
        appendLine("Active communication device: $activeCommunicationDevice")
        if (!routeNote.isNullOrBlank()) appendLine("Route note: $routeNote")
        if (otherRecordings.isEmpty()) {
            appendLine("Other recordings: none")
        } else {
            appendLine("Other recordings:")
            otherRecordings.forEach { recording ->
                appendLine("  ${recording.packageName} source=${recording.sourceName}")
            }
        }
        appendLine("Inputs:")
        appendDevices(devices.filter { it.group == "input" })
        appendLine("Communication devices:")
        appendDevices(devices.filter { it.group == "communication" })
    }.trimEnd()
}

private fun StringBuilder.appendDevices(devices: List<ListedDevice>) {
    if (devices.isEmpty()) {
        appendLine("  none")
        return
    }
    devices.forEach { device ->
        val roles = listOfNotNull(
            if (device.source) "source" else null,
            if (device.sink) "sink" else null,
        ).joinToString("+").ifEmpty { "unknown-role" }
        val address = device.address.ifBlank { "-" }
        val name = device.productName.ifBlank { "-" }
        appendLine("  id=${device.id} ${device.typeName} $roles address=$address name=$name")
    }
}
