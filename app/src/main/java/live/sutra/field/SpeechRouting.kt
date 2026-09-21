package live.sutra.field

import live.sutra.codec.SutraWire

object SpeechRouting {

    enum class Engine {

        WHISPER,

        PLATFORM,

        NONE,
    }

    const val BUNDLED_LANGUAGE = "hi"

    data class Capability(
        val language: String,
        val engine: Engine,
        val prosodyAvailable: Boolean,
    ) {
        val canSpeak: Boolean get() = engine != Engine.NONE
    }

    fun engineFor(
        language: String,
        whisperAvailable: Boolean,
        platformLanguages: Set<String>,
    ): Engine = when {
        whisperAvailable && language == BUNDLED_LANGUAGE -> Engine.WHISPER
        language in platformLanguages -> Engine.PLATFORM
        else -> Engine.NONE
    }

    fun capabilityFor(
        language: String,
        whisperAvailable: Boolean,
        platformLanguages: Set<String>,
    ): Capability {
        val engine = engineFor(language, whisperAvailable, platformLanguages)
        return Capability(
            language = language,
            engine = engine,
            prosodyAvailable = engine == Engine.WHISPER,
        )
    }

    fun survey(whisperAvailable: Boolean, platformLanguages: Set<String>): List<Capability> =
        SutraWire.LANGUAGES.map { capabilityFor(it, whisperAvailable, platformLanguages) }

    fun describe(capability: Capability): String = when (capability.engine) {
        Engine.WHISPER -> "bundled model, with prosody"
        Engine.PLATFORM -> "device recogniser, no prosody"
        Engine.NONE -> "no offline recogniser on this device"
    }
}
