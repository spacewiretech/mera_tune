package com.spacewire.meratune.analytics

import android.content.Context
import com.mixpanel.android.mpmetrics.MixpanelAPI
import com.spacewire.meratune.BuildConfig
import com.spacewire.meratune.data.User
import com.spacewire.meratune.model.PaymentApp
import com.spacewire.meratune.ui.PlaybackSessionStats
import com.spacewire.meratune.util.AuthStore
import com.spacewire.meratune.util.ProfileStore
import com.spacewire.meratune.util.ReplacedRingtone
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * The only place that talks to the Mixpanel SDK. Every event goes through [track], which adds a
 * UUID `$insert_id` so SDK re-sends dedupe. `platform` is a super property, never an event prop.
 * People updates use set / set_once / unset only (no increments: `/engage` cannot dedupe).
 */
class MixpanelAnalytics private constructor(context: Context) {

    private val appContext = context.applicationContext

    // Super properties go in here: the SDK constructor itself fires $ae_first_open / $ae_updated.
    private val mixpanel: MixpanelAPI = MixpanelAPI.getInstance(
        appContext,
        BuildConfig.MIXPANEL_TOKEN,
        AuthStore(appContext).let { superProperties(appContext, it.isLoggedIn(), it.getStatus()) },
        true,
    )

    private val dailyCap by lazy { AnalyticsDailyCap(appContext) }

    @Volatile
    private var lastResumedScreen: String? = null

    /** Slug of the last tracked screen that resumed; the next screen's `previous_screen`. */
    val currentScreen: String?
        get() = lastResumedScreen

    init {
        mixpanel.setEnableLogging(BuildConfig.DEBUG)
        registerSuperProperties()
        restoreIdentity()
    }

    // ---------------------------------------------------------------------------------------------
    // Identity and session
    // ---------------------------------------------------------------------------------------------

    /** Every AuthStore status write is followed by this call, which keeps `user_state` current. */
    fun identifyUser(user: User) {
        mixpanel.identify(user.id.toString())
        registerSuperProperties(isLoggedIn = true, status = user.status)
        val people = mixpanel.people
        user.name?.trim()?.takeIf { it.isNotEmpty() }?.let { people.set("\$name", it) }
        user.status.takeIf { it.isNotBlank() }?.let { people.set("subscription_status", it) }
        val once = JSONObject()
        isoUtc(user.createdAt)?.let { once.put("\$created", it) }
        once.put("first_app_version", BuildConfig.VERSION_NAME)
        // First touch again now that the profile is the user's (install_attributed may have run
        // before login, on the anonymous id); set_once keeps whatever the profile already has.
        AnalyticsStateStore(appContext).installAttribution?.initialProfileProperties?.forEach { (key, value) ->
            once.putIfNotBlank(key, value)
        }
        people.setOnce(once)
    }

    /**
     * Ends the session: `logged_out` (only when logged in) is queued under the user's id before
     * the SDK reset. Meta / Firebase identity is cleared by the caller.
     */
    fun logout(
        context: Context,
        source: String = AnalyticsSource.UNKNOWN,
        reason: String = LogoutReason.USER_INITIATED,
    ) {
        if (AuthStore(context).isLoggedIn()) {
            track(
                "logged_out",
                JSONObject().apply {
                    putEnum("source", source)
                    putEnum("reason", reason)
                },
            )
        }
        mixpanel.reset()
        ProfileStore(context).clearSession()
        registerSuperProperties(isLoggedIn = false)
    }

    fun reset() {
        mixpanel.reset()
        registerSuperProperties()
    }

    fun flush() {
        mixpanel.flush()
    }

    /** State only (no event): remembers the screen for the next `previous_screen`. */
    fun onScreenResumed(screenName: String) {
        lastResumedScreen = screenName
    }

    /** State only (no event): the next screen is a session entry (no `previous_screen`). */
    fun clearPreviousScreen() {
        lastResumedScreen = null
    }

    fun trackScreenViewed(screenName: String, previousScreen: String? = currentScreen) {
        val props = JSONObject()
        props.putEnum("screen_name", screenName)
        props.putEnum("previous_screen", previousScreen)
        track("screen_viewed", props)
    }

    /**
     * Starts a session: the entry screen gets no `previous_screen`. [startType] is a [StartType]
     * value; also refreshes the permission profile properties.
     */
    fun trackAppOpened(startType: String, entryScreen: String?) {
        lastResumedScreen = null
        val props = JSONObject()
        props.putEnum("start_type", startType)
        props.putEnum("entry_screen", entryScreen)
        track("app_opened", props)

        if (mixpanel.people.isIdentified) {
            val snapshot = AnalyticsPermissions.snapshot(appContext)
            mixpanel.people.set(
                JSONObject()
                    .put("phone_state_granted", snapshot.phoneStateGranted)
                    .put("contacts_granted", snapshot.contactsGranted)
                    .put("notifications_enabled", snapshot.notificationsEnabled)
                    .put("write_settings_granted", snapshot.writeSettingsGranted)
                    .put("call_control_granted", snapshot.callControlGranted),
            )
        }
    }

    /** Once per fresh install. UTM values are campaign labels from the Play referrer. */
    /**
     * The install's store-link [attribution] (already saved by [InstallReferrerTracker]): its
     * `acquisition_source` / `utm_*` become super properties, so the rest of the journey carries
     * them, then `install_attributed`, then the first-touch `initial_*` profile properties.
     */
    fun trackInstallAttributed(attribution: InstallAttribution) {
        mixpanel.registerSuperProperties(attributionProps(attribution.eventProperties))
        val props = attributionProps(attribution.eventProperties)
        props.put("has_gclid", attribution.hasGclid)
        track("install_attributed", props)

        val once = attributionProps(attribution.initialProfileProperties)
        if (once.length() > 0) mixpanel.people.setOnce(once)
    }

    /** Fire only after `startActivity` succeeded. [link] is an [ExternalLink] value. */
    fun trackExternalLinkOpened(link: String, source: String) {
        val props = JSONObject()
        props.putEnum("link", link)
        props.putEnum("source", source)
        track("external_link_opened", props)
    }

    /** [permission] is an [AnalyticsPermissionKey]; [promptContext] a [PromptContext] value. */
    fun trackPermissionPromptAnswered(
        permission: String,
        granted: Boolean,
        promptContext: String,
        permanentlyDenied: Boolean? = null,
    ) {
        val props = JSONObject()
        props.putEnum("permission", permission)
        props.put("granted", granted)
        props.putOpt("permanently_denied", permanentlyDenied)
        props.putEnum("prompt_context", promptContext)
        track("permission_prompt_answered", props)
    }

    // ---------------------------------------------------------------------------------------------
    // Auth and onboarding
    // ---------------------------------------------------------------------------------------------

    fun trackSignUpCompleted(
        signUpMethod: String,
        postAuthDestination: String? = null,
        otpEntryMethod: String? = null,
    ) {
        val props = JSONObject()
        props.putEnum("sign_up_method", signUpMethod)
        props.putEnum("post_auth_destination", postAuthDestination)
        props.putEnum("otp_entry_method", otpEntryMethod)
        track("sign_up_completed", props)
        // No-op when identifyUser already stored the server's created_at.
        mixpanel.people.setOnce("\$created", ISO_UTC.format(Instant.now()))
    }

    fun trackLoginCompleted(
        signInMethod: String,
        otpEntryMethod: String? = null,
        attempt: Int? = null,
        resendCount: Int? = null,
        postAuthDestination: String? = null,
    ) {
        val props = JSONObject()
        props.putEnum("sign_in_method", signInMethod)
        props.putEnum("otp_entry_method", otpEntryMethod)
        props.putOpt("attempt", attempt)
        props.putOpt("resend_count", resendCount)
        props.putEnum("post_auth_destination", postAuthDestination)
        track("login_completed", props)
    }

    fun trackOtpSent(isResend: Boolean, resendCount: Int? = null) {
        val props = JSONObject()
        props.put("is_resend", isResend)
        props.putOpt("resend_count", resendCount)
        track("otp_sent", props)
    }

    /**
     * Every auth stage except OTP verification ([trackOtpVerificationFailed]). [failureReason] is
     * the server `error_code` or a client value (`network`, `bad_response`,
     * `invalid_phone_format`, `name_too_short`); never exception or server text.
     */
    fun trackAuthFailed(
        stage: String,
        failureReason: String,
        isResend: Boolean? = null,
        otpEntryMethod: String? = null,
    ) {
        val props = JSONObject()
        props.putEnum("stage", stage)
        props.putEnum("failure_reason", failureReason)
        props.putOpt("is_resend", isResend)
        props.putEnum("otp_entry_method", otpEntryMethod)
        track("auth_failed", props)
    }

    /** The OTP was rejected or verify failed. Same [failureReason] vocabulary as [trackAuthFailed]. */
    fun trackOtpVerificationFailed(failureReason: String, otpEntryMethod: String?, attempt: Int?) {
        val props = JSONObject()
        props.putEnum("failure_reason", failureReason)
        props.putEnum("otp_entry_method", otpEntryMethod)
        props.putOpt("attempt", attempt)
        track("otp_verification_failed", props)
    }

    /** Also sets the `app_language` super and profile property to [language]. */
    fun trackLanguageSelected(
        language: String,
        locale: String,
        context: String,
        previousLanguage: String? = null,
        languageChanged: Boolean? = null,
    ) {
        val props = JSONObject()
        props.putIfNotBlank("language", language)
        props.putIfNotBlank("locale", locale)
        props.putEnum("context", context)
        props.putIfNotBlank("previous_language", previousLanguage)
        props.putOpt("language_changed", languageChanged)
        track("language_selected", props)
        registerAppLanguage(language)
        if (language.isNotBlank()) mixpanel.people.set("app_language", language)
    }

    // ---------------------------------------------------------------------------------------------
    // Subscription
    // ---------------------------------------------------------------------------------------------

    /**
     * `previous_screen` is added automatically. [entryPoint] is a [PaywallEntryPoint] value.
     * [isTrial] is the offer the paywall shows as it opens: the local guess from the stored status,
     * before the server's preview answers.
     */
    fun trackSubscriptionScreenViewed(
        userStatus: String? = null,
        installedAppCount: Int? = null,
        entryPoint: String? = null,
        isTrial: Boolean? = null,
    ) {
        val props = JSONObject()
        props.putEnum("previous_screen", currentScreen)
        props.putEnum("user_status", userStatus)
        props.putOpt("installed_app_count", installedAppCount)
        props.putEnum("entry_point", entryPoint)
        props.putOpt("is_trial", isTrial)
        track("subscription_screen_viewed", props)
    }

    /** The paywall finished without converting. [dismissMethod] is a [PaywallDismissMethod] value. */
    fun trackPaywallDismissed(entryPoint: String?, dismissMethod: String, attempt: Int, videoCompleted: Boolean) {
        val props = JSONObject()
        props.putEnum("entry_point", entryPoint)
        props.putEnum("dismiss_method", dismissMethod)
        props.put("attempt", attempt)
        props.put("video_completed", videoCompleted)
        track("paywall_dismissed", props)
    }

    fun trackSubscriptionCtaTapped(
        paymentApp: PaymentApp,
        paymentAppInstalled: Boolean,
        attempt: Int,
        videoCompleted: Boolean? = null,
    ) {
        val props = JSONObject()
        props.put("payment_app", paymentApp.analyticsSlug())
        props.put("payment_app_installed", paymentAppInstalled)
        props.put("attempt", attempt)
        props.putOpt("video_completed", videoCompleted)
        track("subscription_cta_tapped", props)
    }

    fun trackPaymentAppSelected(paymentApp: PaymentApp, previousPaymentApp: PaymentApp?) {
        val props = JSONObject()
        props.put("payment_app", paymentApp.analyticsSlug())
        previousPaymentApp?.let { props.put("previous_payment_app", it.analyticsSlug()) }
        track("payment_app_selected", props)
    }

    /**
     * Create-subscription succeeded, before the Cashfree checkout opens (named
     * `subscription_started` until 2026-09-28). [authAmount] / [recurringAmount] are the created
     * mandate's (the server's); [isTrial] is `false` for the paid offer (no ₹3 trial).
     */
    fun trackSubscriptionInitiated(
        paymentApp: PaymentApp,
        authAmount: Double,
        recurringAmount: Double,
        isTrial: Boolean,
        attempt: Int? = null,
    ) {
        val props = JSONObject()
        props.put("payment_app", paymentApp.analyticsSlug())
        props.put("auth_amount", authAmount)
        props.put("recurring_amount", recurringAmount)
        props.putOpt("attempt", attempt)
        props.put("user_state", currentUserState())
        props.put("is_trial", isTrial)
        track("subscription_initiated", props)
    }

    /**
     * The mandate verified in the app. [amount] is its auth amount (₹3 trial, or the paid plan's
     * first month); [isTrial] `false` makes the user `active` (not `trial`) in Mixpanel.
     */
    fun trackTrialPaymentCompleted(
        paymentApp: PaymentApp,
        subscriptionId: String,
        amount: Double,
        isTrial: Boolean,
        attempt: Int? = null,
        previousStatus: String? = null,
    ) {
        val props = JSONObject()
        props.put("payment_app", paymentApp.analyticsSlug())
        props.putIfNotBlank("subscription_id", subscriptionId)
        props.put("amount", amount)
        props.put("currency", "INR")
        props.putOpt("attempt", attempt)
        props.putEnum("previous_status", previousStatus)
        props.put("is_trial", isTrial)
        track("trial_payment_completed", props)
        val userState = if (isTrial) UserState.TRIAL else UserState.ACTIVE
        mixpanel.people.set("subscription_status", userState)
        registerUserState(userState)
    }

    /**
     * [failureReason] must be a bounded snake_case value (see the tracking plan); anything else is
     * dropped. [cfErrorCode] / [cashfreeStatus] are lower-cased Cashfree codes. [authAmount] /
     * [recurringAmount] / [isTrial] are the offer the paywall shows at the failure.
     */
    fun trackSubscriptionFailed(
        stage: String,
        failureReason: String?,
        paymentApp: PaymentApp?,
        authAmount: Double,
        recurringAmount: Double,
        isTrial: Boolean,
        cfErrorCode: String? = null,
        httpStatus: Int? = null,
        cashfreeStatus: String? = null,
        attempt: Int? = null,
    ) {
        val props = JSONObject()
        props.putEnum("stage", stage)
        props.putEnum("failure_reason", failureReason)
        paymentApp?.let { props.put("payment_app", it.analyticsSlug()) }
        props.putEnum("cf_error_code", cfErrorCode)
        props.putOpt("http_status", httpStatus)
        props.putEnum("cashfree_status", cashfreeStatus)
        props.putOpt("attempt", attempt)
        props.put("auth_amount", authAmount)
        props.put("recurring_amount", recurringAmount)
        props.put("user_state", currentUserState())
        props.put("is_trial", isTrial)
        track("subscription_failed", props)
    }

    fun trackSubscriptionVideoEnded(endReason: String, errorCode: String? = null, durationMs: Long? = null) {
        val props = JSONObject()
        props.putEnum("end_reason", endReason)
        props.putEnum("error_code", errorCode)
        props.putOpt("duration_ms", durationMs)
        track("subscription_video_ended", props)
    }

    // ---------------------------------------------------------------------------------------------
    // Personalized ringtone flow. Never pass the typed name or the generated title into these.
    // `voice` is "male" | "female" (blank omitted); `category` is the DB category name.
    // ---------------------------------------------------------------------------------------------

    /** Continue on the create form (name + language). [entryPoint] is a [CreationEntryPoint] value. */
    fun trackRingtoneCreationStarted(
        language: String,
        nameLength: Int,
        languageSource: String? = null,
        prefillSource: String? = null,
        nameEdited: Boolean? = null,
        timeOnFormMs: Long? = null,
        entryPoint: String? = null,
    ) {
        val props = JSONObject()
        props.putIfNotBlank("language", language)
        props.put("name_length", nameLength)
        props.putEnum("entry_point", entryPoint)
        props.putEnum("language_source", languageSource)
        props.putEnum("prefill_source", prefillSource)
        props.putOpt("name_edited", nameEdited)
        props.putOpt("time_on_form_ms", timeOnFormMs)
        track("ringtone_creation_started", props)
    }

    /** Tap on a "coming soon" language on the create form. */
    fun trackUnavailableLanguageTapped(language: String) {
        val props = JSONObject()
        props.putIfNotBlank("language", language)
        track("unavailable_language_tapped", props)
    }

    /** Song picker reached a terminal load state (content, empty or error). */
    fun trackSampleListViewed(
        language: String,
        sampleCount: Int,
        categoryCount: Int,
        fallbackLevel: String,
        voiceFilter: String?,
        loadState: String? = null,
        trigger: String? = null,
        failureReason: String? = null,
        requestedLanguage: String? = null,
    ) {
        val props = JSONObject()
        props.putIfNotBlank("language", language)
        props.put("sample_count", sampleCount)
        props.put("category_count", categoryCount)
        props.putEnum("fallback_level", fallbackLevel)
        props.putEnum("voice_filter", voiceFilter)
        props.putEnum("load_state", loadState)
        props.putEnum("trigger", trigger)
        props.putEnum("failure_reason", failureReason)
        props.putIfNotBlank("requested_language", requestedLanguage)
        track("sample_list_viewed", props)
    }

    /** Preview playback started for a card in the song picker. [sampleId] is the card's tune id. */
    fun trackSamplePreviewed(
        sampleId: String,
        category: String,
        language: String,
        voice: String,
        rank: Int,
    ) {
        val props = JSONObject()
        props.putIfNotBlank("sample_id", sampleId)
        props.putIfNotBlank("category", category)
        props.putIfNotBlank("language", language)
        props.putEnum("voice", voice)
        props.put("rank", rank)
        track("sample_previewed", props)
    }

    /** First selection of a card in the song picker. [sampleId] is the card's tune id. */
    fun trackSampleSelected(
        sampleId: String,
        category: String,
        language: String,
        voice: String,
        rank: Int,
        voiceFilter: String?,
        categoryFilter: String?,
    ) {
        val props = JSONObject()
        props.putIfNotBlank("sample_id", sampleId)
        props.putIfNotBlank("category", category)
        props.putIfNotBlank("language", language)
        props.putEnum("voice", voice)
        props.put("rank", rank)
        props.putEnum("voice_filter", voiceFilter)
        props.putIfNotBlank("category_filter", categoryFilter)
        track("sample_selected", props)
    }

    /** Voice chip tap in the song picker. [voiceFilter] is `all` / `male` / `female`. */
    fun trackVoiceFiltered(voiceFilter: String, resultCount: Int) {
        val props = JSONObject()
        props.putEnum("voice_filter", voiceFilter)
        props.put("result_count", resultCount)
        track("voice_filtered", props)
    }

    /**
     * One generate-ringtone attempt is about to be posted (once per attempt, including retries).
     * [sampleId] is the picked sample (same id as [tuneId]).
     */
    fun trackRingtoneGenerationStarted(
        tuneId: String,
        sampleId: String,
        category: String,
        language: String,
        voice: String,
        nameLength: Int,
        isRetry: Boolean,
        attempt: Int? = null,
        trigger: String? = null,
        clientRequestId: String? = null,
        previewedCount: Int? = null,
    ) {
        val props = JSONObject()
        props.putIfNotBlank("tune_id", tuneId)
        props.putIfNotBlank("sample_id", sampleId)
        props.putIfNotBlank("category", category)
        props.putIfNotBlank("language", language)
        props.putEnum("voice", voice)
        props.put("name_length", nameLength)
        props.put("is_retry", isRetry)
        props.putOpt("attempt", attempt)
        props.putEnum("trigger", trigger)
        props.putIfNotBlank("client_request_id", clientRequestId)
        props.putOpt("previewed_count", previewedCount)
        track("ringtone_generation_started", props)
    }

    /**
     * Generation ended without a ringtone for an app-side cause only (the server tracks every
     * `error_code` it returns, and `ringtone_created`). [failureReason] is a lower-cased
     * `GenerationErrorCode` name or `user_cancelled`.
     */
    fun trackRingtoneGenerationFailed(
        tuneId: String,
        sampleId: String,
        category: String,
        language: String,
        voice: String,
        failureReason: String,
        httpStatus: Int?,
        retryable: Boolean,
        clientMs: Long,
        attempt: Int? = null,
        canRetry: Boolean? = null,
        totalClientMs: Long? = null,
        quotaUsedToday: Int? = null,
        quotaDailyLimit: Int? = null,
        clientRequestId: String? = null,
    ) {
        val props = JSONObject()
        props.putIfNotBlank("tune_id", tuneId)
        props.putIfNotBlank("sample_id", sampleId)
        props.putIfNotBlank("category", category)
        props.putIfNotBlank("language", language)
        props.putEnum("voice", voice)
        props.putEnum("failure_reason", failureReason)
        props.putOpt("http_status", httpStatus)
        props.put("retryable", retryable)
        props.put("client_ms", clientMs)
        props.putOpt("attempt", attempt)
        props.putOpt("can_retry", canRetry)
        props.putOpt("total_client_ms", totalClientMs)
        props.putOpt("quota_used_today", quotaUsedToday)
        props.putOpt("quota_daily_limit", quotaDailyLimit)
        props.putIfNotBlank("client_request_id", clientRequestId)
        track("ringtone_generation_failed", props)
    }

    /**
     * The creation limit is shown: the processing screen gets `QUOTA_EXCEEDED` ([source]
     * [AnalyticsSource.RINGTONE_PROCESSING]), or a Home create CTA opens the limit sheet
     * ([AnalyticsSource.HOME]). [limitType] is a [CreationLimitType] value, [plan] the quota's
     * `plan` (`trial` / `member` / `default`, omitted when blank). [quotaUsedToday] /
     * [quotaDailyLimit] are the period's used / limit (the month for members), under their
     * pre-plan property names.
     */
    fun trackCreationLimitReached(
        limitType: String,
        plan: String?,
        source: String,
        quotaUsedToday: Int?,
        quotaDailyLimit: Int?,
    ) {
        val props = JSONObject()
        props.putEnum("limit_type", limitType)
        props.putEnum("plan", plan)
        props.putEnum("source", source)
        props.putOpt("quota_used_today", quotaUsedToday)
        props.putOpt("quota_daily_limit", quotaDailyLimit)
        track("creation_limit_reached", props)
    }

    /** Processing error-state button tap. [action] is a [GenerationErrorAction] value. */
    fun trackGenerationErrorActionTaken(
        action: String,
        failureReason: String,
        tuneId: String,
        language: String,
        attempt: Int? = null,
    ) {
        val props = JSONObject()
        props.putEnum("action", action)
        props.putEnum("failure_reason", failureReason)
        props.putOpt("attempt", attempt)
        props.putIfNotBlank("tune_id", tuneId)
        props.putIfNotBlank("language", language)
        track("generation_error_action_taken", props)
    }

    /** Ready-screen button tap. [action] is a [ReadyAction] value. */
    fun trackRingtoneReadyActionTapped(action: String, tuneId: String, generationId: String?, isSet: Boolean) {
        val props = JSONObject()
        props.putEnum("action", action)
        props.putIfNotBlank("tune_id", tuneId)
        props.putIfNotBlank("generation_id", generationId)
        props.put("is_set", isSet)
        track("ringtone_ready_action_tapped", props)
    }

    // ---------------------------------------------------------------------------------------------
    // Set flow
    // ---------------------------------------------------------------------------------------------

    fun trackRingtoneSetStarted(
        source: String,
        tuneId: String,
        category: String,
        personalized: Boolean,
        generationId: String? = null,
        rank: Int? = null,
        wasPreviewed: Boolean? = null,
    ) {
        val props = JSONObject()
        props.putEnum("source", source)
        props.putIfNotBlank("tune_id", tuneId)
        props.putIfNotBlank("category", category)
        props.put("personalized", personalized)
        props.putIfNotBlank("generation_id", generationId)
        props.putOpt("rank", rank)
        props.putOpt("was_previewed", wasPreviewed)
        track("ringtone_set_started", props)
    }

    fun trackSetModeSelected(setMode: String, source: String, tuneId: String, personalized: Boolean) {
        val props = JSONObject()
        props.putEnum("set_mode", setMode)
        props.putEnum("source", source)
        props.putIfNotBlank("tune_id", tuneId)
        props.put("personalized", personalized)
        track("set_mode_selected", props)
    }

    /**
     * Terminal non-success exit of the Set flow. [stage] is a [SetFailureStage] value,
     * [failureReason] a [FailureReason] / [SetFailureReason] value, [errorType] the exception's
     * class simple name only.
     */
    fun trackRingtoneSetFailed(
        stage: String,
        failureReason: String,
        source: String,
        tuneId: String,
        personalized: Boolean,
        setMode: String? = null,
        errorType: String? = null,
    ) {
        val props = JSONObject()
        props.putEnum("stage", stage)
        props.putEnum("failure_reason", failureReason)
        props.putEnum("set_mode", setMode)
        props.putErrorType(errorType)
        props.putEnum("source", source)
        props.putIfNotBlank("tune_id", tuneId)
        props.put("personalized", personalized)
        track("ringtone_set_failed", props)
    }

    fun trackRingtoneSet(
        source: String,
        category: String,
        tuneId: String,
        tuneName: String,
        setMode: String = "audio_only",
        generationId: String? = null,
        personalized: Boolean = false,
        photoSource: String? = null,
        contactPhotoSaved: Boolean? = null,
        contactRingtoneSaved: Boolean? = null,
        flowDurationMs: Long? = null,
        hasCallTheme: Boolean? = null,
    ) {
        val props = JSONObject()
        props.putEnum("source", source)
        props.putIfNotBlank("category", category)
        props.putIfNotBlank("tune_id", tuneId)
        props.putIfNotBlank("tune_name", tuneName)
        props.putEnum("set_mode", setMode)
        props.putIfNotBlank("generation_id", generationId)
        props.put("personalized", personalized)
        props.putEnum("photo_source", photoSource)
        props.putOpt("contact_photo_saved", contactPhotoSaved)
        props.putOpt("contact_ringtone_saved", contactRingtoneSaved)
        props.putOpt("flow_duration_ms", flowDurationMs)
        track("ringtone_set", props)

        val people = JSONObject().put("meratune_ringtone_active", true)
        if (category.isNotBlank()) people.put("last_ringtone_category", category)
        people.putOpt("has_call_theme", hasCallTheme)
        mixpanel.people.set(people)
    }

    // ---------------------------------------------------------------------------------------------
    // Incoming call (background). Logged-in only, capped per user per day, flushed immediately.
    // Each returns whether the event was tracked. No people updates.
    // ---------------------------------------------------------------------------------------------

    fun trackCallThemeDisplayed(
        themeScope: String,
        displayMode: String,
        hasImage: Boolean,
        screenLocked: Boolean,
        numberAvailable: Boolean,
    ): Boolean {
        val props = JSONObject()
        props.putEnum("theme_scope", themeScope)
        props.putEnum("display_mode", displayMode)
        props.put("has_image", hasImage)
        props.put("screen_locked", screenLocked)
        props.put("number_available", numberAvailable)
        return trackCapped("call_theme_displayed", props)
    }

    /** [action] is an [IncomingCallAction], [surface] a [CallSurface] value. */
    fun trackIncomingCallActionTapped(action: String, surface: String, succeeded: Boolean): Boolean {
        val props = JSONObject()
        props.putEnum("action", action)
        props.putEnum("surface", surface)
        props.put("succeeded", succeeded)
        return trackCapped("incoming_call_action_tapped", props)
    }

    /** [launchTrigger] is a [LaunchTrigger] value. */
    fun trackIncomingCallOverlayDisplayed(launchTrigger: String): Boolean {
        val props = JSONObject()
        props.putEnum("launch_trigger", launchTrigger)
        return trackCapped("incoming_call_overlay_displayed", props)
    }

    // ---------------------------------------------------------------------------------------------
    // Home and catalog
    // ---------------------------------------------------------------------------------------------

    fun trackHomeViewed(
        tuneCount: Int,
        categoryCount: Int,
        loadMs: Long? = null,
        hasActiveRingtone: Boolean? = null,
    ) {
        val props = JSONObject()
        props.put("tune_count", tuneCount)
        props.put("category_count", categoryCount)
        props.putOpt("load_ms", loadMs)
        props.putOpt("has_active_ringtone", hasActiveRingtone)
        track("home_viewed", props)
    }

    /** [stage] is `categories` / `tunes`; [failureReason] comes from `LoadErrorMapper.reason`. */
    fun trackHomeLoadFailed(stage: String, failureReason: String, trigger: String) {
        val props = JSONObject()
        props.putEnum("stage", stage)
        props.putEnum("failure_reason", failureReason)
        props.putEnum("trigger", trigger)
        track("home_load_failed", props)
    }

    fun trackTunePlayed(
        tuneId: String,
        category: String,
        source: String,
        rank: Int? = null,
        categoryFilter: String? = null,
        fromSearch: Boolean? = null,
        isActiveRingtone: Boolean? = null,
    ) {
        val props = JSONObject()
        props.putIfNotBlank("tune_id", tuneId)
        props.putIfNotBlank("category", category)
        props.putEnum("source", source)
        props.putOpt("rank", rank)
        props.putIfNotBlank("category_filter", categoryFilter)
        props.putOpt("from_search", fromSearch)
        props.putOpt("is_active_ringtone", isActiveRingtone)
        track("tune_played", props)
    }

    /** One preview session ended; `percent_listened` is derived from [listenedMs] / [durationMs]. */
    fun trackTunePlayEnded(
        source: String,
        tuneId: String,
        endReason: String,
        listenedMs: Long,
        durationMs: Long?,
        timeToStartMs: Long?,
        errorCode: String? = null,
    ) {
        val props = JSONObject()
        props.putEnum("source", source)
        props.putIfNotBlank("tune_id", tuneId)
        props.putEnum("end_reason", endReason)
        props.put("listened_ms", listenedMs.coerceAtLeast(0L))
        val duration = durationMs?.takeIf { it > 0L }
        props.putOpt("duration_ms", duration)
        duration?.let {
            props.put("percent_listened", (listenedMs.coerceAtLeast(0L) * 100L / it).coerceIn(0L, 100L).toInt())
        }
        props.putOpt("time_to_start_ms", timeToStartMs)
        props.putEnum("error_code", errorCode)
        track("tune_play_ended", props)
    }

    /** [tuneId] defaults to the player id; pass the base tune id when they differ (Ready screen). */
    fun trackTunePlayEnded(source: String, stats: PlaybackSessionStats, tuneId: String = stats.id) {
        trackTunePlayEnded(
            source = source,
            tuneId = tuneId,
            endReason = stats.endReason,
            listenedMs = stats.listenedMs,
            durationMs = stats.durationMs,
            timeToStartMs = stats.timeToStartMs,
            errorCode = stats.errorCode,
        )
    }

    /** [queryLength] is the trimmed length; the query itself is never sent. */
    fun trackSearchPerformed(queryLength: Int, resultCount: Int, categoryFilter: String? = null) {
        val props = JSONObject()
        props.put("query_length", queryLength)
        props.put("result_count", resultCount)
        props.putIfNotBlank("category_filter", categoryFilter)
        track("search_performed", props)
    }

    /**
     * [source] is `"home"` (catalog chips) or `"song_picker"` (create-flow chips); [selection] is a
     * [FilterSelection] value. Ids / names may be omitted for deselect and "All".
     */
    fun trackCategoryFiltered(
        categoryId: String?,
        categoryName: String?,
        source: String = AnalyticsSource.HOME,
        selection: String? = null,
    ) {
        val props = JSONObject()
        props.putIfNotBlank("category_id", categoryId)
        props.putIfNotBlank("category_name", categoryName)
        props.putEnum("source", source)
        props.putEnum("selection", selection)
        track("category_filtered", props)
    }

    fun trackCreateRingtoneCtaTapped(source: String, prefillNameLength: Int) {
        val props = JSONObject()
        props.putEnum("source", source)
        props.put("prefill_name_length", prefillNameLength)
        track("create_ringtone_cta_tapped", props)
    }

    /** The saved MeraTune ringtone is no longer the system default (once per saved ringtone). */
    fun trackRingtoneReplacedExternally(tuneId: String, personalized: Boolean?, daysSinceSet: Int?) {
        val props = JSONObject()
        props.putIfNotBlank("tune_id", tuneId)
        props.putOpt("personalized", personalized)
        props.putOpt("days_since_set", daysSinceSet)
        track("ringtone_replaced_externally", props)
        mixpanel.people.set("meratune_ringtone_active", false)
    }

    fun trackRingtoneReplacedExternally(replaced: ReplacedRingtone) {
        trackRingtoneReplacedExternally(replaced.tuneId, replaced.personalized, replaced.daysSinceSet)
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    /** The only call into `mixpanel.track`. */
    private fun track(event: String, props: JSONObject) {
        props.put("\$insert_id", UUID.randomUUID().toString())
        mixpanel.track(event, props)
    }

    private fun trackCapped(event: String, props: JSONObject): Boolean {
        val authStore = AuthStore(appContext)
        if (!authStore.isLoggedIn()) return false
        if (!dailyCap.tryAcquire(authStore.getUserId())) return false
        track(event, props)
        mixpanel.flush()
        return true
    }

    /** [status] defaults to the stored AuthStore status. */
    private fun registerSuperProperties(
        isLoggedIn: Boolean = AuthStore(appContext).isLoggedIn(),
        status: String? = null,
    ) {
        val superProps = superProperties(appContext, isLoggedIn, status ?: AuthStore(appContext).getStatus())
        mixpanel.registerSuperProperties(superProps)
        if (!superProps.has("app_language")) mixpanel.unregisterSuperProperty("app_language")
    }

    /**
     * `user_state` from the stored session, derived like the super property. Sent explicitly on
     * the subscription events: the super property is only refreshed on identify / logout.
     */
    private fun currentUserState(): String =
        AuthStore(appContext).let { UserState.derive(it.isLoggedIn(), it.getStatus()) }

    private fun registerUserState(userState: String) {
        mixpanel.registerSuperProperties(JSONObject().put("user_state", userState))
    }

    private fun registerAppLanguage(language: String?) {
        if (language.isNullOrBlank()) {
            mixpanel.unregisterSuperProperty("app_language")
        } else {
            mixpanel.registerSuperProperties(JSONObject().put("app_language", language))
        }
    }

    /** Also covers a backup restore that brought back Mixpanel's identity without a session. */
    private fun restoreIdentity() {
        val authStore = AuthStore(appContext)
        if (authStore.isLoggedIn()) {
            mixpanel.identify(authStore.getUserId().toString())
        } else if (mixpanel.people.isIdentified) {
            reset()
        }
    }

    private fun PaymentApp.analyticsSlug(): String = PaymentAppSlug.of(this)

    private fun attributionProps(values: Map<String, String>): JSONObject =
        JSONObject().apply { values.forEach { (key, value) -> putIfNotBlank(key, value.take(MAX_TEXT_LENGTH)) } }

    /** Convention: never send null or blank string properties; omit them instead. */
    private fun JSONObject.putIfNotBlank(key: String, value: String?) {
        if (!value.isNullOrBlank()) put(key, value)
    }

    /** Bounded values: lower-cased, kept only when they match `[a-z0-9_]{1,64}`, else omitted. */
    private fun JSONObject.putEnum(key: String, value: String?) {
        val normalized = value?.trim()?.lowercase(Locale.ROOT) ?: return
        if (ENUM_PATTERN.matches(normalized)) put(key, normalized)
    }

    private fun JSONObject.putErrorType(value: String?) {
        val trimmed = value?.trim() ?: return
        if (ERROR_TYPE_PATTERN.matches(trimmed)) put("error_type", trimmed)
    }

    /** Mixpanel dates: ISO 8601 in UTC without offset. */
    private fun isoUtc(timestamp: String?): String? {
        val raw = timestamp?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val instant = runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(raw) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC) }.getOrNull()
            ?: return null
        return ISO_UTC.format(instant)
    }

    companion object {
        private const val PLATFORM = "android"
        private const val MAX_TEXT_LENGTH = 255
        private val ENUM_PATTERN = Regex("[a-z0-9_]{1,64}")
        private val ERROR_TYPE_PATTERN = Regex("[A-Za-z0-9_]{1,64}")
        private val ISO_UTC: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC)

        /**
         * `app_language` only once the user has picked a language. `user_state` is only as fresh as
         * the last user object the app stored; logged out is `locked`. The install's store-link
         * attribution (`acquisition_source`, `utm_*`) once read, so a logout's reset keeps it.
         */
        private fun superProperties(context: Context, isLoggedIn: Boolean, status: String?): JSONObject {
            val props = JSONObject()
                .put("platform", PLATFORM)
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("build_type", BuildConfig.BUILD_TYPE)
                .put("is_logged_in", isLoggedIn)
                .put("user_state", UserState.derive(isLoggedIn, status))
            val profileStore = ProfileStore(context)
            if (profileStore.hasSelectedLanguage()) {
                profileStore.getProfile().selectedLanguage.takeIf { it.isNotBlank() }
                    ?.let { props.put("app_language", it) }
            }
            AnalyticsStateStore(context).installAttribution?.eventProperties?.forEach { (key, value) ->
                if (value.isNotBlank()) props.put(key, value.take(MAX_TEXT_LENGTH))
            }
            return props
        }

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
