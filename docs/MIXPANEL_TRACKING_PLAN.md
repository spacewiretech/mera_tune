# MeraTune Mixpanel documentation

Product analytics for the MeraTune Android app. This document covers every Mixpanel event that is implemented today: app SDK events, Cashfree webhook events, identity, user profiles, conversion funnels, and how Mixpanel relates to Meta and Firebase.

**Last updated:** September 2026 (matches current codebase)

| Item | Value |
|------|--------|
| Product | MeraTune |
| Mixpanel SDK (Android) | `com.mixpanel.android:mixpanel-android:7.+` |
| App helper | `app/src/main/java/com/spacewire/meratune/analytics/MixpanelAnalytics.kt` |
| Init | `MeraTuneApplication.onCreate()` → `MixpanelAnalytics.init()` |
| Project token (app) | `BuildConfig.MIXPANEL_TOKEN` from `mixpanel.token` in `local.properties` |
| Server helper | `supabase/functions/_shared/mixpanel.ts` |
| Server sender | `supabase/functions/cashfree-webhook/index.ts` |
| `distinct_id` | Database primary key `users.id` as a string. Never phone or email. |

All new Mixpanel tracking must go through `MixpanelAnalytics` in the app or `_shared/mixpanel.ts` on the server. Do not call the Mixpanel SDK or HTTP API from random activities or functions.

---

## Architecture

```
Android app                          Cashfree
───────────                          ────────
MixpanelAnalytics.kt                 Webhooks
  SDK track() + people.set()           │
  identify(user.id)                    ▼
                                       cashfree-webhook
                                       ├─ Mixpanel HTTP /track
                                       ├─ Mixpanel HTTP /engage ($set)
                                       └─ Meta Conversions API (Subscribe)
```

| Source | Events | Transport |
|--------|--------|-----------|
| Android app | 21 events | Mixpanel Android SDK |
| Cashfree webhook | 2 events | Mixpanel HTTP Track API (`https://api.mixpanel.com/track`) |

The personalized-ringtone Edge Function `generate-ringtone` sends **nothing** to Mixpanel; the app owns every create-flow event. Ops and cost reporting for generation is SQL over `generated_ringtones` / `ringtone_renders`.

`SUBSCRIPTION_AUTH_STATUS` updates the database (trial activation) but **does not** send a Mixpanel event. The first-payment Mixpanel event is `trial_payment_completed` from the app after verify succeeds.

---

## Setup

### Android

1. Add to `local.properties`:
   ```
   mixpanel.token=YOUR_MIXPANEL_PROJECT_TOKEN
   ```
2. Gradle writes it into `BuildConfig.MIXPANEL_TOKEN` (`app/build.gradle.kts`).
3. Debug builds pass `BuildConfig.DEBUG` into `MixpanelAPI.getInstance`, so events show in Mixpanel Live View and Logcat.

### Server (Cashfree webhook)

Set **one** of:

- Supabase Edge Function secret `MIXPANEL_TOKEN` on `cashfree-webhook` (preferred)
- `app_config.mixpanel_token` in the database (fallback)

Webhook resolution:

```
Deno.env MIXPANEL_TOKEN → app_config.mixpanel_token → skip tracking if empty
```

If the token is missing, track calls log a warning and return `{ ok: false, error: "token_missing" }`. Subscription DB updates still run.

### Cashfree Dashboard → Webhooks

Enable:

- `SUBSCRIPTION_AUTH_STATUS` — trial activation in DB (no Mixpanel event)
- `SUBSCRIPTION_PAYMENT_SUCCESS` — Mixpanel `subscription_paid`
- `SUBSCRIPTION_STATUS_CHANGED` — Mixpanel `subscription_cancelled`

Apply migration `supabase/migrations/20260728160000_add_subscription_payments.sql` before relying on renewal counts and payment dedup.

---

## Super properties

Attached automatically to every **app** event via `registerSuperProperties()`. Re-registered after `reset()` on logout.

| Property | Type | App | Webhook |
|----------|------|-----|---------|
| `platform` | string | `"android"` | `"server"` |
| `app_version` | string | `BuildConfig.VERSION_NAME` | not sent |

Webhook events also set `platform: "server"` on each track payload (not as Mixpanel super properties).

---

## Identity

`distinct_id` is always `user.id.toString()` (Postgres `users.id`).

| Action | Location | Mixpanel calls |
|--------|----------|----------------|
| Sign up | `SignUpNameActivity` | `identify(user.id)` → `people.set($name, subscription_status)` → `track("sign_up_completed")` |
| Login | `OtpVerificationActivity.completeLogin()` | `identify` → `people.set` → `track("login_completed")` |
| Trial payment | `SubscriptionActivity.onSubscriptionVerify()` | `identify` → `track("trial_payment_completed")` → `people.set(subscription_status = "trial")` |
| App re-open (logged in) | `MixpanelAnalytics.restoreIdentity()` | `identify(user.id)` if `AuthStore.isLoggedIn()` |
| Logout | `ProfileActivity`, `SubscriptionActivity` | `reset()` then re-register super properties; `ProfileStore.clearSession()` |
| Recurring payment | `cashfree-webhook` `SUBSCRIPTION_PAYMENT_SUCCESS` | HTTP track with `distinct_id = user.id` + `/engage` `$set` |
| Cancel | `cashfree-webhook` `SUBSCRIPTION_STATUS_CHANGED` | HTTP track with same `distinct_id` + `/engage` `$set` |

Signup order is required so the signup event is tied to the identified user, not an anonymous ID.

After logout the SDK assigns a new anonymous ID. The next login `identify()` should alias that session to the returning user.

---

## User profile properties (`people.set`)

| Property | Type | When set | Source |
|----------|------|----------|--------|
| `$name` | string | Login / signup / trial identify | App (`user.name`) |
| `subscription_status` | string | Login / signup / trial / webhook | App: `user.status`; trial event forces `"trial"`; webhook `"active"` or `"cancelled"` |
| `app_language` | string | Language continue | App |
| `last_ringtone_category` | string | Ringtone generated (`ringtone_created`) or set (`ringtone_set`) | App (DB category name) |
| `total_renewals` | number | Each successful recurring charge | Webhook (`renewal_number`) |
| `last_billing_month` | string | Latest recurring charge | Webhook (`YYYY-MM`) |
| `last_renewal_amount` | number | Latest recurring charge amount | Webhook |

Typical `subscription_status` values from the product: `trial`, `active`, `cancelled` (and whatever `users.status` is at login/signup).

---

## Conversion funnel

This is the business funnel Mixpanel is built to measure.

```
otp_sent
  → sign_up_completed  |  login_completed
      → subscription_screen_viewed
          → subscription_started
              → trial_payment_completed          ← mandate / trial (app, auth amount, INR)
                  → create_ringtone_cta_tapped
                      → ringtone_creation_started          ← name + language form
                          → song_picker_viewed
                              → sample_song_played
                                  → sample_song_selected
                                      → ringtone_generation_started
                                          → ringtone_created | ringtone_generation_failed
                                              → ringtone_set (personalized = true)
                                                  → subscription_paid           ← recurring autopay (webhook)
                                                      → subscription_cancelled  ← churn (webhook)
```

Failures branch off checkout and off generation:

- `subscription_started` → `subscription_failed` (`stage` = `create` | `checkout` | `verify`)
- `ringtone_generation_started` → `ringtone_generation_failed` (`failure_reason` = lower-cased `GenerationErrorCode` or `user_cancelled`)

### Trial vs recurring (do not mix these)

| Event | Who sends it | Typical amount | Meaning |
|-------|----------------|----------------|---------|
| `trial_payment_completed` | Android app after verify | Auth amount (fallback `3.0` INR) | Mandate / trial started |
| `subscription_paid` | Webhook only | Recurring charge ≥ configured recurring amount | Autopay succeeded |

Webhook **skips** Mixpanel `subscription_paid` when:

- `payment_status` is not `SUCCESS`
- `payment_type` is not `CHARGE` (auth/mandate payments are not counted as paid renewals)
- amount is below `app_config.subscription_recurring_amount` (code default `299` if unset)
- `cf_payment_id` is missing
- payment already exists in `subscription_payments`
- subscription row not found

### Suggested Mixpanel Insights funnels

1. **OTP → account:** `otp_sent` → `sign_up_completed` or `login_completed`
2. **Trial conversion:** `subscription_screen_viewed` → `subscription_started` → `trial_payment_completed`
3. **Checkout drop-off:** `subscription_started` → `subscription_failed` (break down by `stage`, `payment_app`)
4. **Activation:** `trial_payment_completed` → `ringtone_created` → `ringtone_set`
5. **Create flow:** `ringtone_creation_started` → `song_picker_viewed` → `sample_song_selected` → `ringtone_generation_started` → `ringtone_created` → `ringtone_set` (break down by `language`, `fallback_level`, `cached`)
6. **Generation health:** `ringtone_generation_started` → `ringtone_generation_failed` (break down by `failure_reason`, `retryable`, `http_status`; watch `client_ms` on `ringtone_created`)
7. **Paid retention:** `subscription_paid` where `renewal_number = 1` → `renewal_number ≥ 2`
8. **Churn:** `trial_payment_completed` or `subscription_paid` → `subscription_cancelled`

---

## Event catalog — Android (21)

Every event also receives super properties `platform` and `app_version`. Tables below list **event-specific** properties.

### Acquisition and auth

#### `otp_sent`

| | |
|--|--|
| Trigger | Phone OTP send succeeds (`PhoneAuthActivity`), or resend succeeds (`OtpVerificationActivity`) |
| Properties | `is_resend` (boolean), `platform` |

#### `sign_up_completed`

| | |
|--|--|
| Trigger | New user submits name after OTP (`SignUpNameActivity`) |
| Properties | `sign_up_method` (`"phone"`), `platform` |
| People | `$name`, `subscription_status` (via `identifyUser` first) |

#### `login_completed`

| | |
|--|--|
| Trigger | Returning user OTP verify succeeds (`OtpVerificationActivity.completeLogin()`) |
| Properties | `sign_in_method` (`"phone"`), `platform` |
| People | `$name`, `subscription_status` |

---

### Subscription (app)

#### `subscription_screen_viewed`

| | |
|--|--|
| Trigger | Paywall opens (`SubscriptionActivity`) |
| Properties | `platform` |

#### `subscription_started`

| | |
|--|--|
| Trigger | Create-subscription API succeeds, **before** Cashfree UPI checkout opens |
| Properties | `payment_app`, `auth_amount` (number, omitted if null), `recurring_amount` (number, omitted if null), `platform` |

`payment_app` slugs: `phonepe`, `google_pay`, `paytm`, `bhim`

#### `trial_payment_completed`

| | |
|--|--|
| Trigger | `verifySubscription` returns `active == true` after Cashfree `onSubscriptionVerify` |
| Properties | `payment_app`, `subscription_id`, `amount` (auth amount, default `3.0`), `currency` (`"INR"`), `platform` |
| People | `subscription_status` = `"trial"` |

This is the Mixpanel conversion event for **trial / mandate**. Meta `Purchase` and Firebase `purchase` fire in the same handler; they are not Mixpanel events.

#### `subscription_failed`

| | |
|--|--|
| Trigger | Create API fails, Cashfree checkout throws / `onSubscriptionFailure`, or verify fails / pending |
| Properties | `stage`, `failure_reason` (omitted if blank), `payment_app` (omitted if null), `platform` |

`stage` values:

| `stage` | When |
|---------|------|
| `create` | Create-subscription API failure |
| `checkout` | Cashfree session/payment error |
| `verify` | Verify API failure, or verify success with `active != true` (`failure_reason` = `"pending"`) |

---

### Ringtone activation (personalized create flow)

Shared property rules for this group:

| Property | Value |
|----------|-------|
| `language` | Storage value (`Hindi`, `English`, `Telugu`, …), never the localized label |
| `voice` | `male` or `female` from `Tune.voiceKey`; omitted when the tune has no recognised gender |
| `category` | Database category name (`Devotional`, `Romantic`, …) |
| `tune_id` | `tune.id` of the **base song** (the personalized copy keeps the same id) |
| `rank` | 1-based position of the song in the unfiltered tier list of the picker |
| `name_length` | Code-unit length of the validated name; the name itself is never sent |
| `failure_reason` | Lower-cased `GenerationErrorCode` name (`quota_exceeded`, `timeout`, `network`, …) or `user_cancelled` |

**Values before app version 1.3.0:** `ringtone_creation_started` and `ringtone_created` sent `voice`, `category`, `language` as localized form labels (for example "Female voice" or "भक्ति"). Segment by `app_version` when comparing across the change.

#### `ringtone_creation_started`

| | |
|--|--|
| Trigger | Continue on the create form after the name passes `NameNormalizer.validate` (`CreateRingtoneActivity`) |
| Properties | `language`, `name_length`, `platform` |

#### `song_picker_viewed`

| | |
|--|--|
| Trigger | Song picker reaches a terminal load state: content, empty (`song_count` = 0) or error (`ChooseSongActivity`) |
| Properties | `language`, `song_count`, `category_count`, `fallback_level` (`none` / `hindi` / `any`), `voice_filter` (`male` / `female`; omitted for "all"), `platform` |

`fallback_level` = `hindi` when the requested language had no songs and Hindi songs were shown; `any` when neither existed.

#### `sample_song_played`

| | |
|--|--|
| Trigger | Preview playback starts for a card in the picker (tap on card or play button) |
| Properties | `tune_id`, `category`, `language`, `voice`, `rank`, `platform` |

#### `sample_song_selected`

| | |
|--|--|
| Trigger | First selection of a card in the picker (re-tapping the same card does not fire again) |
| Properties | `tune_id`, `category`, `language`, `voice`, `rank`, `voice_filter` (omitted for all), `category_filter` (category id; omitted for all), `platform` |

#### `ringtone_generation_started`

| | |
|--|--|
| Trigger | A `generate-ringtone` request is posted (`RingtoneGenerationViewModel`); once per attempt, so manual retries fire again with `is_retry = true`. Automatic 409/503 back-off re-posts do **not** fire again. |
| Properties | `tune_id`, `category`, `language`, `voice`, `name_length`, `is_retry`, `platform` |

#### `ringtone_created`

| | |
|--|--|
| Trigger | `generate-ringtone` returns a ringtone URL (`Ready` state); exactly once per successful generation |
| Properties | `tune_id`, `category`, `language`, `voice`, `cached` (server render-cache hit), `duration_ms` (omitted if unknown), `client_ms` (wall time from first post to success), `generation_id`, `source` (`"creation_flow"`), `platform` |
| People | `last_ringtone_category` |

#### `ringtone_generation_failed`

| | |
|--|--|
| Trigger | Generation ends without a ringtone: server error, transport error, retry budget exhausted, or the user backs out (`failure_reason` = `user_cancelled`) |
| Properties | `tune_id`, `category`, `language`, `voice`, `failure_reason`, `http_status` (omitted for client-side failures), `retryable`, `client_ms`, `platform` |

#### `ringtone_set`

| | |
|--|--|
| Trigger | Ringtone successfully set as default (`RingtoneSetController.finishSuccess`) |
| Properties | `source`, `category`, `tune_id`, `tune_name`, `set_mode`, `generation_id` (omitted for catalog tunes), `personalized` (boolean), `platform` |
| People | `last_ringtone_category` |

`source` values: `"home"` (Home), `"creation_flow"` (ready screen)

`tune_name` for a personalized tune is the base song as authored (`title_template` with `sample_name` substituted) so the user's name never leaves the device; it is omitted when the tune has no `title_template`.

`set_mode` values (`RingtoneSetMode.analyticsValue`):

| Value | Meaning |
|-------|---------|
| `audio_only` | Audio ringtone only |
| `with_image_everyone` | Call theme image for everyone |
| `with_image_contact` | Call theme image for one contact |

---

### Engagement

#### `language_selected`

| | |
|--|--|
| Trigger | Continue on language screen (`LanguageSelectionActivity`) |
| Properties | `language`, `locale`, `context`, `platform` |
| People | `app_language` |

`context`: `"onboarding"` or `"settings"`

#### `home_viewed`

| | |
|--|--|
| Trigger | Home catalog loads successfully, **once per activity instance** (`Home.kt` `hasTrackedHomeView`) |
| Properties | `tune_count` (int), `category_count` (int), `platform` |

Fired when loading finished, no error, and categories are non-empty.

#### `tune_played`

| | |
|--|--|
| Trigger | Playback starts on Home (not pause, not empty URL) |
| Properties | `tune_id`, `category`, `source` (`"home"`), `platform` |

#### `search_performed`

| | |
|--|--|
| Trigger | Search query debounced **500 ms** (`HomeViewModel`) |
| Properties | `query_length`, `result_count`, `platform` |

Raw query text is never sent.

#### `category_filtered`

| | |
|--|--|
| Trigger | Category chip selected (not “All”, including not when toggling back to All) on Home or in the song picker |
| Properties | `category_id`, `category_name`, `source` (`"home"` / `"song_picker"`), `platform` |

#### `create_ringtone_cta_tapped`

| | |
|--|--|
| Trigger | Empty-search create CTA on Home |
| Properties | `source` (`"empty_search"`), `prefill_name_length`, `platform` |

---

## Event catalog — Cashfree webhook (2)

Sent from `cashfree-webhook` via Mixpanel HTTP API. `platform` is always `"server"`. Same `distinct_id` as the app (`users.id`).

### `subscription_paid`

| | |
|--|--|
| Cashfree type | `SUBSCRIPTION_PAYMENT_SUCCESS` |
| Conditions | `payment_status = SUCCESS`, `payment_type = CHARGE`, amount ≥ recurring config, unique `cf_payment_id` |
| Properties | `amount`, `currency` (`"INR"`), `payment_type` (`"recurring"`), `renewal_number` (1, 2, 3…), `billing_month` (`YYYY-MM`), `subscription_id`, `cf_payment_id`, `platform` (`"server"`) |
| People | `subscription_status` = `"active"`, `total_renewals`, `last_billing_month`, `last_renewal_amount` |
| Dedup | Mixpanel `$insert_id` = `cf_payment_id`; also unique row in `subscription_payments` |

`renewal_number` = count of existing `subscription_payments` rows for that user with `payment_type = recurring`, plus one.

`billing_month` comes from Cashfree `payment_schedule_date`, else webhook `event_time`.

Side effect in the same handler: Meta Conversions API `Subscribe` with `event_id` = `cf_payment_id` (not Mixpanel).

### `subscription_cancelled`

| | |
|--|--|
| Cashfree type | `SUBSCRIPTION_STATUS_CHANGED` |
| Conditions | `subscription_status` is `CUSTOMER_CANCELLED` or `CANCELLED` |
| Properties | `cancellation_status` (lowercased Cashfree status), `subscription_id`, `renewals_before_cancel` (int), `platform` (`"server"`) |
| People | `subscription_status` = `"cancelled"` |
| Dedup | `$insert_id` = `cancel_{subscription_row_id}_{STATUS}` |

`renewals_before_cancel` is the count of recurring rows in `subscription_payments` for that user at cancel time.

Webhook also sets `subscriptions.status = cancelled`, `autopay_enabled = false`, and `users.status = cancelled`.

### Webhook types that do **not** send Mixpanel

| Cashfree type | Mixpanel | What it does |
|---------------|----------|----------------|
| `SUBSCRIPTION_AUTH_STATUS` (SUCCESS) | none | Activates subscription, sets user `trial`, records auth payment |
| Other / unknown types | none | `{ received: true, ignored }` |
| Auth or non-CHARGE payment success | none | Skipped |

---

## Mixpanel HTTP helper (server)

`supabase/functions/_shared/mixpanel.ts`

| Function | Endpoint | Use |
|----------|----------|-----|
| `trackMixpanelEvent(token, distinctId, event, properties, insertId?)` | `POST https://api.mixpanel.com/track?verbose=1` | Server events |
| `setMixpanelPeople(token, distinctId, set)` | `POST https://api.mixpanel.com/engage` | `$set` profile props |
| `billingMonthFromDate(iso)` | — | `YYYY-MM` for `billing_month` |

Track payload always includes `token`, `distinct_id`, `time` (unix seconds), `platform: "server"`, plus event properties. Optional `$insert_id` prevents duplicate counts on webhook retries.

---

## Mixpanel vs Meta vs Firebase

Mixpanel is the product analytics source of truth. Ads conversions use other tools. Do not import Mixpanel into Google Ads.

| Mixpanel event | Meta | Firebase / Google Ads |
|----------------|------|------------------------|
| `sign_up_completed` | `CompleteRegistration` | — |
| `login_completed` | identify only | identify only |
| `subscription_screen_viewed` | `ViewContent` | — |
| `subscription_started` | `InitiatedCheckout` | — |
| `trial_payment_completed` | `Purchase` | `purchase` (Ads conversion) |
| `subscription_paid` | `Subscribe` (Conversions API, webhook) | — |
| `subscription_cancelled` | — | — |
| `subscription_failed` | — | — |
| Ringtone / engagement events | — | — |

---

## Data we do not send to Mixpanel

- Phone numbers
- Email addresses as `distinct_id`
- OTP values
- Raw search query text (only `query_length` and `result_count`)
- The typed ringtone name or the generated title (only `name_length`; personalized `tune_name` falls back to the authored `title_template`)
- Empty or null properties (omit instead)

Consent is not gated yet. If EU/California users are added, initialize the SDK only after consent.

---

## Naming conventions

- Event names: `snake_case`, past tense (`ringtone_created`, not `create_ringtone`)
- Property names: `snake_case`
- Enum-like values: lowercase (`phonepe`, `audio_only`)
- Currency: `"INR"`
- User ID: database id string only

---

## Implementation map

| Component | Path |
|-----------|------|
| Init | `app/src/main/java/com/spacewire/meratune/MeraTuneApplication.kt` |
| App helper | `app/src/main/java/com/spacewire/meratune/analytics/MixpanelAnalytics.kt` |
| Token | `app/build.gradle.kts` → `BuildConfig.MIXPANEL_TOKEN` |
| OTP send | `PhoneAuthActivity.kt` |
| OTP resend / login | `OtpVerificationActivity.kt` |
| Signup | `SignUpNameActivity.kt` |
| Language | `LanguageSelectionActivity.kt` |
| Paywall / trial / failures / logout | `SubscriptionActivity.kt` |
| Create form | `CreateRingtoneActivity.kt` |
| Song picker | `ChooseSongActivity.kt` (`data/SongRanker.kt` for `rank` / `fallback_level`) |
| Generation | `RingtoneProcessingActivity.kt`, `ui/RingtoneGenerationViewModel.kt`, `data/RingtoneGenerationRepository.kt` (`GenerationErrorCode` → `failure_reason`) |
| Ready screen | `RingtoneReadyActivity.kt` |
| Set ringtone | `calltheme/RingtoneSetController.kt`, `calltheme/RingtoneSetMode.kt` |
| Home / play / empty CTA | `Home.kt` |
| Search / category | `ui/HomeViewModel.kt` |
| Profile logout | `ProfileActivity.kt` |
| Webhook | `supabase/functions/cashfree-webhook/index.ts` |
| Mixpanel HTTP | `supabase/functions/_shared/mixpanel.ts` |
| Payment dedup table | `subscription_payments` (`20260728160000_add_subscription_payments.sql`) |

---

## Event count

| Category | Count |
|----------|-------|
| Auth / acquisition | 3 (`otp_sent`, `sign_up_completed`, `login_completed`) |
| Subscription (app) | 4 (`subscription_screen_viewed`, `subscription_started`, `trial_payment_completed`, `subscription_failed`) |
| Subscription (server) | 2 (`subscription_paid`, `subscription_cancelled`) |
| Ringtone activation | 8 (`ringtone_creation_started`, `song_picker_viewed`, `sample_song_played`, `sample_song_selected`, `ringtone_generation_started`, `ringtone_created`, `ringtone_generation_failed`, `ringtone_set`) |
| Engagement | 6 (`language_selected`, `home_viewed`, `tune_played`, `search_performed`, `category_filtered`, `create_ringtone_cta_tapped`) |
| **Total** | **23** |

---

## Verification

1. **Debug app:** trigger each flow; confirm events in Mixpanel Live View and Logcat.
2. **Identity:** signup/login events share the same `distinct_id` as later subscription and webhook events for that user.
3. **Logout:** next events use a new anonymous ID until login.
4. **Trial vs paid:** `trial_payment_completed` only from the app; `subscription_paid` only from the webhook.
5. **Webhook retries:** same `cf_payment_id` must not increment Mixpanel `subscription_paid` twice (`$insert_id` + DB unique payment).
6. **Lexicon:** add descriptions for all 23 events in Mixpanel Data Management.
7. **Funnels:** build the Insights funnels listed in [Conversion funnel](#conversion-funnel).
8. **Create flow:** run one generation end to end and confirm Live View shows `create_ringtone_cta_tapped → ringtone_creation_started → song_picker_viewed → sample_song_played → sample_song_selected → ringtone_generation_started → ringtone_created (cached=false) → ringtone_set (generation_id, personalized=true)` and that no property contains the typed name.
