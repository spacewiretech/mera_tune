package com.spacewire.meratune.calltheme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetChoiceTest {

    @Test
    fun audioOnlyDropsImagePathAndPhotoSource() {
        val choice = SetChoice.parse("AUDIO_ONLY", "/data/staged.jpg", "camera", 1_500L)
        assertEquals(SetChoice(RingtoneSetMode.AUDIO_ONLY, null, null, 1_500L), choice)
    }

    @Test
    fun photoModesKeepPathAndSource() {
        val everyone = SetChoice.parse("WITH_IMAGE_EVERYONE", "/data/a.jpg", "gallery", 10L)
        assertEquals(SetChoice(RingtoneSetMode.WITH_IMAGE_EVERYONE, "/data/a.jpg", "gallery", 10L), everyone)

        val contact = SetChoice.parse("WITH_IMAGE_CONTACT", "/data/b.jpg", "camera", 20L)
        assertEquals(SetChoice(RingtoneSetMode.WITH_IMAGE_CONTACT, "/data/b.jpg", "camera", 20L), contact)
    }

    @Test
    fun unknownOrBlankModeIsNull() {
        assertNull(SetChoice.parse(null, null, null, 0L))
        assertNull(SetChoice.parse("", "/data/a.jpg", "camera", 0L))
        assertNull(SetChoice.parse("with_image_everyone", "/data/a.jpg", "camera", 0L))
        assertNull(SetChoice.parse("RINGTONE_AND_WALLPAPER", null, null, 0L))
    }

    @Test
    fun unknownPhotoSourceIsDropped() {
        val choice = SetChoice.parse("WITH_IMAGE_EVERYONE", "/data/a.jpg", "files", 0L)
        assertNull(choice?.photoSource)
        assertEquals("/data/a.jpg", choice?.imagePath)
    }

    @Test
    fun blankImagePathIsNull() {
        assertNull(SetChoice.parse("WITH_IMAGE_CONTACT", "  ", "camera", 0L)?.imagePath)
    }

    @Test
    fun negativeDurationIsCoercedToZero() {
        assertEquals(0L, SetChoice.parse("AUDIO_ONLY", null, null, -42L)?.chooseDurationMs)
    }

    @Test
    fun everyModeRoundTripsByEnumName() {
        RingtoneSetMode.entries.forEach { mode ->
            assertEquals(mode, SetChoice.parse(mode.name, "/p.jpg", "gallery", 5L)?.mode)
        }
    }
}
