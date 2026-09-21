package live.sutra.field

import live.sutra.codec.SutraWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRoutingTest {

    @Test
    fun hindi_goes_to_the_bundled_model_even_when_the_platform_also_has_it() {

        val capability = SpeechRouting.capabilityFor("hi", whisperAvailable = true, platformLanguages = setOf("hi", "en"))
        assertEquals(SpeechRouting.Engine.WHISPER, capability.engine)
        assertTrue("only the whisper path keeps the audio", capability.prosodyAvailable)
    }

    @Test
    fun other_languages_use_the_platform_when_it_has_them_installed() {
        val capability = SpeechRouting.capabilityFor("ta", whisperAvailable = true, platformLanguages = setOf("ta"))
        assertEquals(SpeechRouting.Engine.PLATFORM, capability.engine)
        assertTrue(capability.canSpeak)

        assertFalse(capability.prosodyAvailable)
    }

    @Test
    fun a_language_no_engine_has_is_reported_rather_than_mis_transcribed() {
        val capability = SpeechRouting.capabilityFor("ml", whisperAvailable = true, platformLanguages = setOf("en"))
        assertEquals(SpeechRouting.Engine.NONE, capability.engine)
        assertFalse(capability.canSpeak)
    }

    @Test
    fun without_the_bundled_model_hindi_falls_to_the_platform_or_to_nothing() {
        assertEquals(
            SpeechRouting.Engine.PLATFORM,
            SpeechRouting.engineFor("hi", whisperAvailable = false, platformLanguages = setOf("hi")),
        )
        assertEquals(
            SpeechRouting.Engine.NONE,
            SpeechRouting.engineFor("hi", whisperAvailable = false, platformLanguages = emptySet()),
        )
    }

    @Test
    fun the_survey_covers_every_language_the_protocol_carries() {
        val survey = SpeechRouting.survey(whisperAvailable = true, platformLanguages = setOf("en", "bn"))
        assertEquals(SutraWire.LANGUAGES, survey.map { it.language })
        assertEquals(
            listOf("hi" to SpeechRouting.Engine.WHISPER, "bn" to SpeechRouting.Engine.PLATFORM, "en" to SpeechRouting.Engine.PLATFORM),
            survey.filter { it.canSpeak }.map { it.language to it.engine },
        )
    }

    @Test
    fun a_phone_with_no_engines_at_all_still_answers_for_every_language() {
        val survey = SpeechRouting.survey(whisperAvailable = false, platformLanguages = emptySet())
        assertEquals(SutraWire.LANGUAGES.size, survey.size)
        assertTrue(survey.none { it.canSpeak })
        assertTrue(survey.all { SpeechRouting.describe(it).isNotBlank() })
    }
}
