package com.glasses.hfptranscribe.audio

/**
 * Identity of one Bluetooth SCO port. Input and output ports usually have
 * different ids and the same address or product name.
 */
data class ScoEndpoint(
    val id: Int,
    val address: String,
    val productName: String,
)

/**
 * Picks the communication-device SCO sink that belongs to a selected headset.
 *
 * [AudioManager.setCommunicationDevice] only accepts a device from
 * `availableCommunicationDevices`, which is a sink. The matching microphone
 * is a different [AudioDeviceInfo] and is used only as the capture preference.
 */
object ScoSinkMatcher {
    fun match(selected: ScoEndpoint, peers: List<ScoEndpoint>): ScoEndpoint? {
        if (peers.isEmpty()) return null
        peers.firstOrNull { it.id == selected.id }?.let { return it }
        val wanted = normalizeAddress(selected.address)
        if (wanted.isNotEmpty()) {
            peers.firstOrNull { normalizeAddress(it.address) == wanted }?.let { return it }
        }
        peers.firstOrNull { endpoint ->
            namesMatch(selected.productName, endpoint.productName) &&
                addressesCompatible(wanted, normalizeAddress(endpoint.address))
        }?.let { return it }
        if (wanted.isNotEmpty() && peers.any { normalizeAddress(it.address).isNotEmpty() }) {
            return null
        }
        return peers.singleOrNull()
    }

    fun normalizeAddress(address: String): String {
        return address.trim().lowercase().filter { it.isLetterOrDigit() }
    }

    private fun addressesCompatible(selected: String, peer: String): Boolean {
        if (selected.isEmpty() || peer.isEmpty()) return true
        return selected == peer
    }

    private fun namesMatch(left: String, right: String): Boolean {
        val a = left.trim()
        val b = right.trim()
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.equals(b, ignoreCase = true)) return true
        if (minOf(a.length, b.length) < 4) return false
        return a.contains(b, ignoreCase = true) || b.contains(a, ignoreCase = true)
    }
}
