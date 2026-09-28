package com.spacewire.meratune.ui

import com.spacewire.meratune.analytics.CreationLimitType
import com.spacewire.meratune.data.GenerationQuota
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure creation-limit rules for Home's create CTAs, the limit sheet and the processing copy (JVM
 * unit-testable, no `android.*`). An unknown or unreadable quota never blocks: the server decides.
 */
object CreationLimitPolicy {

    /** Which limit copy the sheet shows. */
    enum class Variant { TRIAL, MEMBER, DAILY }

    /** Plan periods end at IST midnight (the 1st of the month for members). */
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    /**
     * The period's fresh renders are used up ([GenerationQuota.usedCount] >= a positive
     * [GenerationQuota.limitCount]) and the period ends after [nowMs]. A missing count, a
     * missing or unparseable `resets_at`, or a reset already passed is not exhausted.
     */
    fun isExhausted(quota: GenerationQuota?, nowMs: Long): Boolean {
        if (quota == null) return false
        val used = quota.usedCount ?: return false
        val limit = quota.limitCount ?: return false
        if (limit <= 0 || used < limit) return false
        val resetsAtMs = resetsAtMs(quota) ?: return false
        return resetsAtMs > nowMs
    }

    /** [Variant.TRIAL] for the trial plan, [Variant.MEMBER] for the member (monthly) plan, else [Variant.DAILY]. */
    fun variant(quota: GenerationQuota?): Variant = when {
        plan(quota) == GenerationQuota.PLAN_TRIAL -> Variant.TRIAL
        plan(quota) == GenerationQuota.PLAN_MEMBER || quota?.isMonthly == true -> Variant.MEMBER
        else -> Variant.DAILY
    }

    /**
     * `creation_limit_reached.limit_type`: [CreationLimitType.MONTHLY] for a monthly period, unless
     * the per-day attempt cap was hit ([GenerationQuota.EXCEEDED_ATTEMPTS]); else [CreationLimitType.DAILY].
     */
    fun limitType(quota: GenerationQuota?): String =
        if (quota?.isMonthly == true && quota.exceeded != GenerationQuota.EXCEEDED_ATTEMPTS) {
            CreationLimitType.MONTHLY
        } else {
            CreationLimitType.DAILY
        }

    /** Epoch millis of `resets_at`; `null` when missing or not an ISO-8601 instant. */
    fun resetsAtMs(quota: GenerationQuota?): Long? {
        val raw = quota?.resetsAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    }

    /** `resets_at` as an IST calendar day ("1 October" in [locale]); `null` when it does not parse. */
    fun formatResetDate(quota: GenerationQuota?, locale: Locale): String? {
        val resetsAtMs = resetsAtMs(quota) ?: return null
        return DateTimeFormatter.ofPattern("d MMMM", locale).withZone(IST).format(Instant.ofEpochMilli(resetsAtMs))
    }

    private fun plan(quota: GenerationQuota?): String? = quota?.plan?.trim()?.lowercase(Locale.ROOT)
}
