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

    fun trackRingtoneCreationStarted(voice: String, category: String, language: String) {
        val props = JSONObject()
        props.put("voice", voice)
        props.put("category", category)
        props.put("language", language)
        props.put("platform", PLATFORM)
        mixpanel.track("ringtone_creation_started", props)
    }

    fun trackRingtoneCreated(
        voice: String,
        category: String,
        language: String,
        source: String,
    ) {
        val props = JSONObject()
        props.put("voice", voice)
        props.put("category", category)
        props.put("language", language)
        props.put("source", source)
        props.put("platform", PLATFORM)
        mixpanel.track("ringtone_created", props)
        mixpanel.people.set("last_ringtone_category", category)
    }

    fun trackRingtoneSet(
        source: String,
        category: String,
        tuneId: String,
        tuneName: String,
        setMode: String = "audio_only",
    ) {
        val props = JSONObject()
        props.put("source", source)
        props.put("category", category)
        props.put("tune_id", tuneId)
        props.put("tune_name", tuneName)
        props.put("set_mode", setMode)
        props.put("platform", PLATFORM)
        mixpanel.track("ringtone_set", props)
        mixpanel.people.set("last_ringtone_category", category)
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

    fun trackCategoryFiltered(categoryId: String, categoryName: String) {
        val props = JSONObject()
        props.put("category_id", categoryId)
        props.put("category_name", categoryName)
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

    companion object {
        private const val PLATFORM = "android"

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
