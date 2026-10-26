package com.glasses.hfptranscribe.stt

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSelectionTest {
    @Test
    fun russianDefaultsToTheSmallStreamingModel() {
        assertEquals(Models.ruSmall, modelFor(AppLanguage.RU, russianModelId = "missing", englishModelId = "missing"))
    }

    @Test
    fun russianCanSelectToneOrTheMultilingualModel() {
        assertEquals(Models.ruTone, modelFor(AppLanguage.RU, Models.ruTone.id, Models.enKroko.id))
        assertEquals(Models.nemotron, modelFor(AppLanguage.RU, Models.nemotron.id, Models.enKroko.id))
    }

    @Test
    fun englishDefaultsToKroko() {
        assertEquals(Models.enKroko, modelFor(AppLanguage.EN, Models.ruSmall.id, "missing"))
        assertEquals(Models.nemotron, modelFor(AppLanguage.EN, Models.ruSmall.id, Models.nemotron.id))
    }

    @Test
    fun dutchAlwaysUsesTheMultilingualModel() {
        assertEquals(Models.nemotron, modelFor(AppLanguage.NL, Models.ruSmall.id, Models.enKroko.id))
    }

    @Test
    fun dutchAppearsOnlyAfterTheMultilingualModelIsReady() {
        assertEquals(listOf(AppLanguage.RU, AppLanguage.EN), languagesSelectable(allLanguagesReady = false))
        assertEquals(AppLanguage.entries, languagesSelectable(allLanguagesReady = true))
    }
}
