package com.glasses.hfptranscribe

import android.content.Context
import com.glasses.hfptranscribe.stt.AppLanguage
import com.glasses.hfptranscribe.stt.AsrModel
import com.glasses.hfptranscribe.stt.Models
import com.glasses.hfptranscribe.stt.modelFor

data class Settings(
    val language: AppLanguage,
    val russianModelId: String,
    val englishModelId: String,
    val usePhoneMic: Boolean,
    val glassesName: String,
) {
    fun activeModel(): AsrModel = modelFor(language, russianModelId, englishModelId)
}

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun snapshot(): Settings {
        val language = prefs.getString(KEY_LANGUAGE, AppLanguage.RU.name)
            ?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
            ?: AppLanguage.RU
        return Settings(
            language = language,
            russianModelId = prefs.getString(KEY_RUSSIAN, Models.ruSmall.id) ?: Models.ruSmall.id,
            englishModelId = prefs.getString(KEY_ENGLISH, Models.enKroko.id) ?: Models.enKroko.id,
            usePhoneMic = prefs.getBoolean(KEY_PHONE, false),
            glassesName = prefs.getString(KEY_GLASSES, "Ray-Ban") ?: "Ray-Ban",
        )
    }

    fun update(transform: (Settings) -> Settings) {
        val next = transform(snapshot())
        prefs.edit()
            .putString(KEY_LANGUAGE, next.language.name)
            .putString(KEY_RUSSIAN, next.russianModelId)
            .putString(KEY_ENGLISH, next.englishModelId)
            .putBoolean(KEY_PHONE, next.usePhoneMic)
            .putString(KEY_GLASSES, next.glassesName)
            .apply()
    }

    private companion object {
        const val FILE = "glasses"
        const val KEY_LANGUAGE = "language"
        const val KEY_RUSSIAN = "russian_model"
        const val KEY_ENGLISH = "english_model"
        const val KEY_PHONE = "use_phone_mic"
        const val KEY_GLASSES = "glasses_name"
    }
}
