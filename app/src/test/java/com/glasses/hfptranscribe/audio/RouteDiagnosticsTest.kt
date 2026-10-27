package com.glasses.hfptranscribe.audio

import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDiagnosticsTest {
    @Test
    fun reportNamesTheFailedStepBothDeviceListsAndAnotherRecorder() {
        val text = formatRouteDiagnostics(
            step = "setCommunicationDevice returned false for SCO sink id=44; legacy SCO timed out",
            modeBefore = "MODE_IN_COMMUNICATION (3)",
            modeNow = "MODE_IN_COMMUNICATION (3)",
            scoOn = false,
            activeCommunicationDevice = "none",
            devices = listOf(
                ListedDevice(
                    group = "input",
                    id = 12,
                    typeName = "BLUETOOTH_SCO",
                    source = true,
                    sink = false,
                    address = "AA:BB:CC:DD:EE:01",
                    productName = "Meta RB Display 001M",
                ),
                ListedDevice(
                    group = "communication",
                    id = 44,
                    typeName = "BLUETOOTH_SCO",
                    source = false,
                    sink = true,
                    address = "AA:BB:CC:DD:EE:01",
                    productName = "Meta RB Display 001M",
                ),
            ),
            otherRecordings = listOf(OtherRecording("com.facebook.stella", "VOICE_COMMUNICATION")),
            routeNote = "Mode was already MODE_IN_COMMUNICATION, so another app may own the route.",
        )
        assertTrue(text.contains("Step: setCommunicationDevice returned false"))
        assertTrue(text.contains("Mode before: MODE_IN_COMMUNICATION (3)"))
        assertTrue(text.contains("isBluetoothScoOn: false"))
        assertTrue(text.contains("Active communication device: none"))
        assertTrue(text.contains("Inputs:"))
        assertTrue(text.contains("id=12 BLUETOOTH_SCO source address=AA:BB:CC:DD:EE:01"))
        assertTrue(text.contains("Communication devices:"))
        assertTrue(text.contains("id=44 BLUETOOTH_SCO sink address=AA:BB:CC:DD:EE:01"))
        assertTrue(text.contains("com.facebook.stella source=VOICE_COMMUNICATION"))
        assertTrue(text.contains("another app may own the route"))
    }
}
