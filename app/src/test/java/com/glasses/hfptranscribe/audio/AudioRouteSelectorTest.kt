package com.glasses.hfptranscribe.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioRouteSelectorTest {
    @Test
    fun preferredNameBeatsAnEarlierHintMatch() {
        val buds = sco(2, "Pixel Buds")
        val glasses = sco(1, "Meta Ray-Ban Display")
        val selected = AudioRouteSelector.selectGlasses(
            devices = listOf(glasses, buds),
            preferredName = "pixel buds",
        )
        assertEquals(2, selected?.id)
    }

    @Test
    fun hintMatchesWhenThePreferredNameMisses() {
        val car = sco(2, "Car Kit")
        val glasses = sco(1, "Ray-Ban Meta")
        val selected = AudioRouteSelector.selectGlasses(
            devices = listOf(car, glasses),
            preferredName = "nothing",
        )
        assertEquals(1, selected?.id)
    }

    @Test
    fun fallsBackToTheFirstScoDeviceWhenNothingMatches() {
        val car = sco(4, "Car Kit")
        val headset = sco(5, "Headset")
        val selected = AudioRouteSelector.selectGlasses(listOf(car, headset), preferredName = "")
        assertEquals(4, selected?.id)
    }

    @Test
    fun ignoresNonScoDevicesEvenWhenTheNameMatches() {
        val phone = AudioInputCandidate(1, InputKind.BUILTIN_MIC, "Ray-Ban")
        val glasses = sco(2, "Other Headset")
        val selected = AudioRouteSelector.selectGlasses(listOf(phone, glasses), preferredName = "Ray-Ban")
        assertEquals(2, selected?.id)
    }

    @Test
    fun emptyListSelectsNothing() {
        assertNull(AudioRouteSelector.selectGlasses(emptyList(), preferredName = "Ray-Ban"))
    }

    @Test
    fun matchIsCaseInsensitiveAndTrimsThePreferredName() {
        val glasses = sco(7, "RAY-BAN META")
        val selected = AudioRouteSelector.selectGlasses(listOf(glasses), preferredName = "  ray-ban  ")
        assertEquals(7, selected?.id)
    }

    @Test
    fun raybanWithoutAHyphenMatchesTheHint() {
        val glasses = sco(3, "RayBan Stories")
        val selected = AudioRouteSelector.selectGlasses(listOf(glasses), preferredName = "")
        assertEquals(3, selected?.id)
    }

    @Test
    fun firstPreferredMatchWins() {
        val first = sco(1, "Ray-Ban A")
        val second = sco(2, "Ray-Ban B")
        val selected = AudioRouteSelector.selectGlasses(listOf(first, second), preferredName = "Ray-Ban")
        assertEquals(1, selected?.id)
    }

    @Test
    fun phoneMicrophoneIgnoresBluetoothSco() {
        val glasses = sco(1, "Ray-Ban")
        val phone = AudioInputCandidate(9, InputKind.BUILTIN_MIC, "Pixel")
        val selected = AudioRouteSelector.selectPhoneMicrophone(listOf(glasses, phone))
        assertEquals(9, selected?.id)
    }

    @Test
    fun phoneMicrophoneIsNullWhenOnlyAHeadsetIsPresent() {
        assertNull(AudioRouteSelector.selectPhoneMicrophone(listOf(sco(1, "Ray-Ban"))))
    }

    @Test
    fun labelsStayExplicit() {
        assertEquals("Phone microphone", AudioRouteSelector.phoneLabel())
        assertEquals("Phone microphone", AudioRouteSelector.phoneFallbackLabel(headsetConnected = true))
        assertEquals(
            "Phone microphone · no glasses",
            AudioRouteSelector.phoneFallbackLabel(headsetConnected = false),
        )
        assertEquals(
            "Glasses microphone · Ray-Ban Meta",
            AudioRouteSelector.glassesLabel("Ray-Ban Meta"),
        )
        assertEquals(
            "Glasses microphone · Bluetooth headset",
            AudioRouteSelector.glassesLabel("  "),
        )
    }

    private fun sco(id: Int, name: String) = AudioInputCandidate(id, InputKind.BLUETOOTH_SCO, name)
}
