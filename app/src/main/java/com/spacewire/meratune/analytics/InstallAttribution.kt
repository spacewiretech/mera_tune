package com.spacewire.meratune.analytics

import java.net.URLDecoder
import java.util.Locale

/**
 * Where an install came from, read from the Play install referrer: the `referrer` of the store
 * link the ad or post pointed at, e.g.
 * `https://play.google.com/store/apps/details?id=com.spacewire.meratune&referrer=utm_source%3DMeta%2Bads%26utm_campaign%3D...`.
 * Google hands that string back on the first launch after the install ([InstallReferrerTracker]).
 *
 * Pure (no `android.*`), so parsing and the source rules are unit tested (InstallAttributionTest).
 */
data class InstallAttribution(
    /** [SOURCE_META] / [SOURCE_GOOGLE_ADS] / [SOURCE_PAID_OTHER] / [SOURCE_ORGANIC]. */
    val source: String,
    val utmSource: String? = null,
    val utmMedium: String? = null,
    val utmCampaign: String? = null,
    val utmTerm: String? = null,
    val utmContent: String? = null,
    val hasGclid: Boolean = false,
) {
    /**
     * Registered as super properties, so every later app event carries them: `acquisition_source`
     * and whichever `utm_*` the link had.
     */
    val eventProperties: Map<String, String>
        get() = buildMap {
            put(PROP_ACQUISITION_SOURCE, source)
            utmSource?.let { put(UTM_SOURCE, it) }
            utmMedium?.let { put(UTM_MEDIUM, it) }
            utmCampaign?.let { put(UTM_CAMPAIGN, it) }
            utmTerm?.let { put(UTM_TERM, it) }
            utmContent?.let { put(UTM_CONTENT, it) }
        }

    /** First touch on the profile (`set_once`): `initial_` + each [eventProperties] key. */
    val initialProfileProperties: Map<String, String>
        get() = eventProperties.mapKeys { (key, _) -> "initial_$key" }

    companion object {
        const val SOURCE_META = "meta"
        const val SOURCE_GOOGLE_ADS = "google_ads"
        const val SOURCE_PAID_OTHER = "paid_other"
        const val SOURCE_ORGANIC = "organic"

        const val PROP_ACQUISITION_SOURCE = "acquisition_source"
        const val UTM_SOURCE = "utm_source"
        const val UTM_MEDIUM = "utm_medium"
        const val UTM_CAMPAIGN = "utm_campaign"
        const val UTM_TERM = "utm_term"
        const val UTM_CONTENT = "utm_content"
        private const val GCLID = "gclid"
        private const val FBCLID = "fbclid"

        /** Mixpanel's string limit; a Meta install-ad `utm_content` can be a long encrypted blob. */
        private const val MAX_VALUE_LENGTH = 255

        /** Only these keys leave the device; `gclid` / `fbclid` only as a signal, never the id. */
        private val KEPT_KEYS = setOf(UTM_SOURCE, UTM_MEDIUM, UTM_CAMPAIGN, UTM_TERM, UTM_CONTENT, GCLID, FBCLID)

        /** `meta`, `meta ads`, `facebook`, `apps.facebook.com` (Meta's own store stamp), `instagram`, `ig`, `fb…`. */
        private val META_SOURCE = Regex("""\bmeta\b|facebook|instagram|^ig$|^fb""")
        private val GOOGLE_ADS_SOURCE = Regex("""^(adwords|google[-_ ]?ads)$""")
        private val PAID_MEDIUM = Regex("""^(cpc|ppc|paid|cpm|cpi)""")

        fun fromReferrer(referrer: String): InstallAttribution {
            val params = parseReferrer(referrer)
            return InstallAttribution(
                source = resolveSource(params),
                utmSource = params[UTM_SOURCE],
                utmMedium = params[UTM_MEDIUM],
                utmCampaign = params[UTM_CAMPAIGN],
                utmTerm = params[UTM_TERM],
                utmContent = params[UTM_CONTENT],
                hasGclid = params.containsKey(GCLID),
            )
        }

        /**
         * Paid beats organic: the Play Store stamps `utm_source=google-play&utm_medium=organic` on
         * plain store visits, so only a clear paid signal changes the answer. `gclid` (a Google ad
         * click) is the one unambiguous signal and is tested first. The same rules as Astrolok's
         * `resolveAttribution`, without referrals.
         */
        internal fun resolveSource(params: Map<String, String>): String {
            val utmSource = params[UTM_SOURCE].orEmpty().lowercase(Locale.ROOT)
            val utmMedium = params[UTM_MEDIUM].orEmpty().lowercase(Locale.ROOT)
            return when {
                params.containsKey(GCLID) || GOOGLE_ADS_SOURCE.matches(utmSource) -> SOURCE_GOOGLE_ADS
                params.containsKey(FBCLID) || META_SOURCE.containsMatchIn(utmSource) -> SOURCE_META
                PAID_MEDIUM.containsMatchIn(utmMedium) -> SOURCE_PAID_OTHER
                else -> SOURCE_ORGANIC
            }
        }

        /**
         * Kept keys with non-blank values, keys lower-cased, values decoded and capped. Google
         * returns the `referrer` decoded once; a referrer still encoded as a whole is decoded first.
         */
        internal fun parseReferrer(referrer: String): Map<String, String> {
            val query = if ('=' !in referrer && referrer.contains("%3D", ignoreCase = true)) decode(referrer) else referrer
            return query.split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=', "").trim().lowercase(Locale.ROOT)
                if (key !in KEPT_KEYS) return@mapNotNull null
                val value = decode(pair.substringAfter('=')).trim().take(MAX_VALUE_LENGTH)
                if (value.isEmpty()) null else key to value
            }.toMap()
        }

        private fun decode(value: String): String =
            try {
                URLDecoder.decode(value, "UTF-8")
            } catch (_: IllegalArgumentException) {
                value
            }
    }
}
