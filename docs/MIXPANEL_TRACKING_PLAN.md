# MeraTune Mixpanel documentation

Product analytics for the MeraTune Android app. This document covers every Mixpanel event that is implemented today: app SDK events, SDK automatic events, server events from the Cashfree webhook, `verify-subscription` and `generate-ringtone`, identity, user profiles, conversion funnels, how Mixpanel relates to Meta and Firebase, and the events to wire when planned features ship.

**Last updated:** 25 September 2026 (matches current codebase, including the UI refresh; see [UI refresh changes](#ui-refresh-changes))

| Item | Value |
|------|--------|
| Product | MeraTune |
| Mixpanel SDK (Android) | `com.mixpanel.android:mixpanel-android:7.5.4` (pinned) |
| App helper | `app/src/main/java/com/spacewire/meratune/analytics/MixpanelAnalytics.kt` |
| App value constants | `app/src/main/java/com/spacewire/meratune/analytics/AnalyticsContract.kt` |
| App shell tracker | `app/src/main/java/com/spacewire/meratune/analytics/AnalyticsLifecycleCallbacks.kt` |
| Init | `MeraTuneApplication.onCreate()` → `MixpanelAnalytics.init()`, then `registerActivityLifecycleCallbacks(AnalyticsLifecycleCallbacks(…))` |
| Project token (app) | `BuildConfig.MIXPANEL_TOKEN` from `mixpanel.token` in `local.properties` |
| Server helpers | `supabase/functions/_shared/mixpanel.ts` (HTTP), `supabase/functions/_shared/subscription-analytics.ts` (allowlists, buckets, dedupe keys), `supabase/functions/generate-ringtone/analytics.ts` (generation props and reporting rules) |
| Server senders | `supabase/functions/cashfree-webhook/index.ts`, `supabase/functions/verify-subscription/index.ts`, `supabase/functions/generate-ringtone/index.ts` |
| `distinct_id` | Database primary key `users.id` as a string. Never phone or email. |

All new Mixpanel tracking must go through `MixpanelAnalytics` in the app or `_shared/mixpanel.ts` on the server. Do not call the Mixpanel SDK or HTTP API from random activities or functions.

---

## Architecture

```
Android app                              Cashfree
───────────                              ────────
AnalyticsLifecycleCallbacks              Webhooks                 App verify call
  app_opened, screen_viewed                │                          │
MixpanelAnalytics.kt                       ▼                          ▼
  private track() + $insert_id           cashfree-webhook         verify-subscription
  people set / set_once / unset          ├─ Mixpanel /track       └─ Mixpanel /track (trial_payment_succeeded)
  identify(user.id)                      ├─ Mixpanel /engage         + /engage
        │                                └─ Meta Conversions API (Subscribe)
        │ POST generate-ringtone
        ▼
generate-ringtone ── after the response (EdgeRuntime.waitUntil):
                     Mixpanel /track (name_lookup_completed, ringtone_created, ringtone_generation_failed)
                     + /engage (last_ringtone_category)
```

| Source | Events | Transport |
|--------|--------|-----------|
| Android app | 46 events | Mixpanel Android SDK |
| Android SDK automatic | 3 (`$ae_first_open`, `$ae_updated`, `$ae_crashed`) | Mixpanel Android SDK |
| Server, subscription | 9 events (`trial_payment_succeeded` also from `verify-subscription`) | Mixpanel HTTP API (`https://api.mixpanel.com/track`, `/engage`) |
| Server, generation | 3 events (`ringtone_generation_failed` is also an app event) | Mixpanel HTTP API |

**Generation outcomes are server-owned.** `generate-ringtone` sends `name_lookup_completed`, `ringtone_created` and `ringtone_generation_failed` for every `error_code` it returns except `UNAUTHORIZED` and the busy codes the app re-posts by itself (`GENERATION_IN_PROGRESS`, `TTS_RATE_LIMITED`). The app sends `ringtone_generation_started` and `creation_limit_reached`, and sends `ringtone_generation_failed` only for failures that carry no server `error_code` (user cancel, transport, timeout, unreadable response) plus `unauthorized`, which the server cannot attribute (`GenerationErrorCode.appReportsFailure`). The app's 155 s request timeout is past the function's worst case (TTS 55 s + mix 60 s + upload 20 s) and the 150 s Edge request limit, so the app and the server never both report one attempt. Deploy `generate-ringtone` before or with the app release. Ops and cost reporting for generation stays SQL over `generated_ringtones` / `ringtone_renders`.

Trial activation is tracked on the server: `trial_payment_succeeded` fires once per subscription row, from whichever of the `SUBSCRIPTION_AUTH_STATUS` webhook and `verify-subscription` flips the `pending` row. **Count trials with `trial_payment_succeeded`**, and unconverted trials with `trial_expired`. The app's `trial_payment_completed` stays for app-side attribution and fires in the same handler as Meta `Purchase` and Firebase `purchase`.

---

## Setup

### Android

1. Add to `local.properties`:
   ```
   mixpanel.token=YOUR_MIXPANEL_PROJECT_TOKEN
   ```
2. Gradle writes it into `BuildConfig.MIXPANEL_TOKEN` (`app/build.gradle.kts`).
3. `MixpanelAPI.getInstance(context, token, superProperties, true)`: the last argument is `trackAutomaticEvents`, so automatic events are on in **every** build (see [Automatic events](#automatic-events)). Super properties are passed to the constructor because it fires `$ae_first_open` / `$ae_updated` itself.
4. `setEnableLogging(BuildConfig.DEBUG)`: debug builds log to Logcat. Debug and release share the project token, so filter debug traffic with `build_type`.
5. Install attribution uses `com.android.installreferrer:installreferrer:2.2` (declared directly; Mixpanel 7.5.4 does not read the referrer).

### Server

Set **one** of:

- Supabase Edge Function secret `MIXPANEL_TOKEN` (preferred and already set project-wide; used by `cashfree-webhook`, `verify-subscription` and `generate-ringtone`)
- `app_config.mixpanel_token` in the database (fallback)

Resolution (`resolveMixpanelToken`, uses `||` so an empty secret still falls back):

```
Deno.env MIXPANEL_TOKEN → app_config.mixpanel_token → skip tracking if empty
```

If the token is missing, track calls log a warning and return `{ ok: false, error: "token_missing" }`. Subscription DB updates still run. Mixpanel and Meta calls never throw and time out after 4 s, so an analytics outage never turns a webhook into a 500. `generate-ringtone` sends its events after the response is written, so they never delay or fail a generation.

### Database migrations and deploy order

1. `20260728160000_add_subscription_payments.sql` (payment rows, renewal counts, dedupe) and `20260923121000_add_personalized_ringtones.sql` must already be applied.
2. Apply `20260924120000_add_subscription_cashfree_status.sql` **first, before deploying any function**:

   | Adds | Used by |
   |------|---------|
   | `subscriptions.cashfree_status`, `cashfree_status_at` | Raw Cashfree status and its event time, for `subscription_status_changed` (compare-and-set) and `previous_status`. Written by `cashfree-webhook`, `verify-subscription` (on its activation) and `create-subscription` (reset when a pending row is reused) |
   | `subscriptions.trial_expired_at` | The `trial_expired` once-guard. Starts NULL everywhere (no backfill); rows cancelled before the deploy are never reported late |
   | `app_config.cashfree_webhook_signature_mode = log_only` | Signature rollout switch (see [Webhook signature](#webhook-signature)) |
   | `idx_ringtone_renders_name_lang_ready` | `name_lookup_completed.match_count`. If `ringtone_renders` has grown, build it `CONCURRENTLY` in the SQL editor first (see the migration header) |

3. Deploy `cashfree-webhook`, `verify-subscription`, `create-subscription`, `send-otp`, `verify-otp`, `complete-signup` and `generate-ringtone` (the last one before or with the app release: the app no longer sends `ringtone_created`). No Mixpanel secret changes: `MIXPANEL_TOKEN` is already project-wide. The Meta `Subscribe` side effect of `subscription_paid` separately needs `META_DATASET_ID` and `META_CONVERSIONS_API_ACCESS_TOKEN` (fallback `app_config.meta_dataset_id` / `meta_conversions_api_access_token`). They are currently unset in the project secrets. Without them the webhook skips `Subscribe` with a "credentials missing" warning.

### Prices (`app_config`)

Amounts are server config in `app_config`, read by `create-subscription`, `verify-subscription` and `cashfree-webhook`:

| Key | Function default | Note |
|-----|------------------|------|
| `subscription_auth_amount` | `3` | Trial / mandate auth amount |
| `subscription_recurring_amount` | `299` | Seeded as `249`, raised to `299` by `20260813140000_add_cashfree_plan_id.sql` |
| `subscription_interval_months` | `1` | Seeded as `3`. Confirm prod is `1`: the paywall's "/month" copy assumes it |

`create-subscription` returns the auth and recurring amounts. They feed `subscription_initiated.auth_amount` / `recurring_amount` (and the same two properties on `subscription_failed`), `trial_payment_completed.amount` (fallback `3.0`), the Meta and Firebase purchase value, and the paywall and member-screen price labels (the defaults 3 / 299 show until that response).

### Cashfree Dashboard → Webhooks

Webhook version 2025-01-01 (the parser also reads 2026-01-01 payloads). `cashfree-webhook` runs with `verify_jwt = false` (`supabase/config.toml`); Cashfree authenticates by signature. Set a longer retry policy, because Cashfree cannot replay subscription webhooks.

Enable:

| Cashfree type | Mixpanel event |
|---------------|----------------|
| `SUBSCRIPTION_AUTH_STATUS` | `trial_payment_succeeded` (SUCCESS), `mandate_auth_failed` (FAILED / CANCELLED) |
| `SUBSCRIPTION_PAYMENT_SUCCESS` | `subscription_paid` |
| `SUBSCRIPTION_PAYMENT_FAILED` | `subscription_renewal_failed` |
| `SUBSCRIPTION_PAYMENT_CANCELLED` | `subscription_renewal_failed` |
| `SUBSCRIPTION_PAYMENT_NOTIFICATION_INITIATED` | `subscription_renewal_notified` |
| `SUBSCRIPTION_STATUS_CHANGED` | `subscription_cancelled` or `subscription_status_changed`, plus `trial_expired` for an unconverted trial |
| `SUBSCRIPTION_REFUND_STATUS` | `subscription_refund_processed` |

Leave `CARD_EXPIRY_REMINDER` and `CONTROLLED_*` off: the app only creates UPI mandates, and those types return 200 `ignored`.

### Webhook signature

`cashfree-webhook/signature.ts` verifies Base64(HMAC-SHA256(`x-webhook-timestamp` + raw body)) with `app_config.cashfree_client_secret`, using the constant-time `crypto.subtle.verify`.

| Verdict | `log_only` | `enforce` |
|---------|-----------|-----------|
| `valid` | accepted | accepted |
| `invalid`, `malformed` | 401 | 401 |
| `missing_header`, `missing_secret` | accepted, error logged | 401 |

The mode comes from `app_config.cashfree_webhook_signature_mode`; anything other than `log_only`, including a missing row, means `enforce`. If `app_config` cannot be loaded the webhook returns 500 (fails closed). Timestamp skew is logged, never rejected. After 24–72 h of logs with verdict `valid`, switch without a redeploy:

```sql
update app_config set value = 'enforce' where key = 'cashfree_webhook_signature_mode';
```

---

## Super properties

Attached automatically to every **app** event. Passed to the SDK constructor (it fires `$ae_first_open` / `$ae_updated` itself), then re-registered on identify, on logout (after `reset()`) and on language change.

| Property | Type | App | Server |
|----------|------|-----|---------|
| `platform` | string | `"android"` | `"server"` (on each track payload) |
| `app_version` | string | `BuildConfig.VERSION_NAME` | `generate-ringtone` events only (the `app_version` the app sends in the request); not on subscription events |
| `build_type` | string | `BuildConfig.BUILD_TYPE` (`debug` / `release`) | not sent |
| `is_logged_in` | boolean | `AuthStore.isLoggedIn()`; `true` after identify, `false` after logout | not sent |
| `user_state` | string | `locked` / `trial` / `active` / `cancelled` / `expired` from the `AuthStore` status (`UserState.fromStatus`; `none`, blank, unknown and logged out are `locked`). Set at init; updated on identify (signup, login, trial verify), to `trial` at `trial_payment_completed`, and to `locked` at logout | not sent |
| `app_language` | string | Storage value (`Hindi`, `English`, …), only once the user has picked a language; unregistered otherwise | not sent |

**`user_state` staleness:** it is only as fresh as the last user object the app stored (signup, login, trial verify). Server-side changes (autopay renewal, cancel, expiry) are not seen until the next of those, so a user who converted or cancelled can keep sending `trial`. Use it to segment app behaviour by what the app believed at the time; for the current state use the `subscription_status` profile property, which the webhook keeps up to date. For the same reason `subscription_status` is deliberately **not** a super property.

---

## Automatic events

| Event | Status |
|-------|--------|
| `$ae_first_open` | On (all builds) |
| `$ae_updated` | On (all builds) |
| `$ae_crashed` | On (all builds). `$ae_crashed_reason` is the exception's `toString()`, sent as-is |
| `$ae_session` | **Off** |

`$ae_session` is switched off with manifest meta-data `com.mixpanel.android.MPConfig.MinimumSessionDuration = 2147483647`. `IncomingCallThemeActivity` starts on every ringing call, so SDK sessions would otherwise create uncapped background sessions (plus a `$ae_total_app_sessions` people increment). Use Mixpanel's computed sessions or `app_opened` instead.

---

## Identity

`distinct_id` is always `user.id.toString()` (Postgres `users.id`).

| Action | Location | Mixpanel calls |
|--------|----------|----------------|
| Sign up | `SignUpNameActivity` | `identifyUser(user)` (`identify` → `people.set` / `set_once`) → `track("sign_up_completed")` |
| Login | `OtpVerificationActivity.completeLogin()` | `identifyUser(user)` → `track("login_completed")` |
| Trial payment | `SubscriptionActivity.verifySubscription()`: from `onSubscriptionVerify()`, its re-run after a recreation, or the Pending state's "Payment Status Dekhein" re-check | `identifyUser(user)` → `track("trial_payment_completed")` → `people.set(subscription_status = "trial")` |
| Trial payment succeeded | `cashfree-webhook` `SUBSCRIPTION_AUTH_STATUS`, or `verify-subscription` | HTTP `/track` + `/engage` with `distinct_id = user.id` |
| Charges, notices, status, refunds, cancel, trial expiry | `cashfree-webhook` | HTTP `/track` (+ `/engage`) with `distinct_id = user.id` |
| Generation outcomes | `generate-ringtone` | HTTP `/track` (+ `/engage` on `ringtone_created`) with `distinct_id` = the user of the request's session token (or legacy `user_id` when allowed); nothing is sent for a request that cannot be attributed |
| App re-open | `MixpanelAnalytics.restoreIdentity()` (at init) | `identify(user.id)` if `AuthStore.isLoggedIn()`; `reset()` if logged out but the SDK is still identified (backup restore) |
| Logout | `ProfileActivity` (`source=profile`), `SubscriptionActivity` (`source=subscription`), `RingtoneProcessingActivity` "Log in again" (`source=ringtone_processing`, `reason=session_expired`) | `logout(context, source, reason)`: `track("logged_out")` (only when logged in) → `reset()` → `ProfileStore.clearSession()` → super properties re-registered with `is_logged_in = false` |

Signup order is required so the signup event is tied to the identified user, not an anonymous ID. All three logout sites also clear the Meta and Firebase user IDs.

After logout the SDK assigns a new anonymous ID. The next login `identify()` should alias that session to the returning user.

---

## User profile properties

People updates use `set`, `set_once` and `unset` only. There are **no increments** (`$add` / `people.increment`): `/engage` cannot dedupe, so a retried webhook would double count. Counts come from events, or are `$set` from DB totals.

| Property | Op | When set | Source |
|----------|----|----------|--------|
| `$name` | set | Identify (login / signup / trial payment), when non-blank | App |
| `$created` | set_once | Identify (server `created_at`); `sign_up_completed` (now) is a no-op if already set | App |
| `first_app_version` | set_once | Identify | App |
| `subscription_status` | set | Identify: `users.status`. `"trial"`: `trial_payment_completed`, `trial_payment_succeeded`. `"active"`: `subscription_paid`. `"cancelled"`: `subscription_cancelled`, only when `users.status` was downgraded | App + server |
| `phone_state_granted`, `contacts_granted`, `notifications_enabled`, `write_settings_granted`, `call_control_granted` | set | `app_opened`, identified users only | App |
| `initial_utm_source`, `initial_utm_medium`, `initial_utm_campaign` | set_once | `install_attributed` (blank values omitted) | App |
| `app_language` | set | `language_selected` | App |
| `last_ringtone_category` | set | `ringtone_created` (server) or `ringtone_set` (app); DB category name | App + server |
| `meratune_ringtone_active` | set | `true` at `ringtone_set`, `false` at `ringtone_replaced_externally` | App |
| `has_call_theme` | set | `ringtone_set` | App |
| `trial_started_at`, `trial_ends_at` | set | `trial_payment_succeeded` | Server |
| `autopay_enabled` | set | `true` at `trial_payment_succeeded`, `false` at `subscription_cancelled` | Server |
| `total_renewals` | set | `subscription_paid` (`renewal_number`) | Server |
| `last_billing_month` | set | `subscription_paid` (IST `YYYY-MM`) | Server |
| `last_renewal_amount` | set | `subscription_paid` | Server |
| `last_payment_at` | set | `subscription_paid` (Cashfree `event_time`) | Server |
| `lifetime_revenue` | set | `subscription_paid`: sum of the user's `subscription_payments` (auth + recurring) plus this charge | Server |
| `next_billing_date` | set | `subscription_paid`, `subscription_renewal_notified`, `subscription_status_changed` (IST `YYYY-MM-DD`) | Server |
| `last_payment_failed_reason`, `last_payment_failed_at` | set / unset | Set at `subscription_renewal_failed`; the reason is unset at `subscription_paid` | Server |
| `last_auth_failed_reason` | set | `mandate_auth_failed` | Server |
| `cashfree_subscription_status` | set | `subscription_status_changed`, `subscription_cancelled` (lower-cased Cashfree status) | Server |

Server profile updates send `$ignore_time: true` (a 3 a.m. webhook must not bump `$last_seen`) and `ip=0` (the edge datacenter must not overwrite the user's city).

Typical `subscription_status` values: `none`, `trial`, `active`, `cancelled`, `expired`.

---

## Conversion funnel

This is the business funnel Mixpanel is built to measure, as implemented from the name-ringtone spec:

```
otp_sent
  → sign_up_completed                                  (returning users: login_completed)
      → subscription_screen_viewed                     ← entry_point
          → subscription_initiated
              → trial_payment_completed (app)          ← attribution; Meta Purchase / Firebase purchase
                trial_payment_succeeded (server)       ← same moment; count trials here
                  → create_ringtone_cta_tapped         ← member screen: source = membership_welcome
                    → ringtone_creation_started        ← name + language form; entry_point = post_purchase
                      → sample_list_viewed
                          → sample_previewed           ← row tap (optional)
                              → sample_selected        ← first "chuno"
                                  → ringtone_set_started → set_mode_selected   ← chuno sheets (source = creation_flow)
                                      → ringtone_generation_started   (app)
                                          → name_lookup_completed     (server)
                                              → ringtone_created | ringtone_generation_failed   (server; app-side failures from the app)
                                                  → ringtone_set            ← "Ringtone set karein" on the Ready screen
                                                      → subscription_paid  ← recurring autopay (webhook)
```

After the trial verifies, the paywall opens the member screen ("Aap ab member hain!", `screen_viewed` `membership_welcome`) instead of Home. Its CTA and its 3 checklist rows all send `create_ringtone_cta_tapped` and open the create form on top of a fresh Home, so the form's `previous_screen` is `membership_welcome`. Back from the member screen goes to Home.

Server steps carry `platform = "server"` and none of the app super properties, so do not filter this funnel on `platform`, `build_type` or `user_state`. Both sides share `distinct_id = users.id`.

Finer steps between those: `app_opened` / `install_attributed` before `otp_sent`; `subscription_cta_tapped` before `subscription_initiated`; on Home Set, `ringtone_set_started → set_mode_selected` right before `ringtone_set`; `subscription_renewal_notified` before `subscription_paid`.

Exits and failures:

- `otp_sent` → `otp_verification_failed` (`failure_reason`, `otp_entry_method`, `attempt`); other auth stages → `auth_failed` (`stage`, `failure_reason`)
- `subscription_screen_viewed` → `paywall_dismissed` (`entry_point`, `dismiss_method`, `attempt`, `video_completed`)
- `subscription_cta_tapped` / `subscription_initiated` → `subscription_failed` (`stage` = `precheck` | `create` | `checkout` | `verify`; one more `verify` per manual Pending re-check) and `mandate_auth_failed` (server)
- `trial_payment_succeeded` → `trial_expired` (`reason`, `ringtones_created`) or `subscription_cancelled` (`cancelled_during_trial`)
- `ringtone_generation_started` → `ringtone_generation_failed` (`failure_reason` = lower-cased `error_code` / `GenerationErrorCode`, or `user_cancelled`) → `generation_error_action_taken`; `quota_exceeded` also sends `creation_limit_reached`
- `ringtone_set_started` → `ringtone_set_failed` (`stage`, `failure_reason`). On the create path a flow can also stay open (see [Create-path split](#create-path-split))
- `subscription_renewal_notified` → `subscription_renewal_failed` (`failure_reason`, `retry_attempts`); a later `subscription_paid` with `is_retry_recovery = true` means the retry succeeded
- `subscription_paid` → `subscription_cancelled` (churn)

### Trial vs recurring (do not mix these)

| Event | Who sends it | Typical amount | Meaning |
|-------|----------------|----------------|---------|
| `trial_payment_succeeded` | Server (webhook or verify), once per subscription row | Auth amount from Cashfree, else `app_config.subscription_auth_amount` (default `3`) | Mandate authorised, trial started. **Trial counts** |
| `trial_expired` | Webhook only, once per subscription row | — | Trial ended with no recurring charge. **Unconverted trials** |
| `trial_payment_completed` | Android app after verify (checkout verify or a manual Pending re-check) | Auth amount from `create-subscription` (fallback `3.0` INR) | Same moment, app-side; kept for attribution parity with Meta `Purchase` / Firebase `purchase` |
| `subscription_paid` | Webhook only | The recurring charge (`app_config.subscription_recurring_amount`, default `299`) | Autopay charge succeeded |

`subscription_paid` has **no amount threshold**. The webhook **skips** it when:

- `payment_status` is not `SUCCESS`
- `payment_type` is not `CHARGE` (the auth payment also arrives on `PAYMENT_SUCCESS`, as `AUTH`)
- the amount is not above 0
- `cf_payment_id` is missing
- the payment already exists in `subscription_payments`
- the subscription row is not found

`amount_mismatch = true` flags a charge that differs from `app_config.subscription_recurring_amount` (omitted when that is unset).

### Suggested Mixpanel Insights funnels

1. **OTP → account:** `otp_sent` → `sign_up_completed` or `login_completed` (break down `otp_verification_failed` by `failure_reason`, `otp_entry_method`; `auth_failed` by `stage`, `failure_reason`)
2. **Trial conversion:** `subscription_screen_viewed` → `subscription_cta_tapped` → `subscription_initiated` → `trial_payment_succeeded` (break down by `entry_point`, `payment_app`); drop-off: `paywall_dismissed` (by `dismiss_method`)
3. **Checkout drop-off:** `subscription_initiated` → `subscription_failed` (break down by `stage`, `failure_reason`, `payment_app`) and `mandate_auth_failed` (`failure_reason`, `upi_handle`). Count users, not events: each manual Pending re-check adds a `verify` failure
4. **Activation:** `trial_payment_succeeded` → `create_ringtone_cta_tapped` (`source = membership_welcome`) → `ringtone_created` → `ringtone_set`
5. **Create flow:** `ringtone_creation_started` → `sample_list_viewed` → `sample_selected` → `set_mode_selected` → `ringtone_generation_started` → `ringtone_created` → `ringtone_set` (break down by `entry_point`, `language`, `fallback_level`, `cached`, `set_mode`)
6. **Generation health:** `ringtone_generation_started` → `ringtone_generation_failed` (break down by `failure_reason`, `retryable`, `http_status`, `platform`; watch `latency_ms` / `duration_minutes` on `ringtone_created`)
7. **Name coverage:** `name_lookup_completed` by `has_match`, `exact_match`, `language`
8. **Renewal health:** `subscription_renewal_notified` → `subscription_paid` vs `subscription_renewal_failed` (break down by `failure_reason`, `upi_handle`)
9. **Paid retention:** `subscription_paid` where `renewal_number = 1` → `renewal_number ≥ 2`
10. **Churn:** `trial_payment_succeeded` or `subscription_paid` → `subscription_cancelled` (break down by `cancelled_during_trial`, `cancelled_by`); unconverted trials: `trial_expired` by `reason`, `ringtones_created`

---

## Event catalog — Android (46)

Every event also receives the [super properties](#super-properties) and a UUID `$insert_id`. Tables list **event-specific** properties; blank or unknown values are omitted.

### Session and shell

Tracked by `AnalyticsLifecycleCallbacks` unless noted. `IncomingCallThemeActivity` is ignored entirely, so ringing calls never count as a foreground.

| Event | Trigger | Properties |
|-------|---------|------------|
| `app_opened` | Coming to the foreground: first foreground in the process (`cold`), or back after ≥ 5 min in the background (`warm`). Decided inside the entry screen's `super.onCreate()`, so that screen's own `onCreate` events (for example `subscription_screen_viewed`) come after it. Shorter trips (UPI app, Settings, contact picker, camera) and config changes are not opens. When the process died during such a trip, the restored screen compares against the background time saved in `analytics_state`: under 5 min is no open, otherwise `warm` | `start_type` (`cold` / `warm`), `entry_screen` (screen slug) |
| `screen_viewed` | `onActivityCreated` with no saved state for the 13 slugged screens, so rotation doesn't re-fire. Also called from `Home.onNewIntent` (CLEAR_TOP re-entry from the Ready screen's "Home par jayen") and `CreateRingtoneActivity.onNewIntent` (CLEAR_TOP re-entry from the processing screen's "Change language"). Home skips the `onNewIntent` track when it was just created and hasn't resumed yet (the never-launched Home at the bottom of the post-purchase `startActivities` stack gets `onCreate` then `onNewIntent` on "Home par jayen"), so one visit is tracked once (`HomeScreenViewGate`) | `screen_name`, `previous_screen` |
| `install_attributed` | First foreground after a **fresh** install (not from `Application.onCreate`: the phone-state receiver can cold-start the process). Skipped, and marked done, when `lastUpdateTime > firstInstallTime`, so users upgrading to this build never send it. Once per install (prefs flag); up to 3 tries per process on `SERVICE_UNAVAILABLE` / disconnect (`InstallReferrerTracker`) | `utm_source`, `utm_medium`, `utm_campaign` (max 255 chars), `has_gclid` |
| `permission_prompt_answered` | Startup prompts on a fresh (not recreated) Home (`StartupPermissionRequester`; first answer per permission, then only when it changes; permissions already granted are neither asked nor reported). New members go from the member screen straight into the create flow, so their startup answers come on their first Home visit (back from the member screen or the create flow, or "Home par jayen"), after any Set-flow prompts. Set-flow prompts (`RingtoneSetController`): during Home Set; on the create path on the Ready screen, where the storage prompt (Android 8/9) and WRITE_SETTINGS come first, before the ringtone is downloaded, then the per-mode contacts / phone prompts. The Set flow skips a permission the system denied without a prompt (no rationale before or after the request: already permanently denied, or a first prompt dismissed on Android 11+) | `permission`, `granted`, `permanently_denied` (denied runtime permissions only), `prompt_context` (`startup` / `set_ringtone`) |
| `external_link_opened` | Terms / privacy span in the footer of phone entry, OTP entry and name entry (`AuthTermsHelper`), Profile help / privacy / delete-account rows. Only after `startActivity` succeeded | `link` (`terms` / `privacy_policy` / `help_support` / `delete_account`), `source` (`phone_entry` / `otp_entry` / `name_entry` / `profile`) |
| `logged_out` | Inside `MixpanelAnalytics.logout()`, before `reset()`, only when logged in | `source` (`profile` / `subscription` / `ringtone_processing`), `reason` (`user_initiated` / `session_expired`) |

Screen slugs (13): `language_selection`, `phone_entry`, `otp_entry`, `name_entry`, `subscription`, `membership_welcome` (the member screen after the trial verifies), `home`, `profile`, `create_form`, `name_ringtones` (the create flow's existing name ringtones step, only when the name already has ringtones), `song_picker`, `ringtone_processing`, `ringtone_ready`. The router and third-party activities are untracked. The paywall's Pending and Failed states and the bottom sheets are not screens.

`previous_screen` is the slug of the last tracked screen that resumed. It resets at `app_opened`, and when a fresh activity starts in an emptied task (backed out and relaunched within 5 min), so a session's entry screen has none.

`analytics_state.xml` (referrer and permission dedupe, call-event cap, background time) and `active_ringtone.xml` are excluded from backup and device transfer, so a restored or new device does not suppress `install_attributed` or report a false `ringtone_replaced_externally`.

`permission` values: `read_phone_state`, `answer_phone_calls`, `read_contacts`, `write_contacts`, `post_notifications`, `write_external_storage`, `write_settings`.

### Auth and onboarding

| Event | Trigger | Properties |
|-------|---------|------------|
| `otp_sent` | Phone OTP send succeeds (`PhoneAuthActivity`), or resend succeeds (`OtpVerificationActivity`) | `is_resend`, `resend_count` (resends only, including this one) |
| `auth_failed` | Invalid phone format, OTP send / resend failure, name shorter than 2, complete-signup failure. Coroutine cancellation is not tracked | `stage`, `failure_reason`, `is_resend` (`send_otp` only) |
| `otp_verification_failed` | OTP verify fails, or succeeds without a session (`bad_response`) (`OtpVerificationActivity`). Coroutine cancellation is not tracked | `failure_reason`, `otp_entry_method`, `attempt` (verify attempts on this screen, including this one) |
| `sign_up_completed` | New user submits name after OTP (`SignUpNameActivity`) | `sign_up_method` (`"phone"`), `post_auth_destination` (`home` / `subscription`), `otp_entry_method` |
| `login_completed` | Returning user OTP verify succeeds (`OtpVerificationActivity.completeLogin()`) | `sign_in_method` (`"phone"`), `otp_entry_method`, `attempt` (verify attempts on this screen), `resend_count`, `post_auth_destination` |
| `language_selected` | Continue on the language screen (`LanguageSelectionActivity`); double taps ignored | `language`, `locale`, `context` (`onboarding` from the launch router (`AuthActivity`) before any language is saved; `settings` from the Profile language row, now the only other entry since the auth screens and paywall lost their language button), `previous_language`, `language_changed` (both only after an earlier choice) |

`auth_failed.stage`: `phone_validation`, `send_otp`, `name_validation`, `complete_signup`. OTP verification has its own event, `otp_verification_failed`.

`failure_reason` on both events is the server `error_code` or a client value (`AuthFailureReason`), never the error text (Fast2SMS errors can echo the number):

| Source | Values |
|--------|--------|
| `send-otp` | `phone_missing`, `sms_not_configured`, `sms_provider_error`, `otp_session_create_failed` |
| `verify-otp` | `missing_params`, `invalid_phone`, `otp_not_found`, `otp_expired`, `otp_invalid`, `verify_update_failed`, `internal_error` |
| `complete-signup` | `name_invalid_length`, `session_invalid`, `session_expired`, `profile_update_failed`, `account_create_failed` |
| Client | `invalid_phone_format`, `name_too_short`, `network`, `timeout`, `bad_response`, `unknown` |

`otp_entry_method`: `manual`, `sms_retriever`, `sms_consent`.

### Subscription (app)

`payment_app` slugs (`PaymentAppSlug`): `phonepe`, `google_pay`, `paytm`, `bhim`, `upi_id`. `upi_id` is the "UPI ID" option: Cashfree's hosted subscription checkout (web page inside the Cashfree SDK), where the user enters a UPI ID or picks any UPI app the page finds. The paywall pill and the payment-app sheet list only the installed UPI apps; the default is the first installed app, and when none is installed the pill shows "UPI ID". The sheet always ends with the UPI ID row ("Doosre UPI app / UPI ID", or "UPI ID se pay karein" when it is the only row).

| Event | Trigger | Properties |
|-------|---------|------------|
| `subscription_screen_viewed` | Paywall opens (`SubscriptionActivity`, no saved state, together with Meta `ViewContent`) | `previous_screen`, `user_status` (`AuthStore` status), `installed_app_count`, `entry_point` |
| `paywall_dismissed` | The paywall finishes without converting, through system back or the Home button on the video card: `onPause` with `isFinishing`, once. Both go to Home (`leaveToHome`: remembers the browse choice, then `PaywallUiPolicy.homeButtonRoute` finishes onto the Home below, clears the task down to it, or starts it when the paywall is the task root), so back never closes the app from the paywall; back on Home then closes it. Back on the Pending / Failed states only returns to the paywall state and is not a dismissal. Suppressed for the programmatic finishes (verify success, logout, not logged in); back and the Home button are ignored while a verify runs | `entry_point`, `dismiss_method` (`system_back` / `home_button`), `attempt` (0 before any CTA tap), `video_completed` |
| `subscription_cta_tapped` | Paywall CTA ("Tune banayein", `tryNowButton`) tapped (ignored while a payment is processing) | `payment_app`, `payment_app_installed` (always `false` for `upi_id`, which is not an app), `attempt`, `video_completed` |
| `payment_app_selected` | Row picked in the payment-app sheet (the installed apps, then the UPI ID row) | `payment_app`, `previous_payment_app` |
| `subscription_initiated` (named `subscription_started` until 2026-09-28) | Create-subscription API succeeds, **before** Cashfree checkout opens: the UPI app intent, or for `upi_id` the hosted web checkout (same session, same verify / failure callbacks) | `payment_app`, `auth_amount` (e.g. `3`), `recurring_amount` (e.g. `299`; both from `app_config` via create-subscription, see [Prices](#prices-app_config); always sent, the paywall defaults 3 / 299 if the response has none), `attempt`, `user_state` |
| `trial_payment_completed` | `verifySubscription` returns `active == true` with a user. Once per paywall (`verifyInFlight`): from the Cashfree verify callback, its re-run when the paywall was recreated during the UPI switch (id from saved state or the Cashfree response) or while the verify request ran (`verify_pending` in saved state re-runs it; Cashfree delivers the callback only once), or a manual "Payment Status Dekhein" re-check on the Pending state. The member screen opens next (`finishWithoutDismiss`); a returned user who still needs a subscription is routed as before (`AuthNavigator`) | `payment_app`, `subscription_id`, `amount` (auth amount, default `3.0`), `currency` (`"INR"`), `attempt`, `previous_status` (`AuthStore` status when the paywall opened) |
| `subscription_failed` | Precheck, create, checkout or verify failure. See [Paywall states](#paywall-states) for what the user sees after each | `stage`, `failure_reason`, `payment_app`, `cf_error_code` (lower-cased Cashfree SDK code), `http_status`, `cashfree_status` (verify pending only), `attempt`, `auth_amount`, `recurring_amount` (the amounts the paywall shows: create-subscription's once it answered, else the defaults `3` / `299`; always sent), `user_state` |
| `subscription_video_ended` | Paywall video completes or errors, each at most once per paywall | `end_reason` (`completed` / `error`), `error_code` (ExoPlayer code name without `ERROR_CODE_`), `duration_ms` |

`user_state` on `subscription_initiated` and `subscription_failed` is an event property derived like the super property (`UserState.derive`: the `AuthStore` status when logged in, else `locked`), read when the event fires. It has the same staleness as the super property (see [Super properties](#super-properties)).

**Rename (2026-09-28, first release after 1.2.3 / versionCode 5):** `subscription_started` → `subscription_initiated`, same trigger and properties plus `user_state`; `subscription_failed` keeps its name and gains `auth_amount`, `recurring_amount` and `user_state`. Builds up to 1.2.3 send `subscription_started` without `user_state`, and `subscription_failed` without the amounts. Funnels that span the change need both names (or a Mixpanel Custom Event merging them); segment by `app_version`. Meta `InitiatedCheckout` is unchanged.

`subscription_failed.failure_reason` is bounded (anything outside `[a-z0-9_]{1,64}` is dropped):

| `stage` | `failure_reason` |
|---------|------------------|
| `precheck` | `payment_app_not_installed`, `no_payment_app_installed` (both only when the selected app was uninstalled while the paywall was open; the selection then moves to another installed app or UPI ID. Never with `upi_id`, and the pill no longer sends `no_payment_app_installed`), `not_logged_in` |
| `create` | By HTTP status: `missing_user_id` (400), `user_not_found` (404), `already_active` (409), `gateway_error` (502), `gateway_not_configured` (503), `server_error` (other). Transport: `network`, `timeout`, `invalid_response`, `unknown` |
| `checkout` | `user_cancelled` (Cashfree `action_cancelled`), `payment_failed`, `sdk_exception`, `other` |
| `verify` | `pending` (with `cashfree_status`), `missing_user`, `missing_subscription_id`, `not_logged_in`, `server_error`, `network`, `timeout`, `unknown` |

Paywall `entry_point` (`PaywallEntryPoint.derive`), from `previous_screen` and the `AuthStore` status when the paywall opens; kept in saved state and repeated on `paywall_dismissed`:

| `previous_screen` | Status | `entry_point` |
|-------------------|--------|---------------|
| `ringtone_processing` (its Subscribe action) | any | `limit_screen` |
| `home` | any | `locked_home` (derived, but nothing on Home opens the paywall yet) |
| none (app open) or an onboarding screen (`language_selection`, `phone_entry`, `otp_entry`, `name_entry`) | `cancelled` / `expired` | `win_back` |
| same | `none` / `trial` | `onboarding` |
| anything else (including `membership_welcome`), or an `active` user | | omitted |

#### Paywall states

The paywall has three states inside `SubscriptionActivity` (`PaywallUiPolicy`): the paywall, Pending and Failed. Pending and Failed are not screens: they send no `screen_viewed` and no event of their own. They only change what the user sees after outcomes that were already tracked:

| Outcome | Tracked | Then shows |
|---------|---------|------------|
| Checkout `action_cancelled` (UPI cancel, or "Yes" on the hosted checkout's exit dialog for `upi_id`) | `subscription_failed` `checkout` / `user_cancelled` | The paywall, quietly |
| Any other checkout failure, or a Cashfree SDK exception | `subscription_failed` `checkout` / `payment_failed`, `other` or `sdk_exception` | Failed. The "bank / funds / details" reasons show only for `payment_failed`. "Dobara Try Karein" returns to the paywall |
| Checkout verify (or its re-run after recreation) not active, no user, or a server or transport error | `subscription_failed` `verify` (`pending`, `missing_user`, `server_error`, `network`, `timeout`, `unknown`) | Pending |
| Verify with no logged-in user or no subscription id | `subscription_failed` `verify` (`not_logged_in` / `missing_subscription_id`) | The current state stays (no request is made) |
| "Payment Status Dekhein" on Pending: runs the same `verifySubscription` once (ignored while one is in flight) | Active: `trial_payment_completed` (+ Meta `Purchase`, Firebase `purchase`). Otherwise one `subscription_failed` `verify` per tap | Active: the member screen. Otherwise Pending stays, with a toast |

There is no automatic re-check. Back on Pending or Failed returns to the paywall and is not a `paywall_dismissed`; only back and the Home button on the paywall state are dismissals (both go to Home).

### Home and catalog

| Event | Trigger | Properties |
|-------|---------|------------|
| `home_viewed` | First successful tunes load with non-empty categories, **once per `HomeViewModel`** (survives rotation; `HomeViewModel.trackHomeViewedOnce`) | `tune_count`, `category_count`, `load_ms` (Home creation → first content), `has_active_ringtone` |
| `home_load_failed` | Categories or tunes fetch fails (`HomeViewModel`); cancellation is not tracked | `stage` (`categories` / `tunes`), `failure_reason`, `trigger` (`initial` / `retry` / `category_change` / `reset`) |
| `tune_played` | Playback starts on Home (not stop, not empty URL), or a new row preview on the name ringtones step. Stop vs play follows the UI state, not `isPlaying` | `tune_id`, `category`, `source` (`"search_results"` while a search query is active, else `"home"`; `"name_ringtones"` on that step, with `rank` only), `rank` (1-based in the visible list), `category_filter` (category name, `my_name` for the name chip; omitted for All), `from_search`, `is_active_ringtone` |
| `tune_play_ended` | A preview session ends at completion, stop/release or error (`PreviewPlayerController`), on Home, the song picker, the name ringtones step and the Ready screen. A replay after completion (including a seek on the Ready screen) opens a new session | `source` (`home` / `search_results` (same as its `tune_played`) / `song_picker` / `name_ringtones` / `creation_flow`), `tune_id` (base tune id on the Ready screen), `end_reason` (`completed` / `stopped` / `error`), `listened_ms`, `duration_ms`, `percent_listened`, `time_to_start_ms`, `error_code` |
| `search_performed` | Search query debounced **500 ms** (`HomeViewModel`); a pending one is sent early by the create CTA and the Home reset on return | `query_length` (trimmed), `result_count` (at send time), `category_filter` (`my_name` while the name chip is selected) |
| `category_filtered` | Category chip selected, deselected (tapping the selected chip; Home only) or "All", on Home or in the song picker. Includes the Home name chip | `category_id` (the name chip: `__my_name__`), `category_name` (the name chip: `my_name`, never the user's name; both omitted for `all`), `source` (`"home"` / `"song_picker"`), `selection` (`selected` / `deselected` / `all`) |
| `create_ringtone_cta_tapped` | Empty-state create CTA on Home ("Make {name} tune", after a search or under the name chip), or the member screen's CTA or any of its 3 checklist rows (`MembershipWelcomeActivity`, which then opens the create form with `entry_point = post_purchase`); double taps ignored on both | `source` (`"search_bar"` / `"my_name_chip"` / `"membership_welcome"`), `prefill_name_length` (Home: the trimmed query, or under the name chip without a query the profile first name; member screen: the saved profile name, which the form prefills) |
| `ringtone_replaced_externally` | Home `onResume` finds the saved MeraTune ringtone is no longer the system default (including silent). Once per saved ringtone (`ActiveRingtoneStore`) | `tune_id`, `personalized`, `days_since_set` |

Raw query text is never sent.

**Name chip.** Home shows a synthetic chip right after All Tunes: "{first name} Tunes" (the `ProfileStore` name's first word; "Meri Tunes" when blank), id `__my_name__`. It lists every active tune whose title contains that first name (case-insensitive, the same match as search; search still applies on top), and when none match it shows the same empty state as a search for the name (message with the name, "Make {name} tune" CTA, which prefills the name). Tapping it again or All Tunes goes back to all tunes (no reload: both use the same loaded tunes). `home_viewed.category_count` does not count it. Analytics carry only `__my_name__` / `my_name`, never the name itself.

`home_load_failed.failure_reason` (`LoadErrorMapper.reason`): `network`, `timeout`, `server_error` (5xx), `client_error` (4xx), `decode_error`, `unknown`.

### Ringtone activation (personalized create flow)

Shared property rules for this group:

| Property | Value |
|----------|-------|
| `language` | Storage value (`Hindi`, `English`, `Telugu`, …), never the localized label |
| `voice` | `male` or `female` from `Tune.voiceKey`; omitted when the tune has no recognised gender |
| `category` | Database category name (`Devotional`, `Romantic`, …) |
| `tune_id` | `tune.id` of the **base song** (the personalized copy keeps the same id) |
| `sample_id` | The picked sample: the same id as `tune_id` in this flow (spec name, shared with the server events) |
| `rank` | 1-based position of the song in the unfiltered tier list of the picker |
| `name_length` | Code-unit length of the validated name; the name itself is never sent |
| `failure_reason` | Lower-cased `GenerationErrorCode` name (`quota_exceeded`, `timeout`, `network`, …) or `user_cancelled` |
| `client_request_id` | UUID kept in saved state; the server dedupes generations on it and puts it on its own events |
| `attempt` | 1-based `generate-ringtone` attempt (manual retries capped at 3) |
| `client_ms` | Wall time of the **current attempt**, including automatic busy re-posts |
| `total_client_ms` | Wall time since the **first attempt**; survives process death |

**Values before app version 1.3.0:** `ringtone_creation_started` and the app's old `ringtone_created` sent `voice`, `category`, `language` as localized form labels (for example "Female voice" or "भक्ति"). Segment by `app_version` when comparing across the change.

The app does not send `ringtone_created`, and sends `ringtone_generation_failed` only for app-side causes; see [generation events](#generation-generate-ringtone-3) for the server side.

| Event | Trigger | Properties |
|-------|---------|------------|
| `ringtone_creation_started` | Continue on the create form after the name passes `NameNormalizer.validate` (`CreateRingtoneActivity`); double taps ignored | `language`, `name_length`, `entry_point`, `language_source`, `prefill_source`, `name_edited`, `time_on_form_ms` |
| `unavailable_language_tapped` | Tap on a "coming soon" language (once per language per form) | `language` |
| `sample_list_viewed` | Song picker reaches a terminal load state: content, empty (`sample_count` = 0) or error (`ChooseSongActivity`). After recreation the reload is silent if it lands on the state already reported, else it fires with `trigger = restored` | `language`, `sample_count`, `category_count`, `fallback_level` (`none` / `hindi` / `any`; omitted on error), `voice_filter` (`male` / `female`; omitted for "all"), `load_state` (`content` / `empty` / `error`), `trigger` (`initial` / `retry` / `hindi_fallback` / `restored`), `failure_reason` (error only, `LoadErrorMapper` values), `requested_language` |
| `sample_previewed` | A row (or its art) tap in the picker starts preview playback, including a replay after the preview finished. A row tap only previews; it no longer selects | `sample_id`, `category`, `language`, `voice`, `rank` |
| `sample_selected` | First "chuno" tap on a song in the picker (`trackedSelections`, kept in saved state: a repeat chuno on the same song does not fire again). Ignored while a set flow is open (a sheet showing or a photo being staged) or the picker is navigating. It fires before the chuno `ringtone_set_started` and stops any preview; a song can be chosen without a `sample_previewed` | `sample_id`, `category`, `language`, `voice`, `rank`, `voice_filter` (omitted for all), `category_filter` (category **name**; omitted for all) |
| `voice_filtered` | Voice chip changed in the picker | `voice_filter` (`all` / `male` / `female`), `result_count` |
| `ringtone_generation_started` | A `generate-ringtone` request is posted (`RingtoneGenerationViewModel`); once per attempt, so manual retries fire again with `is_retry = true`. Automatic 409/503 back-off re-posts do **not** fire again. Processing opens once the chuno sheets complete (`isNavigating` guards double launches) | `tune_id`, `sample_id`, `category`, `language`, `voice`, `name_length`, `is_retry`, `attempt`, `trigger` (`initial` / `retry` / `restored`), `client_request_id`, `previewed_count` (distinct songs previewed in the picker) |
| `ringtone_generation_failed` (app) | An attempt ends without a ringtone **and without a server `error_code`** (`GenerationErrorCode.appReportsFailure`): the user backs out (`user_cancelled`), transport failure (`network`, `timeout` incl. HTTP 408 / 504), unreadable response (`invalid_response`, `unknown`), the 90 s busy budget used up (`timeout`). Also `unauthorized` (not logged in, or a 401 / 403 the server could not attribute) | `tune_id`, `sample_id`, `category`, `language`, `voice`, `failure_reason`, `http_status` (omitted without a response), `retryable`, `can_retry` (the error screen offers Retry), `client_ms`, `total_client_ms`, `attempt`, `quota_used_today`, `quota_daily_limit` (when the last response had them), `client_request_id` |
| `creation_limit_reached` | The processing screen gets `QUOTA_EXCEEDED` (shown as the limit screen); once per failed attempt. The server also sends `ringtone_generation_failed` (`quota_exceeded`) | `limit_type` (`daily`), `quota_used_today`, `quota_daily_limit` |
| `generation_error_action_taken` | Button on the processing error screen; first tap per error | `action` (`retry` / `login_again` / `subscribe` / `change_language` / `choose_another`), `failure_reason`, `attempt`, `tune_id`, `language` |
| `ringtone_ready_action_tapped` | Ready-screen "Home par jayen" button or the header back arrow (system back is not tracked); first tap only (both leave the screen). "Ringtone set karein" is not an action here (it runs the set flow); after success it turns into "Ringtone set ho gayi", whose tap goes Home and sends `go_home` with `is_set = true`, like "Home par jayen" | `action` (`go_home` / `back_button`), `tune_id`, `generation_id`, `is_set` ("Home par jayen" is always shown, so `go_home` can have `is_set = false`) |

`fallback_level` = `hindi` when the requested language has no songs but Hindi does: the picker shows the empty state (`load_state = empty`, `sample_count` = 0) with a Hindi offer, and the content load after the user accepts it (`trigger = hindi_fallback`) also sends `hindi`. `any` when neither has songs (empty state).

`entry_point` (`CreationEntryPoint`, kept in saved state): `search_bar` (Home empty-search CTA), `my_name_chip` (the same CTA under the Home name chip without a search query), `post_purchase` (the member screen's CTA and checklist rows), `processing` ("Change language" on the processing error screen); omitted for other entries. `ready_screen` ("Make another") is no longer sent from 1.3.0: the Ready screen's "Gaana Badlein" and "Naya Naam Try Karein" buttons were removed, so `ringtone_ready_action_tapped` no longer sends `change_song` / `make_another` either.

`language_source`: `user_picked`, `profile_default`, `hindi_default`, `first_enabled`. `prefill_source`: `search_query`, `profile_name` (the profile default, or the first name the `my_name_chip` CTA passes), `retained` (CLEAR_TOP re-entry kept the name), `none`.

### Set flow

`source` values: `"home"` (Home Set), `"name_ringtones"` (Set on the create flow's existing name ringtones step), `"creation_flow"` (the create path: song picker chuno and the Ready screen).

**Name ringtones step.** Continue on the create form (after `ringtone_creation_started`) looks up ringtones that already sing the name; when there are some, `NameRingtonesActivity` lists them (`screen_viewed` `name_ringtones`), otherwise the song picker opens as before. Its Set is Home's single-shot `start` with `source = name_ringtones`, `rank` / `was_previewed`, and `personalized = true` for every row (the titles sing the user's name, so `tune_name` falls back to the authored title). A row the user has not made yet is first claimed with a `generate-ringtone` cache hit (the server's `ringtone_created` with `cached = true`; the app sends no `ringtone_generation_started` for it), so `ringtone_set_started` / `ringtone_set` carry the user's `generation_id`; if the claim fails the row is set without one. "Make Your Tune" opens the song picker (`previous_screen = name_ringtones`); it sends no event of its own.

`RingtoneSetController` has three entry points: `start` (Home Set, single shot; also the Ready screen if it has no saved choice), `choose` (song picker chuno) and `apply` (Ready "Ringtone set karein" with the chuno choice).

| Event | Trigger | Properties |
|-------|---------|------------|
| `ringtone_set_started` | `start`, `choose`, or an `apply` retry after a failed apply; ignored while a flow is in flight (double tap, second row, a sheet still open) | `source`, `tune_id`, `category`, `personalized`, `generation_id`, `rank`, `was_previewed` (`rank` / `was_previewed` from Home and chuno; `generation_id` omitted at chuno) |
| `set_mode_selected` | Continue on `SetRingtoneBottomSheet` (Home Set or chuno). On Android 8/9 Home Set now shows the sheet after the storage grant (it used to set audio-only without one), so this fires there too | `set_mode`, `source`, `tune_id`, `personalized` |
| `ringtone_set_failed` | Every terminal non-success exit, once per flow; also a launcher result that arrives after the flow was lost (`state_lost`, `tune_id` omitted). While a system screen (permission dialog, Settings, camera, gallery, contact picker) is on top the flow is kept in saved state, so it survives process death | `stage`, `failure_reason`, `set_mode` (omitted for `mode_sheet`), `error_type` (exception class simple name), `source`, `tune_id`, `personalized` |
| `ringtone_set` | Ringtone successfully set as default (`RingtoneSetController.finishSuccess`): Home Set, Set on the name ringtones step, or "Ringtone set karein" on the Ready screen | `source`, `category`, `tune_id`, `tune_name`, `set_mode`, `generation_id` (omitted for catalog tunes), `personalized`, `photo_source` (`camera` / `gallery`; image modes), `contact_photo_saved`, `contact_ringtone_saved` (contact mode), `flow_duration_ms` |

`personalized` is `true` for every create-path event (chuno included, before anything is generated); on Home it is whether the tune has a `generation_id`.

**Permanently denied permission** (the system no longer shows its prompt, so no `permission_prompt_answered` is sent for it): a dialog replaces the toast. "Settings kholein" or dismiss sends the same `ringtone_set_failed` (`storage_permission` / `contacts_permission` / `phone_permission`, `permission_denied`); for phone / contacts "Sirf ringtone set karein" continues the same flow as audio-only, with no `ringtone_set_failed`, ending in `ringtone_set` (`set_mode = audio_only`) or the audio path's failure events. The dialog is not kept across process death (like a sheet, the flow then ends with no event).

#### Create-path split

On Home one flow runs from `ringtone_set_started` to `ringtone_set` / `ringtone_set_failed`. On the create path it is split across two screens:

| Where | Events |
|-------|--------|
| Song picker, chuno (`choose`) | `sample_selected` (first chuno per song) → `ringtone_set_started` (`rank`, `was_previewed`, no `generation_id`) → `set_mode_selected` → photo sheet for image modes. Cancels: `ringtone_set_failed` `mode_sheet` / `photo_sheet` (`user_cancelled`), or `photo_sheet` when the photo cannot be staged. No permission prompt and no download here. The choice (mode, staged photo, photo source, time on the sheets) travels to Processing and Ready as intent extras, with no event |
| Ready, "Ringtone set karein" (`apply`) | The saved choice is applied without a sheet: storage prompt (Android 8/9), then WRITE_SETTINGS **before** the ringtone is downloaded, then per mode: the phone / call-display prompts (everyone), or the contacts prompt, the contact picker and the phone / call-display prompts (contact); then `ringtone_set` or `ringtone_set_failed` (`storage_permission`, `write_settings_permission`, `contacts_permission`, `phone_permission`, `contact_picker`, `download`, `save`, `set_default`, `theme_save`). If the staged photo is gone the photo sheet opens again (`photo_sheet` on cancel). The staged photo moves into the call-theme store only after the set succeeds |

- The first apply continues the chuno flow: no second `ringtone_set_started` and no second `set_mode_selected`. A retry after a failed apply starts a new flow: `ringtone_set_started` with `generation_id` and without `rank` / `was_previewed`, and no `set_mode_selected` (the choice is reused).
- `flow_duration_ms` on the create path = time on the chuno sheets + time from the "Ringtone set karein" tap to success. It **excludes** generation and the time on Ready before the tap. A retry flow counts only from its own tap. On Home it is the whole single-shot flow.
- **Open flows:** the chuno flow closes with no event when the choice completes. If the user never taps "Ringtone set karein" (generation fails, they back out, or leave with "Home par jayen"), that `ringtone_set_started` has neither `ringtone_set` nor `ringtone_set_failed`. Coming back to the picker and choosing again starts another flow. So on `source = creation_flow`, `ringtone_set_started → ringtone_set` drop-off includes generation drop-off; use `ringtone_created → ringtone_set` for set conversion after a ringtone exists.

`tune_name` for a personalized tune is the base song as authored (`title_template` with `sample_name` substituted) so the user's name never leaves the device; it is omitted when the tune has no `title_template`.

`set_mode` values (`RingtoneSetMode.analyticsValue`):

| Value | Meaning |
|-------|---------|
| `audio_only` | Audio ringtone only |
| `with_image_everyone` | Call theme image for everyone |
| `with_image_contact` | Call theme image for one contact |

`ringtone_set_failed.stage`: `mode_sheet`, `storage_permission`, `write_settings_permission`, `contacts_permission`, `phone_permission`, `contact_picker`, `photo_sheet`, `download`, `save`, `set_default`, `theme_save`.

`ringtone_set_failed.failure_reason`: `user_cancelled`, `permission_denied`, `no_valid_phone_number`, `network`, `timeout`, `media_store_error`, `security_exception`, `state_lost`, `unknown`.

### Incoming call (background, capped)

These three go through one path: logged-in check → `AnalyticsDailyCap.tryAcquire()` → track → `flush()`. They share **one cap of 10 events per user per device-local day** (stored in `analytics_state` prefs, committed synchronously because a receiver-started process can die right after), and make no people updates.

| Event | Trigger | Properties |
|-------|---------|------------|
| `call_theme_displayed` | `IncomingCallEvents.showIncoming`, first show per ringing call, only when a saved call theme applies | `theme_scope` (`everyone` / `contact`), `display_mode` (`overlay_requested` / `heads_up_notification`), `has_image`, `screen_locked`, `number_available` |
| `incoming_call_action_tapped` | Answer / decline on the overlay or the notification action | `action` (`answer` / `decline`), `surface` (`overlay` / `notification`), `succeeded` |
| `incoming_call_overlay_displayed` | `IncomingCallThemeActivity` starts (not on recreation or a repeat auto start) | `launch_trigger` (`auto` / `notification_tap`) |

---

## Event catalog — server (12)

Sent through `_shared/mixpanel.ts`. `platform` is always `"server"`, same `distinct_id` as the app (`users.id`). `cleanProps` drops blanks, reserved keys and PII-looking keys. Never sent: phone, email, the user's name in any form (typed, normalized or spoken) or the generated title, the full UPI VPA (only `upi_handle`, the PSP part after `@`), refund notes or bank free text. No app super properties are attached.

**Failure handling:** Mixpanel and `/engage` calls never throw and time out after 4 s, so an analytics failure is logged and never fails a webhook or a generation.

### Generation (`generate-ringtone`, 3)

Sent from `generate-ringtone/index.ts` **after the response is written** (`runInBackground` → `EdgeRuntime.waitUntil`). `distinct_id` is the request's user; a request that fails before auth (for example `INVALID_NAME`) is attributed the way auth would do it (session token, or the legacy `user_id` when `generate_allow_legacy_user_id` is on), and nothing is sent when that fails. The builders in `generate-ringtone/analytics.ts` take ids, codes and timings only.

Who reports which outcome:

| Outcome of one request | Server sends | App sends |
|------------------------|--------------|-----------|
| Ready: fresh render, render-cache hit, or the sample's own name | `name_lookup_completed` + `ringtone_created` (+ people `last_ringtone_category`) | — |
| Error response with any other `error_code` | `name_lookup_completed` when the lookup ran + `ringtone_generation_failed` | `creation_limit_reached` for `QUOTA_EXCEEDED` |
| `GENERATION_IN_PROGRESS` (409), `TTS_RATE_LIMITED` (503) | nothing: the app re-posts, and the re-post reports | `ringtone_generation_failed` (`timeout`) only when its 90 s busy budget runs out |
| `UNAUTHORIZED` | nothing | `ringtone_generation_failed` (`unauthorized`) |
| No response, unreadable response, user cancel | — | `ringtone_generation_failed` |
| Idempotent replay of a ready `client_request_id` | nothing (already reported) | — |

| Event | Trigger | Properties | `time` |
|-------|---------|------------|--------|
| `name_lookup_completed` | Once per new generation request, after name validation, the tune load and the exact render-cache check, before rendering | `has_match`, `match_count`, `exact_match`, `language`, `name_length`, `sample_id` | When the lookup completed |
| `ringtone_created` | The generation row becomes ready (fresh or cached); once per row | `tune_id`, `sample_id`, `category`, `language`, `voice`, `cached`, `duration_ms`, `latency_ms`, `duration_minutes`, `quota_used_today`, `quota_daily_limit`, `source` (`"creation_flow"`) | `completed_at` |
| `ringtone_generation_failed` | Terminal error response or row marked failed, except the busy codes and `UNAUTHORIZED` | `tune_id`, `sample_id`, `category`, `language`, `voice`, `failure_reason` (lower-cased `error_code`), `retryable`, `http_status`, `latency_ms`, `quota_used_today`, `quota_daily_limit` | Failure time |

All three also carry `generation_id` (when a row exists), `client_request_id` and `app_version` (both from the request body).

- `has_match`: the sample's own recording already sings the name, or any ready render of the name exists in this language (any sample). `exact_match`: this sample already sings it (its authored name, or a ready render of this sample).
- `match_count`: ready `ringtone_renders` of the normalized name in this language, any sample, capped at 100. Omitted when the count query fails; `has_match` is then sent only when `exact_match` is true.
- `name_length` on `name_lookup_completed`: code points of the display name (the app's `name_length` counts UTF-16 code units; they differ only outside the BMP).
- `cached`: no new render (render-cache hit or the sample's own name); cached rows don't count toward quota. `duration_ms` is the audio length, omitted for the sample's own name.
- `latency_ms`: server time from request start to ready or failure; `duration_minutes` is the same in minutes, one decimal.
- `quota_used_today` / `quota_daily_limit`: fresh renders today (including this one when fresh) and the user's daily limit; on failures only when the error carries them (for example `quota_exceeded`).
- `retryable` mirrors the app's `GenerationErrorCode.retryable`. `voice` is `male` / `female`. Before the tune loads (for example `TUNE_NOT_FOUND`) only `tune_id` / `sample_id` (the requested id) are known.
- **Dedupe:** `$insert_id` = `mixpanelInsertId(event, generation_id)` when a generation row exists (each row reports once), else `(event, client_request_id, user id, request start)`.

### Subscription (`cashfree-webhook`, `verify-subscription`, 9)

Each event keeps only its allowlisted properties (`SERVER_EVENT_PROPS` in `subscription-analytics.ts`).

**Dedupe:** `time` is the Cashfree `event_time` (epoch ms; `verify-subscription` uses its activation time), and `$insert_id` is the first 32 hex characters of SHA-256 over a semantic key (`eventKey`). `trial_payment_succeeded` uses the Cashfree `cf_payment_id` itself when it matches `[A-Za-z0-9-]{1,36}`. A Cashfree retry therefore produces the same `(event, distinct_id, time, $insert_id)` and Mixpanel drops the duplicate. DB guards below make sure each event is sent once in the first place. `/track` rejects events older than 5 days (there is no `/import`).

**Failure handling:** DB errors return 500 so Cashfree retries; Mixpanel, `/engage` and Meta failures are logged and never fail the webhook.

| Event | Cashfree type / sender | Conditions and guard | Properties | `$insert_id` key |
|-------|------------------------|----------------------|------------|------------------|
| `trial_payment_succeeded` | `SUBSCRIPTION_AUTH_STATUS` SUCCESS (`activated_via = webhook`), or `verify-subscription` (`activated_via = app_verify`) | Conditional update of the `pending` row; only the writer that flips it sends the event, so exactly one of the two wins. Both set `cashfree_status = ACTIVE` / `cashfree_status_at`. `verify-subscription` tracks only for its own `mt_<user_id>_…` ids | `subscription_id`, `amount`, `currency`, `activated_via`, `payment_group`, `upi_handle`, `payment_app`, `cf_payment_id`, `trial_days`, `recurring_amount`, `interval_months` | The auth `cf_payment_id` as-is, else `trial:<subscription row id>` |
| `trial_expired` | `SUBSCRIPTION_STATUS_CHANGED` cancel (`cancelled_in_trial`, sent after `subscription_cancelled`), or `EXPIRED` / `COMPLETED` / `CARD_EXPIRED` (`mandate_expired` / `mandate_completed` / `card_expired`) | Only for a row that started a trial (`start_date` set) and has no recurring charge on this subscription. Once per row: compare-and-set of `subscriptions.trial_expired_at` (NULL → event time); a failed claim returns 500, so the retry sends it. A cancelled row belongs to its cancel, so a later `EXPIRED` / `COMPLETED` doesn't report it; a stale delivery never does. `ON_HOLD` is not an end (it can recover). No people update | `reason`, `ringtones_created`, `days_since_trial_start`, `subscription_id` | `trial_expired:<subscription row id>` |
| `mandate_auth_failed` | `SUBSCRIPTION_AUTH_STATUS` FAILED / CANCELLED | No DB writes | `failure_reason`, `payment_status`, `payment_group`, `upi_handle`, `retry_attempts`, `subscription_id` | `auth_failed:<cf_payment_id>:<status>:<retry_attempts>` (or cf subscription id + event time) |
| `subscription_paid` | `SUBSCRIPTION_PAYMENT_SUCCESS` | See [skip conditions](#trial-vs-recurring-do-not-mix-these). Row upserted into `subscription_payments` with `ignoreDuplicates`; tracked only when a row was inserted | `amount`, `currency`, `payment_type` (`"recurring"`), `renewal_number`, `subscription_renewal_number`, `is_first_charge`, `billing_month`, `subscription_id`, `cf_payment_id`, `retry_attempts`, `is_retry_recovery`, `payment_group`, `upi_handle`, `days_since_trial_start`, `amount_mismatch` | `paid:<cf_payment_id>` |
| `subscription_renewal_failed` | `SUBSCRIPTION_PAYMENT_FAILED`, `SUBSCRIPTION_PAYMENT_CANCELLED` | Charges only. Never inserted into `subscription_payments`: its UNIQUE `cf_payment_id` would block the later successful retry | `amount`, `currency`, `payment_status`, `failure_reason`, `retry_attempts`, `subscription_id`, `cf_payment_id` | `<type>:<cf_payment_id>:<retry_attempts>` |
| `subscription_renewal_notified` | `SUBSCRIPTION_PAYMENT_NOTIFICATION_INITIATED` (pre-debit notice) | None | `amount`, `payment_schedule_date` (IST `YYYY-MM-DD`), `subscription_id`, `cf_payment_id` | `renewal_notified:<cf_payment_id>:<schedule date>:<retry_attempts>` |
| `subscription_cancelled` | `SUBSCRIPTION_STATUS_CHANGED` with `CUSTOMER_CANCELLED` / `CANCELLED` | Skipped when the row is already cancelled; conditional update. `users.status` is downgraded to `cancelled` only for the user's latest subscription and only from `trial` / `active`. `user_downgraded` is decided by the delivery that wins the row, from the resulting `users.status`, so a retry or a concurrent delivery still reports it | `cancellation_status`, `subscription_id`, `renewals_before_cancel`, `cancelled_by` (`customer` / `merchant`), `previous_status` (`pending` / `trial` / `active`), `cancelled_during_trial`, `user_downgraded` | `cancel:<subscription row id>` |
| `subscription_status_changed` | `SUBSCRIPTION_STATUS_CHANGED`, any other status | **Tracked only**: writes `cashfree_status` / `cashfree_status_at` (compare-and-set), never `subscriptions.status` or `users.status`. Skipped when the status equals the stored one or the event is older than `cashfree_status_at` | `status`, `previous_status`, `transition`, `is_reactivation`, `next_schedule_date`, `subscription_id` | `status:<cf subscription id>:<status>:<event time>` |
| `subscription_refund_processed` | `SUBSCRIPTION_REFUND_STATUS` | User resolved through `subscription_payments.cf_payment_id`; skipped and logged if not found. No people update | `refund_status`, `refund_amount`, `currency`, `refund_speed`, `original_payment_type` (`auth` / `recurring`) | `refund:<refund id>:<refund status>` |

### Property notes

- `subscription_id` is the merchant id (`mt_<user>_<ts>`), falling back to the Cashfree id.
- `payment_app` (on `trial_payment_succeeded`) comes from `upi_handle`: `ybl` / `ibl` / `axl` → `phonepe`, `ok*` → `google_pay`, `paytm` / `pt*` → `paytm`, `upi` → `bhim`; omitted for bank and unknown handles. Same slugs as the app's `payment_app`.
- `cf_payment_id` (on `trial_payment_succeeded`) is the auth payment's Cashfree id (`authorization_details.payment_id` on the verify path).
- `trial_expired.ringtones_created` = the user's `generated_ringtones` that are ready and not cached (all time); omitted when the count fails. `CARD_EXPIRED` can resume after a card update (`transition = resumed`), so a `card_expired` `trial_expired` can precede a `subscription_paid`; the app only opens UPI mandates.
- `renewal_number` = the user's existing `recurring` rows in `subscription_payments` (all subscriptions) + 1. `subscription_renewal_number` counts this subscription row only; `is_first_charge` = `subscription_renewal_number == 1`.
- `billing_month` = IST month of Cashfree `payment_schedule_date`, else of `event_time`.
- `renewals_before_cancel` = the user's recurring rows at cancel time. `previous_status` counts an `active` row that was never charged as `trial`.
- `days_since_trial_start` = whole days from `subscriptions.start_date` to the event.
- `payment_group`: `upi`, `card`, `enach`, `pnach`, `other`. `payment_status`, `status`, `refund_status` are lower-cased Cashfree values.
- Side effect of `subscription_paid`: Meta Conversions API `Subscribe` with `event_id` = `cf_payment_id` (not Mixpanel).

**`failure_reason` buckets** (`failureReasonBucket`, a case-insensitive regex over `failure_details.failure_reason`; first match wins; the raw text is only logged after scrubbing): `insufficient_funds`, `limit_exceeded`, `account_issue`, `mandate_revoked`, `mandate_inactive`, `bank_declined`, `user_declined`, `timeout`, `bank_technical_error`, `debit_failed`, `other`. Plus `payment_cancelled` (cancelled charge), `user_cancelled` (cancelled auth) and `unknown` (no text).

**`transition` values** (`statusTransition`):

| Incoming status | `transition` | `is_reactivation` |
|-----------------|--------------|-------------------|
| `ACTIVE` after `ON_HOLD` | `recovered` | `true` |
| `ACTIVE` after `PAUSED` / `CUSTOMER_PAUSED` / `CARD_EXPIRED` | `resumed` | `true` |
| `ACTIVE` otherwise | `activated` | `false` |
| `ON_HOLD` | `on_hold` | `false` |
| `PAUSED`, `CUSTOMER_PAUSED` | `paused` | `false` |
| `EXPIRED` / `LINK_EXPIRED` / `COMPLETED` / `CARD_EXPIRED` | `expired` / `checkout_expired` / `completed` / `card_expired` | `false` |
| `BANK_APPROVAL_PENDING` / `INITIALIZED` | same name, lower-cased | `false` |
| anything else | `other` | `false` |

### Webhook types that do **not** send Mixpanel

| Cashfree type | Response | Why |
|---------------|----------|-----|
| `SUBSCRIPTION_AUTH_STATUS` with another status | `skipped: auth_<status>` | Not terminal |
| `SUBSCRIPTION_PAYMENT_SUCCESS` for `AUTH`, zero amount, duplicate or unknown subscription | `skipped: …` | Not a new recurring charge |
| `CARD_EXPIRY_REMINDER`, `CONTROLLED_*`, unknown | `{ received: true, ignored }` | The app only creates UPI mandates |

---

## Mixpanel HTTP helper (server)

`supabase/functions/_shared/mixpanel.ts`

| Function | Endpoint | Use |
|----------|----------|-----|
| `trackMixpanelEvent(token, distinctId, event, properties, { insertId, timeMs })` | `POST https://api.mixpanel.com/track?ip=0&verbose=1` | Server events; never throws, 4 s timeout, parses `{status, error}` |
| `updateMixpanelPeople(token, distinctId, { set, setOnce, unset })` | `POST https://api.mixpanel.com/engage?ip=0&verbose=1` | `$set` / `$set_once` / `$unset` with `$ignore_time: true` |
| `mixpanelInsertId(...parts)` | — | Deterministic `$insert_id`: 32 hex chars of SHA-256 over the JSON of the parts |
| `resolveMixpanelToken(config)` | — | Env secret, then `app_config.mixpanel_token` |
| `parseCashfreeTimeMs(value)` | — | Epoch ms of a Cashfree time; naive times are IST |
| `istMonth(ms)` / `istDate(ms)` / `billingMonthFromDate(iso)` | — | IST `YYYY-MM` / `YYYY-MM-DD` |
| `cleanProps(props)` | — | Drops blanks, non-finite numbers, malformed / reserved keys and PII-looking keys |

The track payload always includes `token`, `distinct_id`, `time` (epoch **ms**, the source event time), `platform: "server"` and `$insert_id`, spread after the event properties so callers cannot override them. When `insertId` is not passed it is derived from `(event, distinct_id, time)`.

`_shared/subscription-analytics.ts` holds the pure subscription mapping (prop allowlists, payload readers, `paymentGroup`, `upiHandle`, `paymentAppFromUpiHandle`, `paymentInsertId`, `failureReasonBucket`, `scrubFailureText`, `statusTransition`, `shouldApplyStatus`, `trialExpiredReason`, `isTrialExpiryCandidate`, `eventKey`). `generate-ringtone/analytics.ts` holds the generation builders and rules (`reportsFailure`, `reportsLookup`, `insertIdParts`, `runInBackground`). Both are unit tested.

---

## Mixpanel vs Meta vs Firebase

Mixpanel is the product analytics source of truth. Ads conversions use other tools. Do not import Mixpanel into Google Ads.

| Mixpanel event | Meta | Firebase / Google Ads |
|----------------|------|------------------------|
| `sign_up_completed` | `CompleteRegistration` | — |
| `login_completed` | identify only | identify only |
| `subscription_screen_viewed` | `ViewContent` | — |
| `subscription_initiated` (was `subscription_started`) | `InitiatedCheckout` | — |
| `trial_payment_completed` | `Purchase` | `purchase` (Ads conversion) |
| `trial_payment_succeeded` | — (Meta `Purchase` comes from the app) | — |
| `subscription_paid` | `Subscribe` (Conversions API, webhook; only when `META_DATASET_ID` / `META_CONVERSIONS_API_ACCESS_TOKEN` are set, currently unset) | — |
| `subscription_cancelled`, `subscription_renewal_failed`, `trial_expired`, other server events | — | — |
| `subscription_failed`, `paywall_dismissed` | — | — |
| `logged_out` | `clearUserId()` | `clearUserId()` |
| Ringtone / engagement / shell events (app or `generate-ringtone`) | — | — |

---

## Data we do not send to Mixpanel

- Phone numbers (app or server), contact names or numbers
- Email addresses as `distinct_id`
- OTP values
- Raw search query text (only `query_length` and `result_count`)
- The typed ringtone name, its normalized or spoken form, or the generated title, from the app or `generate-ringtone` (only `name_length`; personalized `tune_name` falls back to the authored `title_template`)
- Exception or server error text (`failure_reason` is bounded; `error_type` is the class simple name only)
- The full UPI VPA (only `upi_handle`), Cashfree failure text, refund notes, bank details
- Install referrer keys other than `utm_source`, `utm_medium`, `utm_campaign` (`gclid` becomes `has_gclid`)
- Empty or null properties (omit instead)

Exception: the SDK's own `$ae_crashed` carries `$ae_crashed_reason` = the exception's `toString()`.

Consent is not gated yet. If EU/California users are added, initialize the SDK only after consent.

---

## Naming conventions

- Event names: `snake_case`, past tense (`ringtone_created`, not `create_ringtone`)
- Property names: `snake_case`
- Enum-like values: lowercase snake_case (`phonepe`, `audio_only`). `putEnum` lower-cases and drops anything outside `[a-z0-9_]{1,64}`
- `source` = where the action happened (`home`, `search_results` = Home with a search query, `search_bar` = the Home empty-search CTA, `membership_welcome` = the member screen, `song_picker`, `name_ringtones` = the create flow's existing name ringtones step (row previews and its Set), `creation_flow` = the create path (the set flow at chuno and on the Ready screen, Ready-screen playback) and the server `ringtone_created`, `profile`, `phone_entry`, `otp_entry`, `name_entry`, `subscription`, `ringtone_processing`). `previous_screen` = where the user came from, computed by the lifecycle tracker (no intent extras). `entry_point` = how a funnel screen was reached (paywall, create form)
- `sample_id` = the picked sample's tune id (spec name; equals `tune_id` in the create flow)
- `failure_reason`: always bounded, never exception or server text. `network` and `timeout` are separate; `user_cancelled` for backing out
- Shared names: `attempt` (not `attempt_number`), `trigger` (`initial` / `retry` / `restored` / …), `error_type` (exception class simple name)
- `$insert_id`: app events get a random UUID per event (dedupes SDK re-sends only); server events get a deterministic hash of a semantic key (dedupes webhook retries), or the Cashfree `cf_payment_id` for `trial_payment_succeeded`
- People: `set` / `set_once` / `unset` only, no increments
- Currency: `"INR"`
- User ID: database id string only

---

## Wire when the feature ships

Names and values from the name-ringtone spec for features that are not built yet. There is no code for them: add them through `MixpanelAnalytics` when the trigger exists, with these exact names.

| Event or value | Properties / where |
|----------------|--------------------|
| `locked_action_blocked` (new) | `action` |
| `locked_sample_tapped` (new) | `sample_id` |
| `ringtone_ready_notification_tapped` (new) | `ringtone_id` |
| `ringtone_downloaded` (new) | `ringtone_id` |
| `create_ringtone_cta_tapped` `source`, `ringtone_creation_started` `entry_point` = `home_button` / `trial_nudge` | A Home create button and a trial nudge |
| `creation_limit_reached` `limit_type = trial` / `cycle` | Once trial and billing-cycle tiers exist (today only `daily`) |
| paywall `entry_point = locked_home` | Already derived for `previous_screen = home`; fires once Home can open the paywall |

---

## Implementation map

| Component | Path |
|-----------|------|
| Init | `app/src/main/java/com/spacewire/meratune/MeraTuneApplication.kt` |
| App helper | `app/src/main/java/com/spacewire/meratune/analytics/MixpanelAnalytics.kt` |
| Slugs, value constants, permission snapshot, daily cap | `analytics/AnalyticsContract.kt` |
| `app_opened` / `screen_viewed` | `analytics/AnalyticsLifecycleCallbacks.kt` |
| `install_attributed` | `analytics/InstallReferrerTracker.kt` |
| `$ae_session` switch | `app/src/main/AndroidManifest.xml` (`MPConfig.MinimumSessionDuration`) |
| Token, SDK pin | `app/build.gradle.kts` → `BuildConfig.MIXPANEL_TOKEN` |
| OTP send / phone validation / terms links | `PhoneAuthActivity.kt`, `util/AuthTermsHelper.kt` |
| OTP resend / verify / login | `OtpVerificationActivity.kt`, `util/SmsOtpFetcher.kt` (`otp_entry_method`) |
| Auth failure mapping | `data/AuthRepository.kt` (`AuthStage`, `AuthFailureReason`) |
| Signup | `SignUpNameActivity.kt` |
| Post-auth destination | `util/AuthNavigator.kt` |
| Language | `LanguageSelectionActivity.kt` |
| Paywall / trial / failures / video / logout / Home button and back | `SubscriptionActivity.kt`, `ui/PaymentAppBottomSheet.kt`, `model/PaymentApp.kt` (installed apps, UPI ID option), `PaymentAppSlug` in `analytics/AnalyticsContract.kt` |
| Paywall Pending / Failed rules | `ui/PaywallUiPolicy.kt` (`PaywallUiState`, `VerifyTrigger`) |
| Member screen (`membership_welcome`, post-purchase CTA) | `MembershipWelcomeActivity.kt` |
| Subscription failure mapping | `data/SubscriptionRepository.kt` (`SubscriptionFailureReason`) |
| Create form | `CreateRingtoneActivity.kt`, `ui/FormOptionGroup.kt` |
| Song picker (preview, chuno) | `ChooseSongActivity.kt` (`data/SongRanker.kt` for `rank` / `fallback_level`; chuno → `RingtoneSetController.choose`) |
| Generation | `RingtoneProcessingActivity.kt` (error actions, session-expired logout), `ui/RingtoneGenerationViewModel.kt`, `data/RingtoneGenerationRepository.kt` (`GenerationErrorCode` → `failure_reason`) |
| Ready screen | `RingtoneReadyActivity.kt` |
| Preview sessions (`tune_play_ended`) | `ui/PreviewPlayerController.kt` |
| Set ringtone | `calltheme/RingtoneSetController.kt` (`start` / `choose` / `apply`), `calltheme/SetChoice.kt`, `calltheme/SetEntryContext.kt`, `calltheme/RingtoneSetMode.kt`, `calltheme/CallThemeImageHelper.kt` (photo staging), `ui/SetRingtoneBottomSheet.kt`, `ui/UploadPhotoBottomSheet.kt` |
| Incoming call | `calltheme/IncomingCallEvents.kt`, `calltheme/IncomingCallThemeActivity.kt`, `calltheme/IncomingCallActionReceiver.kt` |
| Startup permissions | `util/StartupPermissionRequester.kt` |
| Home / play / empty CTA / set entry | `Home.kt`, `ui/HomeScreenViewGate.kt` (`onNewIntent` screen-view de-dup) |
| Home load / search / category / name chip / replaced ringtone | `ui/HomeViewModel.kt`, `ui/HomeTuneFilter.kt`, `util/LoadErrorMapper.kt`, `util/ActiveRingtoneStore.kt` |
| Profile logout / links | `ProfileActivity.kt` |
| Webhook | `supabase/functions/cashfree-webhook/index.ts`, `cashfree-webhook/signature.ts` |
| Trial via app verify | `supabase/functions/verify-subscription/index.ts` |
| Generation events | `supabase/functions/generate-ringtone/index.ts` (`reportCreated`, `reportFailure`, `countNameMatches`), `generate-ringtone/analytics.ts` |
| Mixpanel HTTP | `supabase/functions/_shared/mixpanel.ts` |
| Server event mapping | `supabase/functions/_shared/subscription-analytics.ts` |
| Payment dedup table | `subscription_payments` (`20260728160000_add_subscription_payments.sql`) |
| Cashfree status, trial expiry guard, signature mode, name-lookup index | `20260924120000_add_subscription_cashfree_status.sql` |
| Backup exclusions | `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml` |
| Tests | `app/src/test/…` (`DailyCapPolicyTest`, `AnalyticsDerivationTest` (`user_state`, paywall `entry_point`, including `membership_welcome` → omitted), `LoadErrorMapperTest`, `AuthFailureReasonTest`, `SubscriptionFailureReasonTest`, `SetFailureClassifierTest`, `GenerationErrorCodeTest`, `PaywallUiPolicyTest`, `HomeScreenViewGateTest`, `SetChoiceTest`, `PaymentAppTest`, `HomeTuneFilterTest`), `supabase/functions/tests/mixpanel_test.ts`, `cashfree_webhook_test.ts`, `generate_analytics_test.ts` |

---

## Event count

| Category | Count |
|----------|-------|
| Session and shell | 6 (`app_opened`, `screen_viewed`, `install_attributed`, `permission_prompt_answered`, `external_link_opened`, `logged_out`) |
| Auth and onboarding | 6 (`otp_sent`, `auth_failed`, `otp_verification_failed`, `sign_up_completed`, `login_completed`, `language_selected`) |
| Subscription (app) | 8 (`subscription_screen_viewed`, `paywall_dismissed`, `subscription_cta_tapped`, `payment_app_selected`, `subscription_initiated`, `trial_payment_completed`, `subscription_failed`, `subscription_video_ended`) |
| Home and catalog | 8 (`home_viewed`, `home_load_failed`, `tune_played`, `tune_play_ended`, `search_performed`, `category_filtered`, `create_ringtone_cta_tapped`, `ringtone_replaced_externally`) |
| Ringtone activation (app) | 11 (`ringtone_creation_started`, `unavailable_language_tapped`, `sample_list_viewed`, `sample_previewed`, `sample_selected`, `voice_filtered`, `ringtone_generation_started`, `ringtone_generation_failed`, `creation_limit_reached`, `generation_error_action_taken`, `ringtone_ready_action_tapped`) |
| Set flow | 4 (`ringtone_set_started`, `set_mode_selected`, `ringtone_set_failed`, `ringtone_set`) |
| Incoming call (capped) | 3 (`call_theme_displayed`, `incoming_call_action_tapped`, `incoming_call_overlay_displayed`) |
| Generation (server) | 3 (`name_lookup_completed`, `ringtone_created`, `ringtone_generation_failed`; the last is also an app event) |
| Subscription (server) | 9 (`trial_payment_succeeded`, `trial_expired`, `mandate_auth_failed`, `subscription_paid`, `subscription_renewal_failed`, `subscription_renewal_notified`, `subscription_cancelled`, `subscription_status_changed`, `subscription_refund_processed`) |
| **Total** | **57** distinct event names: 46 app + 12 server, `ringtone_generation_failed` counted once (plus 3 SDK automatic events) |

Renamed on this branch before release (no history to migrate): `song_picker_viewed` → `sample_list_viewed` (`song_count` → `sample_count`), `sample_song_played` → `sample_previewed` and `sample_song_selected` → `sample_selected` (`tune_id` → `sample_id`), `auth_failed` with `stage = verify_otp` → `otp_verification_failed`, `trial_activated` → `trial_payment_succeeded`, `subscription_payment_failed` → `subscription_renewal_failed`, `create_ringtone_cta_tapped.source` `empty_search` → `search_bar`. The app's `ringtone_created` moved to `generate-ringtone`.

### UI refresh changes

The UI refresh adds and removes no events (still 57) and changes no server code. What changed in values and timing:

| Area | Change |
|------|--------|
| `screen_viewed` | New slug `membership_welcome` (12 screens). The create form after a purchase has `previous_screen = membership_welcome`. Home `onNewIntent` no longer double-tracks a just-created Home |
| `create_ringtone_cta_tapped` | New `source = membership_welcome` (member screen CTA and its 3 rows) |
| `ringtone_creation_started` | New `entry_point = post_purchase`; `ready_screen` is no longer sent. No new properties |
| `paywall_dismissed` | New `dismiss_method = home_button` (the Home button that replaced the close X). System back on the paywall now goes to Home like that button (still `system_back`) instead of closing the app. Back on Pending / Failed is not a dismissal |
| `subscription_initiated` | Renamed from `subscription_started` (2026-09-28); `auth_amount` / `recurring_amount` always sent; new `user_state` |
| `subscription_failed` | Same values, plus `auth_amount`, `recurring_amount` and `user_state` (2026-09-28). There is no automatic re-check; each manual "Payment Status Dekhein" re-check that doesn't activate sends one `verify` failure |
| `trial_payment_completed` (+ Meta `Purchase`, Firebase `purchase`) | Same handler, once per paywall; now also reachable from the Pending re-check. The member screen opens next instead of Home |
| `sample_previewed` / `sample_selected` | A row tap only previews; `sample_selected` moves to the first "chuno" tap |
| `voice_filtered`, `category_filtered` (`song_picker`) | Unchanged (the picker keeps its voice and category chips) |
| Set flow, create path | `ringtone_set_started`, `set_mode_selected` and the `mode_sheet` / `photo_sheet` cancels fire at chuno; the apply stages, `ringtone_set` and the Set-flow permission prompts fire on Ready. `flow_duration_ms` excludes generation. Open flows are possible ([Create-path split](#create-path-split)) |
| Set flow, Home path | Unchanged, except that Android 8/9 now shows the mode sheet (and sends `set_mode_selected`) after the storage grant |
| `ringtone_ready_action_tapped` | `change_song` / `make_another` are no longer sent. `go_home` ("Home par jayen", always shown) can have `is_set = false` |
| `external_link_opened` | New sources `otp_entry` and `name_entry` (terms footer on all 3 auth screens) |
| `permission_prompt_answered` | New members answer the `startup` prompts on their first Home visit (usually after the create flow), only for permissions still missing. On the create path, WRITE_SETTINGS (`set_ringtone`) is asked up front on Ready, before the download |
| `language_selected` | `context = settings` now comes only from Profile (the auth screens and paywall lost their language button) |
| `payment_app` (paywall events) | New `upi_id` (Cashfree hosted checkout); the pill no longer defaults to PhonePe when no UPI app is installed, so `subscription_cta_tapped` sends `payment_app_installed = false` for `upi_id` (always) or for a UPI app uninstalled while the paywall was open (restored selection; that case still fires the `precheck` failure), and `subscription_failed` `precheck` / `no_payment_app_installed` is no longer sent from the pill |
| `category_filtered`, `tune_played` / `search_performed` `category_filter` | New Home name chip: `category_id = __my_name__`, `category_name` / `category_filter` = `my_name` |
| `create_ringtone_cta_tapped` / `ringtone_creation_started` | New `source` / `entry_point` = `my_name_chip` (the Home empty-state CTA under the name chip, no search query); the form then reports `prefill_source = profile_name` |

---

## Verification

1. **Unit tests:** `./gradlew :app:testDebugUnitTest` and `deno test --allow-read supabase/functions/tests` (insert id format, IST month boundaries, token fallback, fetch failures, signature matrix, failure buckets, transitions, fixtures with allowlisted keys only and no phone, email or `@`; generation props, reporting rules and insert ids).
2. **Debug app:** trigger each flow; confirm events in Mixpanel Live View (filter `build_type = debug`) and Logcat. Server events have no `build_type`: find them with `platform = server` and the test user's `distinct_id`.
3. **Recreation and double taps:** rotate on each screen and double-tap each Continue, chuno and the member-screen CTA; no event fires twice.
4. **Identity:** signup/login events share the same `distinct_id` as later subscription and webhook events for that user.
5. **Logout:** `logged_out` arrives under the user's id; next events use a new anonymous ID until login.
6. **Trial vs paid:** `trial_payment_succeeded` once per subscription row (server); `trial_payment_completed` only from the app; `subscription_paid` only from the webhook; `trial_expired` at most once per row.
7. **Webhook retries:** re-posting the same payload gives one event (`$insert_id` + `event_time`, and the DB guards).
8. **Lexicon:** add descriptions for all 57 events in Mixpanel Data Management.
9. **Funnels:** build the Insights funnels listed in [Conversion funnel](#conversion-funnel).
10. **Create flow:** run one generation end to end (with `generate-ringtone` deployed) and confirm Live View shows `create_ringtone_cta_tapped (source=search_bar) → ringtone_creation_started (entry_point=search_bar) → sample_list_viewed → sample_previewed → sample_selected → ringtone_set_started (source=creation_flow, no generation_id) → set_mode_selected → ringtone_generation_started → name_lookup_completed → ringtone_created (platform=server, cached=false) → ringtone_set (generation_id, personalized=true)`, with no second `ringtone_set_started` on Ready, that `ringtone_created` appears once and only from the server, and that no property contains the typed name.
11. **Post-purchase:** pay the trial and confirm `trial_payment_completed → screen_viewed (membership_welcome) → create_ringtone_cta_tapped (source=membership_welcome) → screen_viewed (create_form, previous_screen=membership_welcome) → ringtone_creation_started (entry_point=post_purchase)`. After "Home par jayen", exactly one `screen_viewed (home)`.
12. **Paywall states:** UPI cancel → one `subscription_failed (checkout, user_cancelled)` and back on the paywall. A checkout whose verify is not active yet → one `subscription_failed (verify, pending)` and Pending; each "Payment Status Dekhein" tap → one `subscription_failed (verify)` or `trial_payment_completed`. Back on Pending / Failed → no `paywall_dismissed`. Home button → `paywall_dismissed (dismiss_method=home_button)` and Home; system back on the paywall → `paywall_dismissed (dismiss_method=system_back)` and Home; back on Home closes the app.
