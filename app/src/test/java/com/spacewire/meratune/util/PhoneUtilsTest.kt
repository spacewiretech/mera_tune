package com.spacewire.meratune.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [PhoneUtils.normalizeIndianPhone] also drives the phone CTA's grey/gradient look. */
class PhoneUtilsTest {

    @Test
    fun tenDigitsAreKept() {
        assertEquals("7645789764", PhoneUtils.normalizeIndianPhone("7645789764"))
    }

    @Test
    fun countryCodeAndSpacesAreStripped() {
        assertEquals("7645789764", PhoneUtils.normalizeIndianPhone("+91 76457 89764"))
    }

    @Test
    fun leadingZeroIsStripped() {
        assertEquals("7645789764", PhoneUtils.normalizeIndianPhone("07645789764"))
    }

    @Test
    fun tooShortIsInvalid() {
        assertNull(PhoneUtils.normalizeIndianPhone("764578976"))
    }

    @Test
    fun emptyIsInvalid() {
        assertNull(PhoneUtils.normalizeIndianPhone(""))
    }
}
