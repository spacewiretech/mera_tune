# MeraTune — Agent Guidelines

## Mixpanel Analytics

Mixpanel is the product analytics tool for this project. All new user-action tracking should go through the centralized helper.

| Setting | Value |
|---|---|
| Platform | Android (Kotlin) |
| SDK | `com.mixpanel.android:mixpanel-android:7.+` |
| Token location | `BuildConfig.MIXPANEL_TOKEN` (override via `mixpanel.token` in `local.properties`) |
| Init | `MeraTuneApplication.onCreate()` → `MixpanelAnalytics.init()` |
| Helper | `app/src/main/java/com/spacewire/meratune/analytics/MixpanelAnalytics.kt` |

### Tracking plan

| Event | Trigger | Properties |
|---|---|---|
| `otp_sent` | OTP send/resend succeeds | `is_resend`, `platform` |
| `sign_up_completed` | New user completes name entry (`SignUpNameActivity`) | `sign_up_method`, `platform` |
| `login_completed` | Returning user OTP verify succeeds (`OtpVerificationActivity`) | `sign_in_method`, `platform` |
| `subscription_screen_viewed` | Subscription paywall opens | `platform` |
| `subscription_started` | Create-subscription API succeeds, before Cashfree checkout | `payment_app`, `auth_amount`, `recurring_amount`, `platform` |
| `trial_payment_completed` | Trial/mandate payment verified in app (`SubscriptionActivity`) | `payment_app`, `subscription_id`, `amount`, `currency`, `platform` |
| `subscription_paid` | ₹249 recurring autopay succeeds (Cashfree webhook) | `amount`, `currency`, `payment_type`, `renewal_number`, `billing_month`, `subscription_id`, `cf_payment_id`, `platform` (`server`) |
| `subscription_cancelled` | User cancels subscription (Cashfree webhook) | `cancellation_status`, `subscription_id`, `renewals_before_cancel`, `platform` (`server`) |
| `subscription_failed` | Create, checkout, or verify failure | `stage`, `failure_reason`, `payment_app`, `platform` |
| `ringtone_creation_started` | Continue on create form (name + language, `CreateRingtoneActivity`) | `language`, `name_length`, `platform` |
| `song_picker_viewed` | Song picker reaches a terminal load state (content / empty / error, `ChooseSongActivity`) | `language`, `song_count`, `category_count`, `fallback_level` (`none` / `hindi` / `any`), `voice_filter` (`male` / `female`, omitted for all), `platform` |
| `sample_song_played` | Preview starts for a card in the song picker | `tune_id`, `category`, `language`, `voice`, `rank`, `platform` |
| `sample_song_selected` | First selection of a card in the song picker | `tune_id`, `category`, `language`, `voice`, `rank`, `voice_filter`, `category_filter`, `platform` |
| `ringtone_generation_started` | `generate-ringtone` request posted (once per attempt, `RingtoneGenerationViewModel`) | `tune_id`, `category`, `language`, `voice`, `name_length`, `is_retry`, `platform` |
| `ringtone_created` | `generate-ringtone` succeeds (exactly once per generation) | `tune_id`, `category`, `language`, `voice`, `cached`, `duration_ms`, `client_ms`, `generation_id`, `source` (`creation_flow`), `platform` |
| `ringtone_generation_failed` | Generation ends without a ringtone (server error, transport error, or user cancel) | `tune_id`, `category`, `language`, `voice`, `failure_reason`, `http_status`, `retryable`, `client_ms`, `platform` |
| `ringtone_set` | Ringtone successfully set as default | `source`, `category`, `tune_id`, `tune_name` (omitted for personalized tunes without a `title_template`), `set_mode` (`audio_only` / `with_image_everyone` / `with_image_contact`), `generation_id`, `personalized`, `platform` |
| `language_selected` | Language continue tapped | `language`, `locale`, `context`, `platform` |
| `home_viewed` | Home loads successfully (once per session) | `tune_count`, `category_count`, `platform` |
| `tune_played` | Tune playback starts on Home | `tune_id`, `category`, `source`, `platform` |
| `search_performed` | Search query debounced 500ms | `query_length`, `result_count`, `platform` |
| `category_filtered` | Category chip selected (Home catalog or song picker) | `category_id`, `category_name`, `source` (`home` / `song_picker`), `platform` |
| `create_ringtone_cta_tapped` | Empty-search create CTA tapped | `source`, `prefill_name_length`, `platform` |

Values before app version 1.3.0: `ringtone_creation_started` and `ringtone_created` carried `voice`, `category`, `language` as **localized form labels** (e.g. "Female voice", "भक्ति"). From 1.3.0 `voice` is `male` / `female`, `category` is the database category name and `language` is the storage value (`Hindi`, `English`, …). Segment by `app_version` when comparing across the change.

Create-flow funnel: `create_ringtone_cta_tapped → ringtone_creation_started → song_picker_viewed → sample_song_played → sample_song_selected → ringtone_generation_started → ringtone_created | ringtone_generation_failed → ringtone_set`.

### Super properties (auto-attached)

- `platform` — `"android"`
- `app_version` — from `BuildConfig.VERSION_NAME`

### Identity

| Action | Location | Call |
|---|---|---|
| Sign up | `SignUpNameActivity` | `identify(user.id)` → `people.set()` → `track("sign_up_completed")` |
| Login | `OtpVerificationActivity.completeLogin()` | `identify(user.id)` → `people.set()` → `track("login_completed")` |
| Trial payment complete | `SubscriptionActivity.onSubscriptionVerify()` | `identifyUser(user)` → `track("trial_payment_completed")` |
| Recurring payment | `cashfree-webhook` on `SUBSCRIPTION_PAYMENT_SUCCESS` | Mixpanel HTTP API, `distinct_id = user.id` |
| Subscription cancelled | `cashfree-webhook` on `SUBSCRIPTION_STATUS_CHANGED` | Mixpanel HTTP API, `distinct_id = user.id` |
| App re-open (logged in) | `MixpanelAnalytics.restoreIdentity()` | `identify(user.id)` |
| Logout | `ProfileActivity`, `SubscriptionActivity` | `logout()` → `reset()` + `ProfileStore.clearSession()` |

### User profile properties (people.set)

| Property | When |
|---|---|
| `$name` | Login / signup |
| `subscription_status` | Login / signup / trial payment / webhook renewals & cancel |
| `total_renewals` | Each successful ₹249 webhook charge |
| `last_billing_month` | Latest recurring charge month |
| `app_language` | Language selected |
| `last_ringtone_category` | Ringtone generated (`ringtone_created`) or set (`ringtone_set`) |

### Conventions

- Event names: `snake_case`, past tense (`ringtone_created`, not `create_ringtone`)
- Property names: `snake_case`
- User ID: database primary key (`user.id.toString()`), never phone or email
- Omit properties when they have no value; do not send `null` or empty strings
- Add new events via `MixpanelAnalytics` methods, not raw SDK calls scattered in activities
- Do not send raw phone numbers, search queries, or OTP values
- Create flow: never send the typed name or the generated ringtone title (only `name_length`); `voice` is `male` / `female` (`Tune.voiceKey`, omitted when blank); `category` is the DB category name; `language` is the storage value; `failure_reason` is the lower-cased `GenerationErrorCode` name (`quota_exceeded`, `timeout`, …) or `user_cancelled`
- The client owns every create-flow event; `generate-ringtone` sends nothing to Mixpanel (ops/cost reporting is SQL on `generated_ringtones`)

### Consent

No consent gate is implemented yet. If EU/California users are added, gate SDK initialization behind consent before tracking.

### Webhook setup

Server-side Mixpanel events require `MIXPANEL_TOKEN` as a Supabase Edge Function secret on `cashfree-webhook`.

In Cashfree Dashboard → Webhooks, enable:
- `SUBSCRIPTION_AUTH_STATUS`
- `SUBSCRIPTION_PAYMENT_SUCCESS`
- `SUBSCRIPTION_STATUS_CHANGED`

Apply migration `20260728160000_add_subscription_payments.sql` before deploying the updated webhook.

### Verification

After adding events, confirm in Mixpanel Live View (debug builds log to Logcat). Add Lexicon descriptions in Mixpanel Data Management.

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
| `Subscribe` | `subscription_paid` | ₹249 recurring autopay succeeds (Cashfree webhook via Conversions API) |

**App events (SDK):** `CompleteRegistration`, `ViewContent`, `InitiatedCheckout`, `Purchase` — via `MetaAnalytics.kt`.

**Server events (Conversions API):** `Subscribe` — via `cashfree-webhook` + `_shared/meta.ts`. Uses `external_id` = SHA-256(`user.id`) to match app events. Deduped with `event_id` = `cf_payment_id`.

**Properties on checkout/subscribe/purchase:** `fb_currency` / `currency` = `"INR"`, value = auth or recurring amount, `content_type` = `"subscription"`.

**Not sent to Meta:** `login_completed`, `subscription_failed`, engagement/ringtone events — use Mixpanel for those.

### Identity

| Action | Location | Call |
|---|---|---|
| Sign up | `SignUpNameActivity` | `identifyUser(user)` → `trackCompleteRegistration("phone")` |
| Login | `OtpVerificationActivity.completeLogin()` | `identifyUser(user)` |
| Trial payment | `SubscriptionActivity.onSubscriptionVerify()` | `identifyUser(user)` → `trackTrialPaymentCompleted()` → Meta `Purchase` |
| Recurring payment | `cashfree-webhook` on `SUBSCRIPTION_PAYMENT_SUCCESS` | Meta Conversions API `Subscribe`, `external_id` = `user.id` |
| App re-open (logged in) | `MetaAnalytics.restoreIdentity()` | `AppEventsLogger.setUserID(user.id)` |
| Logout | `ProfileActivity`, `SubscriptionActivity` | `clearUserId()` |

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
6. In Ads Manager: optimize acquisition campaigns for **Purchase** (trial); optimize ROAS/retention for **Subscribe** (₹249 renewals).

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

**Not sent to Firebase:** `login_completed`, `sign_up_completed`, paywall views, checkout start, `subscription_failed`, engagement/ringtone events, or recurring ₹249 autopay. Use Mixpanel (and Meta `Subscribe` for renewals) for those.

**Properties on purchase:** `value` = auth amount, `currency` = `"INR"`, `transaction_id` = Cashfree subscription id, `items` = one subscription item.

Firebase still collects default SDK events (`first_open`, `session_start`) used for Google Ads install attribution. Automatic screen reporting is disabled.

### Identity

| Action | Location | Call |
|---|---|---|
| Sign up | `SignUpNameActivity` | `identifyUser(user)` |
| Login | `OtpVerificationActivity.completeLogin()` | `identifyUser(user)` |
| Trial payment | `SubscriptionActivity.onSubscriptionVerify()` | `identifyUser(user)` → `trackTrialPaymentCompleted()` → Firebase `purchase` |
| App re-open (logged in) | `FirebasePurchaseAnalytics.restoreIdentity()` | `setUserId(user.id)` |
| Logout | `ProfileActivity`, `SubscriptionActivity` | `clearUserId()` |

User ID is the database primary key (`user.id.toString()`), same as Mixpanel and Meta. Never phone or email.

### Setup checklist

1. Confirm the Android app in [Firebase Con sole](https://console.firebase.google.com/) project `mera-tune` uses package `com.spacewire.meratune`, and keep `app/google-services.json` in sync if the app is re-downloaded.
2. Enable **Google Analytics** for the Firebase project if it is not already linked.
3. In Firebase / GA4: **Admin → Google Ads links** → link the Google Ads account that will run app campaigns.
4. In Google Ads: **Goals → Conversions → Summary → + New conversion action → Import → Google Analytics 4 properties → App** → import **`purchase`**. Mark it as a primary conversion for acquisition campaigns.
5. Add the app's SHA-1 (debug and Play App Signing) in Firebase Console → Project settings → Your apps, so Analytics and Ads attribution match the signed builds.
6. Debug builds: `adb shell setprop debug.firebase.analytics.app com.spacewire.meratune`, then confirm `purchase` in Logcat (`FirebasePurchase`) and in Firebase DebugView / GA4 DebugView. Purchase value is the trial auth amount in INR.
7. In Google Ads: optimize App campaigns for **Purchase** (trial). Do not import Mixpanel or Meta events into Google Ads; Firebase `purchase` is the conversion source.
