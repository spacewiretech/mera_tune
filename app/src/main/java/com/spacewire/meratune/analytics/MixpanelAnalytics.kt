package com.spacewire.meratune.analytics

import android.content.Context
import com.mixpanel.android.mpmetrics.MixpanelAPI
import com.spacewire.meratune.BuildConfig
import com.spacewire.meratune.data.User
import com.spacewire.meratune.model.PaymentApp
import com.spacewire.meratune.util.AuthStore
import com.spacewire.meratune.util.ProfileStore
import org.json.JSONObject

class MixpanelAnalytics private constructor(context: Context) {

    val mixpanel: MixpanelAPI = MixpanelAPI.getInstance(
        context,
        BuildConfig.MIXPANEL_TOKEN,
        BuildConfig.DEBUG,
    )

    init {
        registerSuperProperties()
        restoreIdentity(context)
    }

    fun identifyUser(user: User) {
        mixpanel.identify(user.id.toString())
        mixpanel.people.set("\$name", user.name.orEmpty())
        mixpanel.people.set("subscription_status", user.status)
    }

    fun logout(context: Context) {
        reset()
        ProfileStore(context).clearSession()
    }

    fun trackSignUpCompleted(signUpMethod: String) {
        val props = JSONObject()
        props.put("sign_up_method", signUpMethod)
        props.put("platform", PLATFORM)
        mixpanel.track("sign_up_completed", props)
    }

    fun trackLoginCompleted(signInMethod: String) {
        val props = JSONObject()
        props.put("sign_in_method", signInMethod)
        props.put("platform", PLATFORM)
        mixpanel.track("login_completed", props)
    }

    fun trackOtpSent(isResend: Boolean) {
        val props = JSONObject()
        props.put("is_resend", isResend)
        props.put("platform", PLATFORM)
        mixpanel.track("otp_sent", props)
    }

    fun trackSubscriptionScreenViewed() {
        val props = JSONObject()
        props.put("platform", PLATFORM)
        mixpanel.track("subscription_screen_viewed", props)
    }

    fun trackSubscriptionStarted(
        paymentApp: PaymentApp,
        authAmount: Double?,
        recurringAmount: Double?,
    ) {
        val props = JSONObject()
        props.put("payment_app", paymentApp.analyticsSlug())
        authAmount?.let { props.put("auth_amount", it) }
        recurringAmount?.let { props.put("recurring_amount", it) }
        props.put("platform", PLATFORM)
        mixpanel.track("subscription_started", props)
    }

    fun trackTrialPaymentCompleted(
        paymentApp: PaymentApp,
        subscriptionId: String,
        amount: Double,
    ) {
        val props = JSONObject()
        props.put("payment_app", paymentApp.analyticsSlug())
        props.put("subscription_id", subscriptionId)
        props.put("amount", amount)
        props.put("currency", "INR")
        props.put("platform", PLATFORM)
        mixpanel.track("trial_payment_completed", props)
        mixpanel.people.set("subscription_status", "trial")
    }

    fun trackSubscriptionFailed(
        stage: String,
        failureReason: String?,
        paymentApp: PaymentApp?,
    ) {
        val props = JSONObject()
        props.put("stage", stage)
        failureReason?.takeIf { it.isNotBlank() }?.let { props.put("failure_reason", it) }
        paymentApp?.let { props.put("payment_app", it.analyticsSlug()) }
        props.put("platform", PLATFORM)
        mixpanel.track("subscription_failed", props)
    }

    // ---------------------------------------------------------------------------------------------
    // Personalized ringtone flow. Never pass the typed name or the generated title into these.
    // `voice` is "male" | "female" (blank omitted); `category` is the DB category name.
    // ---------------------------------------------------------------------------------------------

    /** Continue on the create form (name + language). */
    fun trackRingtoneCreationStarted(language: String, nameLength: Int) {
        val props = JSONObject()
        props.putIfNotBlank("language", language)
        props.put("name_length", nameLength)
        props.put("platform", PLATFORM)
        mixpanel.track("ringtone_creation_started", props)
    }

    /** Song picker reached a terminal load state (content, empty or error). */
    fun trackSongPickerViewed(
        language: String,
        songCount: Int,
        categoryCount: Int,
        fallbackLevel: String,
        voiceFilter: String?,
    ) {
        val props = JSONObject()
        props.putIfNotBlank("language", language)
        props.put("song_count", songCount)
        props.put("category_count", categoryCount)
        props.putIfNotBlank("fallback_level", fallbackLevel)
        props.putIfNotBlank("voice_filter", voiceFilter)
        props.put("platform", PLATFORM)
        mixpanel.track("song_picker_viewed", props)
    }

    /** Preview playback started for a card in the song picker. */
    fun trackSampleSongPlayed(
        tuneId: String,
        category: String,
        language: String,
        voice: String,
        rank: Int,
    ) {
        val props = JSONObject()
        props.put("tune_id", tuneId)
        props.putIfNotBlank("category", category)
        props.putIfNotBlank("language", language)
        props.putIfNotBlank("voice", voice)
        props.put("rank", rank)
        props.put("platform", PLATFORM)
        mixpanel.track("sample_song_played", props)
    }

    /** First selection of a card in the song picker. */
    fun trackSampleSongSelected(
        tuneId: String,
        category: String,
        language: String,
        voice: String,
        rank: Int,
        voiceFilter: String?,
        categoryFilter: String?,
    ) {
        val props = JSONObject()
        props.put("tune_id", tuneId)
        props.putIfNotBlank("category", category)
        props.putIfNotBlank("language", language)
        props.putIfNotBlank("voice", voice)
        props.put("rank", rank)
        props.putIfNotBlank("voice_filter", voiceFilter)
        props.putIfNotBlank("category_filter", categoryFilter)
        props.put("platform", PLATFORM)
        mixpanel.track("sample_song_selected", props)
    }

    /** One generate-ringtone attempt is about to be posted (once per attempt, including retries). */
    fun trackRingtoneGenerationStarted(
        tuneId: String,
        category: String,
        language: String,
        voice: String,
        nameLength: Int,
        isRetry: Boolean,
    ) {
        val props = JSONObject()
        props.put("tune_id", tuneId)
        props.putIfNotBlank("category", category)
        props.putIfNotBlank("language", language)
        props.putIfNotBlank("voice", voice)
        props.put("name_length", nameLength)
        props.put("is_retry", isRetry)
        props.put("platform", PLATFORM)
        mixpanel.track("ringtone_generation_started", props)
    }

    /** Generation succeeded (fires exactly once per successful generation). */
    fun trackRingtoneCreated(
        tuneId: String,
        category: String,
        language: String,
        voice: String,
        cached: Boolean,
        durationMs: Int?,
        clientMs: Long,
        generationId: String?,
        source: String = SOURCE_CREATION_FLOW,
    ) {
        val props = JSONObject()
        props.put("tune_id", tuneId)
        props.putIfNotBlank("category", category)
        props.putIfNotBlank("language", language)
        props.putIfNotBlank("voice", voice)
        props.put("cached", cached)
        durationMs?.let { props.put("duration_ms", it) }
        props.put("client_ms", clientMs)
        props.putIfNotBlank("generation_id", generationId)
        props.putIfNotBlank("source", source)
        props.put("platform", PLATFORM)
        mixpanel.track("ringtone_created", props)
        if (category.isNotBlank()) {
            mixpanel.people.set("last_ringtone_category", category)
        }
    }

    /**
     * Generation ended without a ringtone. [failureReason] is a lower-cased
     * `GenerationErrorCode` name or `user_cancelled`.
     */
    fun trackRingtoneGenerationFailed(
        tuneId: String,
        category: String,
        language: String,
        voice: String,
        failureReason: String,
        httpStatus: Int?,
        retryable: Boolean,
        clientMs: Long,
    ) {
        val props = JSONObject()
        props.put("tune_id", tuneId)
        props.putIfNotBlank("category", category)
        props.putIfNotBlank("language", language)
        props.putIfNotBlank("voice", voice)
        props.putIfNotBlank("failure_reason", failureReason)
        httpStatus?.let { props.put("http_status", it) }
        props.put("retryable", retryable)
        props.put("client_ms", clientMs)
        props.put("platform", PLATFORM)
        mixpanel.track("ringtone_generation_failed", props)
    }

    fun trackRingtoneSet(
        source: String,
        category: String,
        tuneId: String,
        tuneName: String,
        setMode: String = "audio_only",
        generationId: String? = null,
        personalized: Boolean = false,
    ) {
        val props = JSONObject()
        props.put("source", source)
        props.putIfNotBlank("category", category)
        props.put("tune_id", tuneId)
        props.putIfNotBlank("tune_name", tuneName)
        props.put("set_mode", setMode)
        props.putIfNotBlank("generation_id", generationId)
        props.put("personalized", personalized)
        props.put("platform", PLATFORM)
        mixpanel.track("ringtone_set", props)
        if (category.isNotBlank()) {
            mixpanel.people.set("last_ringtone_category", category)
        }
    }

    fun trackLanguageSelected(language: String, locale: String, context: String) {
        val props = JSONObject()
        props.put("language", language)
        props.put("locale", locale)
        props.put("context", context)
        props.put("platform", PLATFORM)
        mixpanel.track("language_selected", props)
        mixpanel.people.set("app_language", language)
    }

    fun trackHomeViewed(tuneCount: Int, categoryCount: Int) {
        val props = JSONObject()
        props.put("tune_count", tuneCount)
        props.put("category_count", categoryCount)
        props.put("platform", PLATFORM)
        mixpanel.track("home_viewed", props)
    }

    fun trackTunePlayed(tuneId: String, category: String, source: String) {
        val props = JSONObject()
        props.put("tune_id", tuneId)
        props.put("category", category)
        props.put("source", source)
        props.put("platform", PLATFORM)
        mixpanel.track("tune_played", props)
    }

    fun trackSearchPerformed(queryLength: Int, resultCount: Int) {
        val props = JSONObject()
        props.put("query_length", queryLength)
        props.put("result_count", resultCount)
        props.put("platform", PLATFORM)
        mixpanel.track("search_performed", props)
    }

    /** [source] is `"home"` (catalog chips) or `"song_picker"` (create-flow chips). */
    fun trackCategoryFiltered(categoryId: String, categoryName: String, source: String = SOURCE_HOME) {
        val props = JSONObject()
        props.put("category_id", categoryId)
        props.put("category_name", categoryName)
        props.putIfNotBlank("source", source)
        props.put("platform", PLATFORM)
        mixpanel.track("category_filtered", props)
    }

    fun trackCreateRingtoneCtaTapped(source: String, prefillNameLength: Int) {
        val props = JSONObject()
        props.put("source", source)
        props.put("prefill_name_length", prefillNameLength)
        props.put("platform", PLATFORM)
        mixpanel.track("create_ringtone_cta_tapped", props)
    }

    fun reset() {
        mixpanel.reset()
        registerSuperProperties()
    }

    private fun registerSuperProperties() {
        val superProps = JSONObject()
        superProps.put("platform", PLATFORM)
        superProps.put("app_version", BuildConfig.VERSION_NAME)
        mixpanel.registerSuperProperties(superProps)
    }

    private fun restoreIdentity(context: Context) {
        val authStore = AuthStore(context)
        if (!authStore.isLoggedIn()) return

        mixpanel.identify(authStore.getUserId().toString())
    }

    private fun PaymentApp.analyticsSlug(): String = when (this) {
        PaymentApp.PHONEPE -> "phonepe"
        PaymentApp.GOOGLE_PAY -> "google_pay"
        PaymentApp.PAYTM -> "paytm"
        PaymentApp.BHIM -> "bhim"
    }

    /** Convention: never send null or blank string properties; omit them instead. */
    private fun JSONObject.putIfNotBlank(key: String, value: String?) {
        if (!value.isNullOrBlank()) put(key, value)
    }

    companion object {
        private const val PLATFORM = "android"
        private const val SOURCE_HOME = "home"
        private const val SOURCE_CREATION_FLOW = "creation_flow"

        @Volatile
        private var instance: MixpanelAnalytics? = null

        fun init(context: Context): MixpanelAnalytics {
            return instance ?: synchronized(this) {
                instance ?: MixpanelAnalytics(context.applicationContext).also { instance = it }
            }
        }

        fun getInstance(context: Context): MixpanelAnalytics {
            return instance ?: init(context)
        }
    }
}

fun Context.mixpanelAnalytics(): MixpanelAnalytics = MixpanelAnalytics.getInstance(this)
