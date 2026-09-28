package com.spacewire.meratune.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallAttributionTest {

    /** The store link's `referrer`, as the Install Referrer API returns it (decoded once). */
    private val metaAdReferrer =
        "utm_source=Meta+ads&utm_medium=online&utm_term=16+sep+-+8th" +
            "&utm_content=AL_palm_Ai+4_Hindi_pr_ds_16SEP26&utm_campaign=MeraTune+%7C+Hindi"

    @Test
    fun `a Meta ads store link keeps every utm and resolves to meta`() {
        val attribution = InstallAttribution.fromReferrer(metaAdReferrer)
        assertEquals(InstallAttribution.SOURCE_META, attribution.source)
        assertEquals("Meta ads", attribution.utmSource)
        assertEquals("online", attribution.utmMedium)
        assertEquals("16 sep - 8th", attribution.utmTerm)
        assertEquals("AL_palm_Ai 4_Hindi_pr_ds_16SEP26", attribution.utmContent)
        assertEquals("MeraTune | Hindi", attribution.utmCampaign)
        assertFalse(attribution.hasGclid)
    }

    @Test
    fun `event and first-touch properties`() {
        val attribution = InstallAttribution.fromReferrer(metaAdReferrer)
        assertEquals(
            mapOf(
                "acquisition_source" to "meta",
                "utm_source" to "Meta ads",
                "utm_medium" to "online",
                "utm_campaign" to "MeraTune | Hindi",
                "utm_term" to "16 sep - 8th",
                "utm_content" to "AL_palm_Ai 4_Hindi_pr_ds_16SEP26",
            ),
            attribution.eventProperties,
        )
        assertEquals("meta", attribution.initialProfileProperties["initial_acquisition_source"])
        assertEquals("MeraTune | Hindi", attribution.initialProfileProperties["initial_utm_campaign"])
        assertEquals(6, attribution.initialProfileProperties.size)
    }

    @Test
    fun `a referrer still encoded as a whole is decoded first`() {
        val encoded = "utm_source%3DMeta%2Bads%26utm_medium%3Donline%26utm_campaign%3DMeraTune%2B%257C%2BHindi"
        val attribution = InstallAttribution.fromReferrer(encoded)
        assertEquals("Meta ads", attribution.utmSource)
        assertEquals("online", attribution.utmMedium)
        assertEquals("MeraTune | Hindi", attribution.utmCampaign)
        assertEquals(InstallAttribution.SOURCE_META, attribution.source)
    }

    @Test
    fun `a plain store visit is organic`() {
        val attribution = InstallAttribution.fromReferrer("utm_source=google-play&utm_medium=organic")
        assertEquals(InstallAttribution.SOURCE_ORGANIC, attribution.source)
        assertEquals("google-play", attribution.utmSource)
        assertEquals(
            mapOf("acquisition_source" to "organic", "utm_source" to "google-play", "utm_medium" to "organic"),
            attribution.eventProperties,
        )
    }

    @Test
    fun `a Google ad click is google_ads and the gclid never leaves the device`() {
        val attribution = InstallAttribution.fromReferrer("gclid=Cj0KCQjw-abc&utm_source=google-play&utm_medium=organic")
        assertEquals(InstallAttribution.SOURCE_GOOGLE_ADS, attribution.source)
        assertTrue(attribution.hasGclid)
        assertFalse(attribution.eventProperties.values.any { it.contains("Cj0KCQjw") })
        assertEquals(InstallAttribution.SOURCE_GOOGLE_ADS, InstallAttribution.fromReferrer("utm_source=google-ads").source)
        assertEquals(InstallAttribution.SOURCE_GOOGLE_ADS, InstallAttribution.fromReferrer("utm_source=adwords").source)
    }

    @Test
    fun `Meta sources in their usual spellings`() {
        listOf("meta", "Meta ads", "facebook", "apps.facebook.com", "instagram", "ig", "fb").forEach { source ->
            assertEquals(source, InstallAttribution.SOURCE_META, InstallAttribution.fromReferrer("utm_source=$source").source)
        }
        assertEquals(InstallAttribution.SOURCE_META, InstallAttribution.fromReferrer("fbclid=IwAR0abc").source)
        // Not Meta: "meta" inside another word.
        assertEquals(InstallAttribution.SOURCE_ORGANIC, InstallAttribution.fromReferrer("utm_source=metamorph").source)
    }

    @Test
    fun `a paid medium with an unknown source is paid_other`() {
        assertEquals(
            InstallAttribution.SOURCE_PAID_OTHER,
            InstallAttribution.fromReferrer("utm_source=newsletter&utm_medium=cpc").source,
        )
        assertEquals(
            InstallAttribution.SOURCE_ORGANIC,
            InstallAttribution.fromReferrer("utm_source=whatsapp&utm_medium=share").source,
        )
    }

    @Test
    fun `unknown keys are dropped, keys are case-insensitive, blanks omitted, values capped`() {
        val long = "x".repeat(400)
        val attribution = InstallAttribution.fromReferrer("UTM_Source=meta&phone=9999999999&utm_medium=&utm_content=$long")
        assertEquals("meta", attribution.utmSource)
        assertNull(attribution.utmMedium)
        assertEquals(255, attribution.utmContent?.length)
        assertFalse(attribution.eventProperties.values.any { it.contains("9999999999") })
    }

    @Test
    fun `garbage and empty referrers are organic with nothing else`() {
        listOf("", "abc", "&&=", "%E0%A4%A").forEach { raw ->
            val attribution = InstallAttribution.fromReferrer(raw)
            assertEquals(raw, InstallAttribution.SOURCE_ORGANIC, attribution.source)
            assertEquals(raw, mapOf("acquisition_source" to "organic"), attribution.eventProperties)
        }
    }
}
