package com.app.assistant.tts

/**
 * Каталог офлайн-голосов.
 *
 * Раньше в приложении была одна модель синтеза — Supertonic, и она английская: русский
 * текст читался с иностранным акцентом. Владелец попросил русские голоса и «у каждого
 * агента свой голос», поэтому здесь четыре русских диктора Piper (VITS), которые
 * sherpa-onnx уже умеет запускать тем же API, что и Supertonic.
 *
 * Один голос = один архив sherpa-onnx (~64 МБ) с model.onnx, tokens.txt и
 * espeak-ng-data (фонемизация русского уже внутри архива).
 *
 * `onlineVoice` / `googleVoice` — соответствия для облачного синтеза: у Edge TTS
 * русских голосов только два (Светлана и Дмитрий), поэтому женские офлайн-голоса
 * (Ирина) идут на Светлану, мужские — на Дмитрия. Это честное ограничение сервиса,
 * а не наша потеря: уникальность голосов даёт офлайн-режим.
 */
data class OfflineVoice(
    val id: String,
    val title: String,
    val gender: String,
    /** Имя каталога после распаковки архива. */
    val dirName: String,
    /** Имя файла модели внутри каталога. */
    val modelFile: String,
    val onlineVoice: String,
    val googleVoice: String,
    val sizeMb: Int,
) {
    val archiveUrl: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$dirName.tar.bz2"
}

object VoiceCatalog {
    val voices: List<OfflineVoice> = listOf(
        OfflineVoice(
            id = "irina",
            title = "Ирина",
            gender = "женский",
            dirName = "vits-piper-ru_RU-irina-medium",
            modelFile = "ru_RU-irina-medium.onnx",
            onlineVoice = "ru-RU-SvetlanaNeural",
            googleVoice = "ru-RU-Wavenet-A",
            sizeMb = 64,
        ),
        OfflineVoice(
            id = "denis",
            title = "Денис",
            gender = "мужской",
            dirName = "vits-piper-ru_RU-denis-medium",
            modelFile = "ru_RU-denis-medium.onnx",
            onlineVoice = "ru-RU-DmitryNeural",
            googleVoice = "ru-RU-Wavenet-B",
            sizeMb = 64,
        ),
        OfflineVoice(
            id = "dmitri",
            title = "Дмитрий",
            gender = "мужской",
            dirName = "vits-piper-ru_RU-dmitri-medium",
            modelFile = "ru_RU-dmitri-medium.onnx",
            onlineVoice = "ru-RU-DmitryNeural",
            googleVoice = "ru-RU-Wavenet-C",
            sizeMb = 64,
        ),
        OfflineVoice(
            id = "ruslan",
            title = "Руслан",
            gender = "мужской",
            dirName = "vits-piper-ru_RU-ruslan-medium",
            modelFile = "ru_RU-ruslan-medium.onnx",
            onlineVoice = "ru-RU-DmitryNeural",
            googleVoice = "ru-RU-Wavenet-D",
            sizeMb = 64,
        ),
    )

    /** Голос по умолчанию: женский, совпадает с голосом Старпома и Масяни. */
    const val DEFAULT_ID = "irina"

    fun byId(id: String?): OfflineVoice =
        voices.firstOrNull { it.id == id } ?: voices.first { it.id == DEFAULT_ID }
}
