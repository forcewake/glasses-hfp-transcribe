package com.glasses.hfptranscribe.stt

enum class AppLanguage(val code: String, val label: String) {
    RU("ru", "Russian"),
    EN("en", "English"),
    NL("nl", "Dutch"),
}

enum class ModelFamily {
    ZIPFORMER,
    TONE_CTC,
    NEMOTRON,
}

data class AsrModel(
    val id: String,
    val title: String,
    val summary: String,
    val archiveName: String,
    val downloadBytes: Long,
    val freeSpaceBytes: Long,
    val family: ModelFamily,
    val modelType: String,
    val encoder: String = "",
    val decoder: String = "",
    val joiner: String = "",
    val toneModel: String? = null,
    val tokens: String = "tokens.txt",
    val passesLanguage: Boolean = false,
    val threads: Int = 2,
) {
    val archiveUrl: String =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$archiveName"

    val requiredFiles: List<String> = buildList {
        if (toneModel != null) {
            add(toneModel)
        } else {
            add(encoder)
            add(decoder)
            add(joiner)
        }
        add(tokens)
    }
}

object Models {
    val ruSmall = AsrModel(
        id = "ru-small-int8",
        title = "Russian · small Zipformer",
        summary = "Default. Streaming Zipformer int8, about 23 MB. Low-latency Russian partials.",
        archiveName = "sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16.tar.bz2",
        downloadBytes = 23_000_000L,
        freeSpaceBytes = 80_000_000L,
        family = ModelFamily.ZIPFORMER,
        modelType = "zipformer2",
        encoder = "encoder.int8.onnx",
        decoder = "decoder.onnx",
        joiner = "joiner.int8.onnx",
    )

    val ruTone = AsrModel(
        id = "ru-tone",
        title = "Russian · T-one",
        summary = "Optional higher-accuracy Russian model. Streaming CTC, telephony-tuned, about 123 MB. Sherpa resamples the 16 kHz capture to the model's 8 kHz rate.",
        archiveName = "sherpa-onnx-streaming-t-one-russian-2025-09-08.tar.bz2",
        downloadBytes = 123_000_000L,
        freeSpaceBytes = 400_000_000L,
        family = ModelFamily.TONE_CTC,
        modelType = "",
        toneModel = "model.onnx",
        threads = 2,
    )

    val enKroko = AsrModel(
        id = "en-kroko",
        title = "English · Kroko Zipformer",
        summary = "Streaming Zipformer2 for English, about 55 MB.",
        archiveName = "sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06.tar.bz2",
        downloadBytes = 55_000_000L,
        freeSpaceBytes = 200_000_000L,
        family = ModelFamily.ZIPFORMER,
        modelType = "zipformer2",
        encoder = "encoder.onnx",
        decoder = "decoder.onnx",
        joiner = "joiner.onnx",
    )

    val nemotron = AsrModel(
        id = "multi-nemotron",
        title = "All languages · Nemotron 3.5",
        summary = "Optional. Streaming 0.6B int8, 160 ms chunks. About 450 MB to download and 650 MB on disk. Covers Russian, English, and Dutch, with punctuation.",
        archiveName = "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-160ms-int8-2026-06-11.tar.bz2",
        downloadBytes = 453_000_000L,
        freeSpaceBytes = 1_200_000_000L,
        family = ModelFamily.NEMOTRON,
        modelType = "",
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
        passesLanguage = true,
        threads = 4,
    )

    val all: List<AsrModel> = listOf(ruSmall, ruTone, enKroko, nemotron)

    fun byId(id: String): AsrModel? = all.firstOrNull { it.id == id }

    fun choicesFor(language: AppLanguage): List<AsrModel> = when (language) {
        AppLanguage.RU -> listOf(ruSmall, ruTone, nemotron)
        AppLanguage.EN -> listOf(enKroko, nemotron)
        AppLanguage.NL -> listOf(nemotron)
    }
}

fun modelFor(
    language: AppLanguage,
    russianModelId: String,
    englishModelId: String,
): AsrModel = when (language) {
    AppLanguage.NL -> Models.nemotron
    AppLanguage.RU -> when (russianModelId) {
        Models.ruTone.id -> Models.ruTone
        Models.nemotron.id -> Models.nemotron
        else -> Models.ruSmall
    }
    AppLanguage.EN -> when (englishModelId) {
        Models.nemotron.id -> Models.nemotron
        else -> Models.enKroko
    }
}

fun languagesSelectable(allLanguagesReady: Boolean): List<AppLanguage> {
    return if (allLanguagesReady) {
        AppLanguage.entries
    } else {
        listOf(AppLanguage.RU, AppLanguage.EN)
    }
}

fun megabytes(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return if (mb >= 100) "${mb.toInt()} MB" else "${"%.0f".format(mb)} MB"
}
