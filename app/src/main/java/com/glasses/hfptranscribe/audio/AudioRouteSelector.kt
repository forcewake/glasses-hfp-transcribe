package com.glasses.hfptranscribe.audio

enum class InputKind {
    BLUETOOTH_SCO,
    BUILTIN_MIC,
}

enum class CaptureSource {
    GLASSES,
    PHONE,
}

data class AudioInputCandidate(
    val id: Int,
    val kind: InputKind,
    val productName: String,
)

/**
 * Picks the HFP input the way the Meta glasses docs describe it: a
 * [InputKind.BLUETOOTH_SCO] device from the communication-device list.
 * When several headsets are connected, a product-name match wins.
 */
object AudioRouteSelector {
    val glassesNameHints: List<String> = listOf("ray-ban", "rayban", "meta")

    fun selectGlasses(
        devices: List<AudioInputCandidate>,
        preferredName: String,
    ): AudioInputCandidate? {
        val sco = devices.filter { it.kind == InputKind.BLUETOOTH_SCO }
        if (sco.isEmpty()) return null
        val preferred = preferredName.trim()
        if (preferred.isNotEmpty()) {
            sco.firstOrNull { it.productName.contains(preferred, ignoreCase = true) }
                ?.let { return it }
        }
        sco.firstOrNull { device ->
            val name = device.productName.lowercase()
            glassesNameHints.any { hint -> name.contains(hint) }
        }?.let { return it }
        return sco.first()
    }

    fun selectPhoneMicrophone(devices: List<AudioInputCandidate>): AudioInputCandidate? {
        return devices.firstOrNull { it.kind == InputKind.BUILTIN_MIC }
    }

    fun glassesLabel(productName: String): String {
        val name = productName.trim()
        return if (name.isEmpty()) {
            "Glasses microphone · Bluetooth headset"
        } else {
            "Glasses microphone · $name"
        }
    }

    fun phoneLabel(): String = "Phone microphone"
}
