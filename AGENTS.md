# MeraTune — Agent Guidelines

## Mixpanel Analytics

Mixpanel is the product analytics tool for this project. All tracking goes through the centralized helpers: `MixpanelAnalytics` in the app and `_shared/mixpanel.ts` on the server. Value lists and funnels: `docs/MIXPANEL_TRACKING_PLAN.md`.

| Setting | Value |
|---|---|
| Platform | Android (Kotlin) + Supabase Edge Functions |
| SDK | `com.mixpanel.android:mixpanel-android:7.5.4` (pinned) |
| Token location | App: `BuildConfig.MIXPANEL_TOKEN` (override via `mixpanel.token` in `local.properties`). Server: `MIXPANEL_TOKEN` secret (project-wide), else `app_config.mixpanel_token` |
| Init | `MeraTuneApplication.onCreate()` → `MixpanelAnalytics.init()`, then registers `AnalyticsLifecycleCallbacks` |
| App helper | `app/src/main/java/com/spacewire/meratune/analytics/MixpanelAnalytics.kt` (value constants in `AnalyticsContract.kt`) |
| Server helpers | `supabase/functions/_shared/mixpanel.ts` (HTTP), `_shared/subscription-analytics.ts` (subscription prop allowlists, failure buckets, dedupe keys), `generate-ringtone/analytics.ts` (generation props, which outcomes the server reports) |
| Server senders | `cashfree-webhook`, `verify-subscription`, `generate-ringtone` |

### Tracking plan

Every app event also carries the super properties below. Server events carry `platform` = `server` and none of the app super properties; `generate-ringtone` events add `app_version` from the request body.

**Session and shell**

| Event | Trigger | Properties |
|---|---|---|
| `app_opened` | First foreground in the process (`cold`), or back after ≥ 5 min in the background (`warm`). Decided when the entry screen is created, so that screen's own events come after it. A return into a new process (killed during a UPI, Settings, picker or camera trip) is an open only after ≥ 5 min, as `warm`. Shorter trips and the incoming-call overlay don't count (`AnalyticsLifecycleCallbacks`) | `start_type`, `entry_screen` |
| `screen_viewed` | Fresh `onCreate` (no saved state) of one of the 11 slugged screens; also the `onNewIntent` re-entry of Home and the create form | `screen_name`, `previous_screen` |
| `install_attributed` | First foreground after a **fresh** install, once (Play Install Referrer). Skipped when the package was updated since install, so upgrading users never send it | `utm_source`, `utm_medium`, `utm_campaign`, `has_gclid` |
| `permission_prompt_answered` | Startup prompts (`StartupPermissionRequester`; first answer per permission, then only changes) and Set-flow prompts (`RingtoneSetController`) | `permission`, `granted`, `permanently_denied` (denied runtime permissions only), `prompt_context` (`startup` / `set_ringtone`) |
| `external_link_opened` | Terms / privacy link on phone entry, Profile help / privacy / delete-account rows. Only after the browser opened | `link` (`terms` / `privacy_policy` / `help_support` / `delete_account`), `source` (`phone_entry` / `profile`) |
| `logged_out` | `MixpanelAnalytics.logout()`, before `reset()`, only when logged in | `source` (`profile` / `subscription` / `ringtone_processing`), `reason` (`user_initiated` / `session_expired`) |

**Auth and onboarding**

| Event | Trigger | Properties |
|---|---|---|
| `otp_sent` | OTP send/resend succeeds | `is_resend`, `resend_count` (resends only) |
| `auth_failed` | Invalid phone, OTP send/resend failure, name too short, complete-signup failure | `stage` (`phone_validation` / `send_otp` / `name_validation` / `complete_signup`), `failure_reason`, `is_resend` (`send_otp`) |
| `otp_verification_failed` | OTP verify fails, or succeeds without a session (`OtpVerificationActivity`) | `failure_reason` (same vocabulary as `auth_failed`), `otp_entry_method`, `attempt` |
| `sign_up_completed` | New user completes name entry (`SignUpNameActivity`) | `sign_up_method`, `post_auth_destination` (`home` / `subscription`), `otp_entry_method` |
| `login_completed` | Returning user OTP verify succeeds (`OtpVerificationActivity`) | `sign_in_method`, `otp_entry_method` (`manual` / `sms_retriever` / `sms_consent`), `attempt`, `resend_count`, `post_auth_destination` |
| `language_selected` | Language continue tapped (double taps ignored) | `language`, `locale`, `context` (`onboarding` / `settings`), `previous_language`, `language_changed` (both only after an earlier choice) |

**Subscription (app)**

| Event | Trigger | Properties |
|---|---|---|
| `subscription_screen_viewed` | Paywall opens (not on recreation) | `previous_screen`, `user_status`, `installed_app_count`, `entry_point` |
| `paywall_dismissed` | Paywall closed with system back without converting (`onPause` + `isFinishing`, once). Not sent for the conversion, logout or not-logged-in finishes; back is blocked while verify runs | `entry_point`, `dismiss_method` (`system_back`), `attempt`, `video_completed` |
| `subscription_cta_tapped` | Try Now tapped | `payment_app`, `payment_app_installed`, `attempt`, `video_completed` |
| `payment_app_selected` | Row picked in the payment-app sheet | `payment_app`, `previous_payment_app` |
| `subscription_started` | Create-subscription API succeeds, before Cashfree checkout | `payment_app`, `auth_amount`, `recurring_amount`, `attempt` |
| `trial_payment_completed` | Trial/mandate payment verified in app (`SubscriptionActivity`), once per paywall, including after a recreation during the UPI switch or during verify (the verify is re-run) | `payment_app`, `subscription_id`, `amount`, `currency`, `attempt`, `previous_status` |
| `subscription_failed` | Precheck, create, checkout or verify failure | `stage` (`precheck` / `create` / `checkout` / `verify`), `failure_reason`, `payment_app`, `cf_error_code`, `http_status`, `cashfree_status`, `attempt` |
| `subscription_video_ended` | Paywall video completes or errors (each once per paywall) | `end_reason` (`completed` / `error`), `error_code`, `duration_ms` |

Paywall `entry_point` (`PaywallEntryPoint.derive`, from `previous_screen` and the `AuthStore` status when the paywall opens, kept in saved state): `limit_screen` from the processing screen (its Subscribe action); `win_back` for a `cancelled` / `expired` user at app open or after the onboarding screens; `onboarding` for a `none` / `trial` user there; omitted otherwise.

**Home and catalog**

| Event | Trigger | Properties |
|---|---|---|
| `home_viewed` | First successful catalog load per `HomeViewModel` (survives rotation) | `tune_count`, `category_count`, `load_ms`, `has_active_ringtone` |
| `home_load_failed` | Categories or tunes load fails | `stage` (`categories` / `tunes`), `failure_reason`, `trigger` (`initial` / `retry` / `category_change` / `reset`) |
| `tune_played` | Tune playback starts on Home | `tune_id`, `category`, `source` (`search_results` while a search query is active, else `home`), `rank`, `category_filter`, `from_search`, `is_active_ringtone` |
| `tune_play_ended` | A preview session ends (Home, song picker, Ready screen) | `source` (`home` / `search_results` / `song_picker` / `creation_flow`), `tune_id`, `end_reason` (`completed` / `stopped` / `error`), `listened_ms`, `duration_ms`, `percent_listened`, `time_to_start_ms`, `error_code` |
| `search_performed` | Search query debounced 500ms; sent early by the create CTA and the Home reset | `query_length` (trimmed), `result_count`, `category_filter` |
| `category_filtered` | Category chip selected, deselected (Home only) or "All" (Home catalog or song picker) | `category_id`, `category_name` (both omitted for `all`), `source` (`home` / `song_picker`), `selection` (`selected` / `deselected` / `all`) |
| `create_ringtone_cta_tapped` | Empty-search create CTA tapped (double taps ignored) | `source` (`search_bar`), `prefill_name_length` |
| `ringtone_replaced_externally` | Home resume finds the saved MeraTune ringtone is no longer the system default (once per saved ringtone) | `tune_id`, `personalized`, `days_since_set` |

**Create flow (app)**

`sample_id` is the picked sample's tune id (the same value as `tune_id` in this flow). `ringtone_created` and server-coded failures come from `generate-ringtone` (next tables).

| Event | Trigger | Properties |
|---|---|---|
| `ringtone_creation_started` | Continue on create form (name + language, `CreateRingtoneActivity`) | `language`, `name_length`, `entry_point` (`search_bar` / `ready_screen` / `processing`; omitted otherwise), `language_source` (`user_picked` / `profile_default` / `hindi_default` / `first_enabled`), `prefill_source` (`search_query` / `profile_name` / `retained` / `none`), `name_edited`, `time_on_form_ms` |
| `unavailable_language_tapped` | "Coming soon" language tapped (once per language per form) | `language` |
| `sample_list_viewed` | Song picker reaches a terminal load state (`ChooseSongActivity`). Silent after recreation unless the state changed | `language`, `sample_count`, `category_count`, `fallback_level` (`none` / `hindi` / `any`), `voice_filter` (`male` / `female`, omitted for all), `load_state` (`content` / `empty` / `error`), `trigger` (`initial` / `retry` / `hindi_fallback` / `restored`), `failure_reason` (error only), `requested_language` |
| `sample_previewed` | Preview starts for a card in the song picker, including a replay after it finished (before `sample_selected` on the same tap) | `sample_id`, `category`, `language`, `voice`, `rank` |
| `sample_selected` | First selection of a card in the song picker | `sample_id`, `category`, `language`, `voice`, `rank`, `voice_filter`, `category_filter` (category name) |
| `voice_filtered` | Voice chip changed in the song picker | `voice_filter` (`all` / `male` / `female`), `result_count` |
| `ringtone_generation_started` | `generate-ringtone` request posted (once per attempt, `RingtoneGenerationViewModel`; automatic busy re-posts don't fire) | `tune_id`, `sample_id`, `category`, `language`, `voice`, `name_length`, `is_retry`, `attempt`, `trigger` (`initial` / `retry` / `restored`), `client_request_id`, `previewed_count` |
| `ringtone_generation_failed` (app) | An attempt ends without a server `error_code`: user cancel (`user_cancelled`), transport or unreadable response (`network` / `timeout` / `invalid_response` / `unknown`), the 90 s busy budget used up (`timeout`); also `unauthorized`, which the server cannot attribute | `tune_id`, `sample_id`, `category`, `language`, `voice`, `failure_reason`, `http_status`, `retryable`, `can_retry`, `client_ms`, `total_client_ms`, `attempt`, `quota_used_today`, `quota_daily_limit`, `client_request_id` |
| `creation_limit_reached` | The processing screen gets `quota_exceeded` (once per failed attempt) | `limit_type` (`daily`), `quota_used_today`, `quota_daily_limit` |
| `generation_error_action_taken` | Button on the processing error screen (first tap per error) | `action` (`retry` / `login_again` / `subscribe` / `change_language` / `choose_another`), `failure_reason`, `attempt`, `tune_id`, `language` |
| `ringtone_ready_action_tapped` | Ready-screen button (first tap only) | `action` (`change_song` / `make_another` / `go_home` / `back_button`), `tune_id`, `generation_id`, `is_set` |

**Set flow and incoming calls**

| Event | Trigger | Properties |
|---|---|---|
| `ringtone_set_started` | Set flow starts (`RingtoneSetController.start`; ignored while one is in flight) | `source` (`home` / `creation_flow`), `tune_id`, `category`, `personalized`, `generation_id`, `rank`, `was_previewed` (Home only) |
| `set_mode_selected` | Continue on the set-mode sheet | `set_mode`, `source`, `tune_id`, `personalized` |
| `ringtone_set_failed` | Any terminal non-success exit of the set flow (once per flow) | `stage`, `failure_reason`, `set_mode`, `error_type`, `source`, `tune_id`, `personalized` |
| `ringtone_set` | Ringtone successfully set as default | `source`, `category`, `tune_id`, `tune_name` (omitted for personalized tunes without a `title_template`), `set_mode` (`audio_only` / `with_image_everyone` / `with_image_contact`), `generation_id`, `personalized`, `photo_source` (`camera` / `gallery`), `contact_photo_saved`, `contact_ringtone_saved`, `flow_duration_ms` |
| `call_theme_displayed` | Ringing call shows a saved call theme (first show per call). Capped | `theme_scope` (`everyone` / `contact`), `display_mode` (`overlay_requested` / `heads_up_notification`), `has_image`, `screen_locked`, `number_available` |
| `incoming_call_action_tapped` | Answer / decline on the overlay or the notification. Capped | `action` (`answer` / `decline`), `surface` (`overlay` / `notification`), `succeeded` |
| `incoming_call_overlay_displayed` | Incoming-call overlay starts. Capped | `launch_trigger` (`auto` / `notification_tap`) |

**Server: generation (`generate-ringtone`)**

Sent after the response (`EdgeRuntime.waitUntil`) with `distinct_id` = the request's user (from the session token); nothing is sent for a request that cannot be attributed. All three also carry `app_version` from the request.

| Event | Trigger | Properties |
|---|---|---|
| `name_lookup_completed` | Once per new generation request, after the name, tune and render-cache checks and before rendering; sent with the outcome. Not on idempotent replays or busy re-posts | `has_match` (the sample's own recording or any ready render of the name in this language), `match_count` (capped at 100; omitted when the count failed), `exact_match` (this sample already sings the name), `language`, `name_length` (code points), `sample_id`, `generation_id`, `client_request_id` |
| `ringtone_created` | A generation row becomes ready: fresh render, render-cache hit or the sample's own name. Once per row | `tune_id`, `sample_id`, `category`, `language`, `voice`, `cached`, `duration_ms` (audio), `latency_ms`, `duration_minutes` (generation time), `generation_id`, `client_request_id`, `quota_used_today`, `quota_daily_limit`, `source` (`creation_flow`) |
| `ringtone_generation_failed` (server) | Every error response except `unauthorized` and the busy codes the app re-posts (`generation_in_progress`, `tts_rate_limited`) | `tune_id`, `sample_id`, `category`, `voice` (before the tune loads: only the requested id), `language`, `failure_reason` (lower-cased `error_code`), `retryable`, `http_status`, `latency_ms`, `quota_used_today`, `quota_daily_limit`, `generation_id`, `client_request_id` |

**Server: subscription (`cashfree-webhook`, `verify-subscription`)**

| Event | Trigger | Properties |
|---|---|---|
| `trial_payment_succeeded` | Mandate authorised: `SUBSCRIPTION_AUTH_STATUS` SUCCESS or `verify-subscription`. Once per subscription row: whichever flips the `pending` row sends it | `subscription_id`, `amount`, `currency`, `activated_via` (`webhook` / `app_verify`), `payment_group`, `upi_handle`, `payment_app` (from `upi_handle`: `ybl` / `ibl` / `axl` → `phonepe`, `ok*` → `google_pay`, `paytm` / `pt*` → `paytm`, `upi` → `bhim`; else omitted), `cf_payment_id`, `trial_days`, `recurring_amount`, `interval_months` |
| `trial_expired` | A trial ends unconverted, once per subscription row (`trial_expired_at` guard): the row started a trial, has no recurring charge on this subscription, and is cancelled or reaches `EXPIRED` / `COMPLETED` / `CARD_EXPIRED`. `ON_HOLD` doesn't count (it can recover). `CARD_EXPIRED` can still resume, so a `card_expired` one can precede a `subscription_paid` | `reason` (`cancelled_in_trial` / `mandate_expired` / `mandate_completed` / `card_expired`), `ringtones_created` (the user's ready, non-cached generations), `days_since_trial_start`, `subscription_id` |
| `mandate_auth_failed` | `SUBSCRIPTION_AUTH_STATUS` FAILED / CANCELLED | `failure_reason`, `payment_status`, `payment_group`, `upi_handle`, `retry_attempts`, `subscription_id` |
| `subscription_paid` | Recurring autopay charge succeeds (`SUBSCRIPTION_PAYMENT_SUCCESS`, `payment_type` = `CHARGE`, amount > 0), once per `cf_payment_id` | `amount`, `currency`, `payment_type` (`recurring`), `renewal_number`, `subscription_renewal_number`, `is_first_charge`, `billing_month` (IST `YYYY-MM`), `subscription_id`, `cf_payment_id`, `retry_attempts`, `is_retry_recovery`, `payment_group`, `upi_handle`, `days_since_trial_start`, `amount_mismatch` |
| `subscription_renewal_failed` | `SUBSCRIPTION_PAYMENT_FAILED` / `SUBSCRIPTION_PAYMENT_CANCELLED` for a charge | `amount`, `currency`, `payment_status`, `failure_reason`, `retry_attempts`, `subscription_id`, `cf_payment_id` |
| `subscription_renewal_notified` | `SUBSCRIPTION_PAYMENT_NOTIFICATION_INITIATED` (pre-debit notice) | `amount`, `payment_schedule_date`, `subscription_id`, `cf_payment_id` |
| `subscription_cancelled` | `SUBSCRIPTION_STATUS_CHANGED` to `CUSTOMER_CANCELLED` / `CANCELLED`, once per subscription row | `cancellation_status`, `subscription_id`, `renewals_before_cancel`, `cancelled_by` (`customer` / `merchant`), `previous_status` (`pending` / `trial` / `active`), `cancelled_during_trial`, `user_downgraded` (from the resulting `users.status`, decided by the delivery that wins the row) |
| `subscription_status_changed` | Any other `SUBSCRIPTION_STATUS_CHANGED` status. Tracked only: access and `users.status` don't change. Repeated or out-of-order deliveries are skipped | `status`, `previous_status`, `transition`, `is_reactivation`, `next_schedule_date`, `subscription_id` |
| `subscription_refund_processed` | `SUBSCRIPTION_REFUND_STATUS` for a payment in `subscription_payments` | `refund_status`, `refund_amount`, `currency`, `refund_speed`, `original_payment_type` (`auth` / `recurring`) |

**Trial counts come from `trial_payment_succeeded`.** It is server truth and fires even when the user leaves the app before verify. `trial_payment_completed` stays for app-side attribution and fires in the same handler as Meta `Purchase` and Firebase `purchase`. Unconverted trials: `trial_expired`.

Values before app version 1.3.0: `ringtone_creation_started` and the app's old `ringtone_created` carried `voice`, `category`, `language` as **localized form labels** (e.g. "Female voice", "भक्ति"). From 1.3.0 `voice` is `male` / `female`, `category` is the database category name and `language` is the storage value (`Hindi`, `English`, …). Segment by `app_version` when comparing across the change.

Main funnel (as implemented): `otp_sent → sign_up_completed → subscription_screen_viewed → subscription_started → trial_payment_completed (app) / trial_payment_succeeded (server) → ringtone_creation_started → sample_list_viewed → sample_previewed → sample_selected → ringtone_generation_started → name_lookup_completed → ringtone_created | ringtone_generation_failed → ringtone_set → subscription_paid`. Server steps carry `platform` = `server` and no app super properties, so don't filter these funnels on `platform`, `build_type` or `user_state`.

Subscription funnel: `subscription_screen_viewed → subscription_cta_tapped → subscription_started → trial_payment_succeeded → subscription_paid`. Exits: `paywall_dismissed`, `subscription_failed`, `mandate_auth_failed`, `trial_expired`.

Create-flow funnel: `create_ringtone_cta_tapped → ringtone_creation_started → sample_list_viewed → sample_previewed → sample_selected → ringtone_generation_started → name_lookup_completed → ringtone_created | ringtone_generation_failed → ringtone_set_started → set_mode_selected → ringtone_set | ringtone_set_failed`.

**Wire when the feature ships** (planned names; no code yet):
- `locked_action_blocked` (`action`), `locked_sample_tapped` (`sample_id`), `ringtone_ready_notification_tapped` (`ringtone_id`), `ringtone_downloaded` (`ringtone_id`)
- `paywall_dismissed` `dismiss_method` = `close_button`
- `create_ringtone_cta_tapped` `source` and `ringtone_creation_started` `entry_point` = `home_button` / `trial_nudge`
- `tune_played` `source` = `name_lookup`
- `creation_limit_reached` `limit_type` = `trial` / `cycle`
- paywall `entry_point` = `locked_home`: already derived from `previous_screen` = `home`, but nothing on Home opens the paywall yet

### Automatic events

`MixpanelAPI.getInstance(context, token, superProperties, true)` turns SDK automatic events on in every build: `$ae_first_open`, `$ae_updated`, `$ae_crashed`. The super properties go into the constructor because it fires `$ae_first_open` / `$ae_updated` itself. `$ae_session` is switched off with manifest meta-data `com.mixpanel.android.MPConfig.MinimumSessionDuration=2147483647`, because the incoming-call overlay would otherwise create uncapped sessions. Use Mixpanel's computed sessions or `app_opened` instead. `setEnableLogging(BuildConfig.DEBUG)` logs to Logcat in debug builds only.

### Super properties (auto-attached)

- `platform` — `"android"`
- `app_version` — from `BuildConfig.VERSION_NAME`
- `build_type` — `debug` / `release` (filter debug traffic in reports)
- `is_logged_in` — updated on identify and logout
- `user_state` — `locked` / `trial` / `active` / `cancelled` / `expired` from the `AuthStore` status (`none`, unknown and logged out → `locked`). Set at init and updated on identify (signup, login, trial verify), at `trial_payment_completed` and at logout
- `app_language` — storage value, only once the user has picked a language

**`user_state` goes stale:** it is only as fresh as the last user object the app stored. Webhook changes (renewal, cancel, expiry) reach it only at the next login or trial verify, so a converted or cancelled user can still send `trial`. For the current state use the `subscription_status` profile property, which the server keeps up to date; `subscription_status` itself is not a super property for the same reason.

### Identity

| Action | Location | Call |
|---|---|---|
| Sign up | `SignUpNameActivity` | `identifyUser(user)` (`identify` + `people.set` / `set_once`) → `track("sign_up_completed")` |
| Login | `OtpVerificationActivity.completeLogin()` | `identifyUser(user)` → `track("login_completed")` |
| Trial payment complete | `SubscriptionActivity.onSubscriptionVerify()` | `identifyUser(user)` → `track("trial_payment_completed")` |
| Trial payment succeeded | `cashfree-webhook` `SUBSCRIPTION_AUTH_STATUS`, or `verify-subscription` | Mixpanel HTTP API, `distinct_id = user.id` |
| Charges, notices, status changes, refunds, cancel, trial expiry | `cashfree-webhook` | Mixpanel HTTP API, `distinct_id = user.id` |
| Generation outcomes | `generate-ringtone` | Mixpanel HTTP API, `distinct_id` = the session token's `user.id` (nothing when the request cannot be attributed) |
| App re-open | `MixpanelAnalytics.restoreIdentity()` | `identify(user.id)` when logged in; `reset()` when logged out but the SDK is still identified (backup restore) |
| Logout | `ProfileActivity`, `SubscriptionActivity`, `RingtoneProcessingActivity` ("Log in again", `session_expired`) | `logout(context, source, reason)` → `logged_out` → `reset()` → `ProfileStore.clearSession()` → super properties re-registered |

### User profile properties

People updates use `set`, `set_once` and `unset` only. There are no increments (`/engage` cannot dedupe); counts come from events or DB totals.

| Property | Op | When |
|---|---|---|
| `$name` | set | Identify (login / signup / trial payment), when non-blank |
| `$created`, `first_app_version` | set_once | Identify; `$created` also at `sign_up_completed` |
| `subscription_status` | set | Identify (`users.status`); `trial` at `trial_payment_completed` / `trial_payment_succeeded`; `active` at `subscription_paid`; `cancelled` at `subscription_cancelled` only when the user was downgraded |
| `phone_state_granted`, `contacts_granted`, `notifications_enabled`, `write_settings_granted`, `call_control_granted` | set | `app_opened` (identified users) |
| `initial_utm_source`, `initial_utm_medium`, `initial_utm_campaign` | set_once | `install_attributed` |
| `app_language` | set | Language selected |
| `last_ringtone_category` | set | `ringtone_created` (server) or `ringtone_set` |
| `meratune_ringtone_active` | set | `true` at `ringtone_set`, `false` at `ringtone_replaced_externally` |
| `has_call_theme` | set | `ringtone_set` |
| `trial_started_at`, `trial_ends_at` | set | `trial_payment_succeeded` |
| `autopay_enabled` | set | `true` at `trial_payment_succeeded`, `false` at `subscription_cancelled` |
| `total_renewals`, `last_billing_month`, `last_renewal_amount`, `last_payment_at`, `lifetime_revenue` | set | `subscription_paid` (totals from `subscription_payments`) |
| `next_billing_date` | set | `subscription_paid`, `subscription_renewal_notified`, `subscription_status_changed` |
| `last_payment_failed_reason`, `last_payment_failed_at` | set / unset | Set at `subscription_renewal_failed`; reason unset at `subscription_paid` |
| `last_auth_failed_reason` | set | `mandate_auth_failed` |
| `cashfree_subscription_status` | set | `subscription_status_changed`, `subscription_cancelled` |

Server profile updates send `$ignore_time: true` and `ip=0`, so a webhook doesn't bump `$last_seen` or overwrite the user's city.

### Conventions

- Event names: `snake_case`, past tense (`ringtone_created`, not `create_ringtone`)
- Property names: `snake_case`
- User ID: database primary key (`user.id.toString()`), never phone or email
- Omit properties when they have no value; do not send `null` or empty strings
- Add new events via `MixpanelAnalytics` methods, not raw SDK calls scattered in activities. Every event goes through its private `track()`, which adds a UUID `$insert_id` so SDK re-sends dedupe
- `source` is where the action happened (`home`, `search_results` = Home with a search query, `search_bar` = the Home empty-search CTA, `song_picker`, `creation_flow` = the Ready screen, `profile`, `phone_entry`, …). `previous_screen` is where the user came from; the lifecycle tracker computes it (no intent extras). `entry_point` is how a funnel screen (paywall, create form) was reached. `sample_id` is the picked sample's tune id (spec name)
- Screen slugs: `language_selection`, `phone_entry`, `otp_entry`, `name_entry`, `subscription`, `home`, `profile`, `create_form`, `song_picker`, `ringtone_processing`, `ringtone_ready`
- `failure_reason` is always a bounded snake_case value, never exception or server text (`network` and `timeout` are separate; `user_cancelled` for backing out). Enum props go through `putEnum`, which drops anything outside `[a-z0-9_]{1,64}`. `error_type` is the exception class simple name only
- Shared names: `attempt` (not `attempt_number`), `trigger` (`initial` / `retry` / `restored` / …)
- Do not send raw phone numbers, search queries, OTP values, contact names or numbers, or exception messages. Server events never carry the full UPI VPA (only `upi_handle`, the part after `@`), email or bank free text; props are allowlisted per event in `SERVER_EVENT_PROPS`
- Create flow (app and `generate-ringtone`): never send the typed, normalized or spoken name or the generated ringtone title (only `name_length`); `voice` is `male` / `female` (`Tune.voiceKey`, omitted when blank); `category` is the DB category name; `language` is the storage value; `failure_reason` is the lower-cased `GenerationErrorCode` name (`quota_exceeded`, `timeout`, …) or `user_cancelled`
- Generation outcomes are server-owned. `generate-ringtone` sends `name_lookup_completed`, `ringtone_created` and `ringtone_generation_failed` for every `error_code` it returns except `unauthorized` and the busy codes the app re-posts (`generation_in_progress`, `tts_rate_limited`). The app sends `ringtone_generation_started` and `creation_limit_reached`, and sends `ringtone_generation_failed` only for failures without a server `error_code` (`user_cancelled`, `network`, `timeout`, `invalid_response`, `unknown`) plus `unauthorized` (`GenerationErrorCode.appReportsFailure`). The app's 155 s request timeout is past the function's worst case (TTS 55 s + mix 60 s + upload 20 s) and the 150 s Edge request limit, so the app and the server never both report one attempt. Ops/cost reporting stays SQL on `generated_ringtones`
- Incoming-call events (`call_theme_displayed`, `incoming_call_action_tapped`, `incoming_call_overlay_displayed`) are sent only when logged in, share one cap of **10 per user per device-local day** (`AnalyticsDailyCap`), are flushed right after tracking, and make no people updates
- `analytics_state.xml` (referrer and permission dedupe, call-event cap, background time) and `active_ringtone.xml` are excluded from backup and device transfer, so a restored device does not suppress `install_attributed` or report a false `ringtone_replaced_externally`
- Server dedupe: `time` is the source event time (Cashfree `event_time`; generation completion or failure time) and `$insert_id` is the first 32 hex characters of SHA-256 over a semantic key (`eventKey` in `subscription-analytics.ts`; `generate-ringtone` keys on event + `generation_id`), so a retry maps to the same event. `trial_payment_succeeded` uses the Cashfree `cf_payment_id` as `$insert_id` when it matches `[A-Za-z0-9-]{1,36}`. `/track` rejects events older than 5 days

### Consent

No consent gate is implemented yet. If EU/California users are added, gate SDK initialization behind consent before tracking.

### Webhook setup

Server events need `MIXPANEL_TOKEN`, a Supabase Edge Function secret that is already set project-wide (used by `cashfree-webhook`, `verify-subscription` and now `generate-ringtone`, so no secrets change); `app_config.mixpanel_token` is the fallback. Analytics failures are logged and never fail the webhook or the generation response.

1. Apply migration `20260924120000_add_subscription_cashfree_status.sql` **first, before deploying any function**. It adds `subscriptions.cashfree_status` / `cashfree_status_at` (raw Cashfree status, for `subscription_status_changed` and `previous_status`) and `subscriptions.trial_expired_at` (the `trial_expired` once-guard), seeds `cashfree_webhook_signature_mode = log_only`, and adds `idx_ringtone_renders_name_lang_ready` for `name_lookup_completed.match_count` (build it `CONCURRENTLY` first if `ringtone_renders` has grown; see the migration header). `cashfree-webhook`, `verify-subscription` and `create-subscription` write these columns. `20260728160000_add_subscription_payments.sql` and `20260923121000_add_personalized_ringtones.sql` must already be applied.
2. Deploy `cashfree-webhook` with JWT verification off (`verify_jwt = false` in `supabase/config.toml`); Cashfree authenticates by HMAC signature. Then deploy `verify-subscription`, `create-subscription`, `send-otp`, `verify-otp`, `complete-signup` and `generate-ringtone`. Deploy `generate-ringtone` **before or with** the app release: the app no longer sends `ringtone_created`, so an app on the old function loses every create-flow outcome.
3. In Cashfree Dashboard → Webhooks (version 2025-01-01), enable `SUBSCRIPTION_AUTH_STATUS`, `SUBSCRIPTION_PAYMENT_SUCCESS`, `SUBSCRIPTION_PAYMENT_FAILED`, `SUBSCRIPTION_PAYMENT_CANCELLED`, `SUBSCRIPTION_PAYMENT_NOTIFICATION_INITIATED`, `SUBSCRIPTION_STATUS_CHANGED` and `SUBSCRIPTION_REFUND_STATUS`, and set a longer retry policy. Leave `CARD_EXPIRY_REMINDER` and `CONTROLLED_*` off (they return 200 `ignored`).
4. Signatures: a bad signature is rejected (401) in every mode. In `log_only`, deliveries without headers (or without the secret) are processed and logged. After 24–72 h of logs with verdict `valid`, run `update app_config set value = 'enforce' where key = 'cashfree_webhook_signature_mode'` (no redeploy). A missing value means `enforce`.

`subscription_paid` has no amount threshold: any `CHARGE` above 0 counts, and `amount_mismatch` flags charges that differ from `app_config.subscription_recurring_amount`.

### Verification

After adding events, confirm in Mixpanel Live View filtered by `build_type = debug` (debug builds log to Logcat); server events have no `build_type`, so find them by `platform = server` and the test user's `distinct_id`. Server tests: `deno test --allow-read supabase/functions/tests` (webhook, Mixpanel helper, generation analytics). Add Lexicon descriptions in Mixpanel Data Management.

## Meta (Facebook) App Events

Meta App Events power Facebook/Instagram ad conversion tracking and optimization. All Meta events go through the centralized helper.

| Setting | Value |
|---|---|
| Platform | Android (Kotlin) |
| SDK | `com.facebook.android:facebook-core:18.+` |
| App ID | `facebook.app_id` in `local.properties` → `@string/facebook_app_id` |
| Client token | `facebook.client_token` in `local.properties` → `@string/facebook_client_token` |
| Init | `MeraTuneApplication.onCreate()` → `MetaAnalytics.init()` |
| Helper | `app/src/main/java/com/spacewire/meratune/analytics/MetaAnalytics.kt` |

### Standard events (subscription funnel)

| Meta standard event | Mixpanel equivalent | Trigger |
|---|---|---|
| `CompleteRegistration` | `sign_up_completed` | New user completes signup (`SignUpNameActivity`) |
| `ViewContent` | `subscription_screen_viewed` | Paywall opens (`SubscriptionActivity`) |
| `InitiatedCheckout` | `subscription_started` | Create-subscription API succeeds, before UPI checkout |
| `Purchase` | `trial_payment_completed` | Trial/mandate payment verified in app |
| `Subscribe` | `subscription_paid` | Recurring autopay charge succeeds (Cashfree webhook via Conversions API) |

**App events (SDK):** `CompleteRegistration`, `ViewContent`, `InitiatedCheckout`, `Purchase` — via `MetaAnalytics.kt`.

**Server events (Conversions API):** `Subscribe` — via `cashfree-webhook` + `_shared/meta.ts`. Uses `external_id` = SHA-256(`user.id`) to match app events. Deduped with `event_id` = `cf_payment_id`.

**Properties on checkout/subscribe/purchase:** `fb_currency` / `currency` = `"INR"`, value = auth or recurring amount, `content_type` = `"subscription"`.

**Not sent to Meta:** `login_completed`, `subscription_failed`, `paywall_dismissed`, the other server subscription events (`trial_payment_succeeded`, `trial_expired`, `subscription_renewal_failed`, `subscription_cancelled`, …), engagement/ringtone events (app or `generate-ringtone`) — use Mixpanel for those.

### Identity

| Action | Location | Call |
|---|---|---|
| Sign up | `SignUpNameActivity` | `identifyUser(user)` → `trackCompleteRegistration("phone")` |
| Login | `OtpVerificationActivity.completeLogin()` | `identifyUser(user)` |
| Trial payment | `SubscriptionActivity.onSubscriptionVerify()` | `identifyUser(user)` → `trackTrialPaymentCompleted()` → Meta `Purchase` |
| Recurring payment | `cashfree-webhook` on `SUBSCRIPTION_PAYMENT_SUCCESS` | Meta Conversions API `Subscribe`, `external_id` = `user.id` |
| App re-open (logged in) | `MetaAnalytics.restoreIdentity()` | `AppEventsLogger.setUserID(user.id)` |
| Logout | `ProfileActivity`, `SubscriptionActivity`, `RingtoneProcessingActivity` ("Log in again") | `clearUserId()` |

User ID is the database primary key (`user.id.toString()`), same as Mixpanel. Never phone or email.

### Setup checklist

1. Create app at [Meta for Developers](https://developers.facebook.com/) and add the Android platform with package `com.spacewire.meratune`.
2. Add to `local.properties`:
   ```
   facebook.app_id=YOUR_APP_ID
   facebook.client_token=YOUR_CLIENT_TOKEN
   ```
3. Enable **Advertiser ID Collection** and **Automatic App Events** in Meta Events Manager (manifest flags are already set).
4. For server-side renewals, set Supabase Edge Function secrets on `cashfree-webhook`:
   ```
   META_DATASET_ID=YOUR_DATASET_ID
   META_CONVERSIONS_API_ACCESS_TOKEN=YOUR_ACCESS_TOKEN
   ```
   Dataset ID is in Events Manager → your app data source → Settings. Generate the access token under **Conversions API → Generate access token**.
5. Link the app in Meta Events Manager → Test Events (debug builds log App Events to Logcat with `LoggingBehavior.APP_EVENTS`).
6. In Ads Manager: optimize acquisition campaigns for **Purchase** (trial); optimize ROAS/retention for **Subscribe** (recurring renewals).

## Google Firebase / Google Ads

Firebase Analytics sends the `purchase` conversion that Google Ads uses to optimize app campaigns. Only this event is logged from the app; Mixpanel and Meta remain the sources for funnel and engagement events.

| Setting | Value |
|---|---|
| Platform | Android (Kotlin) |
| SDK | `com.google.firebase:firebase-analytics` via BoM `34.17.0` |
| Plugin | `com.google.gms.google-services` `4.5.0` |
| Config | `app/google-services.json` (Firebase project `mera-tune`, package `com.spacewire.meratune`) |
| Init | `MeraTuneApplication.onCreate()` → `FirebasePurchaseAnalytics.init()` |
| Helper | `app/src/main/java/com/spacewire/meratune/analytics/FirebaseAnalytics.kt` |

### Standard events (subscription funnel)

| Firebase / GA4 event | Mixpanel equivalent | Trigger |
|---|---|---|
| `purchase` | `trial_payment_completed` | Trial/mandate payment verified in app (`SubscriptionActivity`) |

**App events (SDK):** `purchase` only — via `FirebasePurchaseAnalytics.kt`.

**Not sent to Firebase:** `login_completed`, `sign_up_completed`, paywall views, checkout start, `subscription_failed`, engagement/ringtone events, or recurring autopay. Use Mixpanel (and Meta `Subscribe` for renewals) for those.

**Properties on purchase:** `value` = auth amount, `currency` = `"INR"`, `transaction_id` = Cashfree subscription id, `items` = one subscription item.

Firebase still collects default SDK events (`first_open`, `session_start`) used for Google Ads install attribution. Automatic screen reporting is disabled.

### Identity

| Action | Location | Call |
|---|---|---|
| Sign up | `SignUpNameActivity` | `identifyUser(user)` |
| Login | `OtpVerificationActivity.completeLogin()` | `identifyUser(user)` |
| Trial payment | `SubscriptionActivity.onSubscriptionVerify()` | `identifyUser(user)` → `trackTrialPaymentCompleted()` → Firebase `purchase` |
| App re-open (logged in) | `FirebasePurchaseAnalytics.restoreIdentity()` | `setUserId(user.id)` |
| Logout | `ProfileActivity`, `SubscriptionActivity`, `RingtoneProcessingActivity` ("Log in again") | `clearUserId()` |

User ID is the database primary key (`user.id.toString()`), same as Mixpanel and Meta. Never phone or email.

### Setup checklist

1. Confirm the Android app in [Firebase Con sole](https://console.firebase.google.com/) project `mera-tune` uses package `com.spacewire.meratune`, and keep `app/google-services.json` in sync if the app is re-downloaded.
2. Enable **Google Analytics** for the Firebase project if it is not already linked.
3. In Firebase / GA4: **Admin → Google Ads links** → link the Google Ads account that will run app campaigns.
4. In Google Ads: **Goals → Conversions → Summary → + New conversion action → Import → Google Analytics 4 properties → App** → import **`purchase`**. Mark it as a primary conversion for acquisition campaigns.
5. Add the app's SHA-1 (debug and Play App Signing) in Firebase Console → Project settings → Your apps, so Analytics and Ads attribution match the signed builds.
6. Debug builds: `adb shell setprop debug.firebase.analytics.app com.spacewire.meratune`, then confirm `purchase` in Logcat (`FirebasePurchase`) and in Firebase DebugView / GA4 DebugView. Purchase value is the trial auth amount in INR.
7. In Google Ads: optimize App campaigns for **Purchase** (trial). Do not import Mixpanel or Meta events into Google Ads; Firebase `purchase` is the conversion source.
