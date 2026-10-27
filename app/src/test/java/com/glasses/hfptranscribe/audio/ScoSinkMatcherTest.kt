package com.glasses.hfptranscribe.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoSinkMatcherTest {
    @Test
    fun sameIdWins() {
        val selected = sco(4, "AA:BB:CC:DD:EE:FF", "Meta RB Display 001M")
        val sink = sco(4, "", "something else")
        val other = sco(9, "AA:BB:CC:DD:EE:FF", "Meta RB Display 001M")
        assertEquals(sink, ScoSinkMatcher.match(selected, listOf(other, sink)))
    }

    @Test
    fun addressMatchesAcrossDifferentIds() {
        val input = sco(12, "AA:BB:CC:DD:EE:01", "Meta RB Display 001M")
        val sink = sco(44, "aa-bb-cc-dd-ee-01", "Meta RB Display")
        val car = sco(7, "11:22:33:44:55:66", "Car Kit")
        assertEquals(sink, ScoSinkMatcher.match(input, listOf(car, sink)))
    }

    @Test
    fun productNameMatchesWhenAddressIsMissing() {
        val input = sco(12, "", "Meta RB Display 001M")
        val sink = sco(44, "", "meta rb display")
        assertEquals(sink, ScoSinkMatcher.match(input, listOf(sink)))
    }

    @Test
    fun productNameDoesNotOverrideADifferentAddress() {
        val input = sco(12, "AA:BB:CC:DD:EE:01", "Meta RB Display 001M")
        val other = sco(44, "11:22:33:44:55:66", "Meta RB Display 001M")
        assertNull(ScoSinkMatcher.match(input, listOf(other)))
    }

    @Test
    fun singleSinkIsUsedWhenNothingElseIdentifiesIt() {
        val input = sco(12, "", "")
        val sink = sco(44, "", "Meta RB Display 001M")
        assertEquals(sink, ScoSinkMatcher.match(input, listOf(sink)))
    }

    @Test
    fun severalUnidentifiedSinksAreNotGuessed() {
        val input = sco(12, "", "")
        val first = sco(44, "", "")
        val second = sco(45, "", "")
        assertNull(ScoSinkMatcher.match(input, listOf(first, second)))
    }

    @Test
    fun emptyPeerListMatchesNothing() {
        assertNull(ScoSinkMatcher.match(sco(1, "aa", "Meta"), emptyList()))
    }

    @Test
    fun containedProductNameMatches() {
        val input = sco(3, "", "Meta RB Display 001M")
        val sink = sco(8, "", "RB Display")
        assertEquals(sink, ScoSinkMatcher.match(input, listOf(sink)))
    }

    private fun sco(id: Int, address: String, name: String) = ScoEndpoint(id, address, name)
}
