package com.spacewire.meratune.data

import com.spacewire.meratune.util.TuneStatsUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NameRingtonesParserTest {

    private fun row(
        tuneId: String = "t1",
        title: String? = "Jai Shri Ram Ayush ji..",
        url: String? = "https://cdn.example/generated/t1/r1.mp3",
        generationId: String? = null,
        tuneExtra: String = "",
    ): String {
        val titleJson = title?.let { "\"$it\"" } ?: "null"
        val urlJson = url?.let { "\"$it\"" } ?: "null"
        val generationJson = generationId?.let { "\"$it\"" } ?: "null"
        return """
            {
              "tune": {
                "id": "$tuneId", "name": "Jai Shri Shyam", "category_id": "c1", "gender": "Male",
                "language": "Hindi", "tune_url": "https://cdn.example/stock/$tuneId.mp3", "is_active": true,
                "is_personalizable": true, "featured_rank": null, "sample_name": "Shyam",
                "title_template": "Jai Shri Ram {name} ji..", "assets_version": 2,
                "likes_count": 1, "views_count": 2,
                "category": { "id": "c1", "name": "Bhakti", "image_url": "" }$tuneExtra
              },
              "title": $titleJson,
              "ringtone_url": $urlJson,
              "render_id": "r1",
              "generation_id": $generationJson,
              "voice": "male",
              "language": "Hindi",
              "source": "render",
              "created_at": "2026-09-20T10:00:00+00:00"
            }
        """.trimIndent()
    }

    private fun body(vararg rows: String, mode: String = "name") =
        """{ "mode": "$mode", "ringtones": [${rows.joinToString(",")}] }"""

    private fun expectFailure(status: Int, body: String): RingtoneGenerationException {
        try {
            NameRingtonesParser.parse(status, body)
        } catch (error: RingtoneGenerationException) {
            return error
        }
        fail("expected RingtoneGenerationException")
        throw AssertionError()
    }

    @Test
    fun rowBecomesPersonalizedCopyOfBaseTune() {
        val tunes = NameRingtonesParser.parse(200, body(row(generationId = "g1")))
        assertEquals(1, tunes.size)
        val tune = tunes.single()
        assertEquals("t1", tune.id)
        assertEquals("Jai Shri Ram Ayush ji..", tune.name)
        assertEquals("https://cdn.example/generated/t1/r1.mp3", tune.tuneUrl)
        assertEquals("g1", tune.generationId)
        assertEquals("c1", tune.categoryId)
        assertEquals("Bhakti", tune.category?.name)
        assertEquals(Tune.VOICE_MALE, tune.voiceKey)
        assertEquals("Hindi", tune.language)
        assertEquals("Shyam", tune.sampleName)
        assertEquals("Jai Shri Ram {name} ji..", tune.titleTemplate)
        assertTrue(tune.isPersonalizable)
        assertEquals(2, tune.assetsVersion)
    }

    @Test
    fun statsMatchHomeForTheSameBaseTune() {
        val tune = NameRingtonesParser.parse(200, body(row())).single()
        val home = TuneStatsUtils.withRandomStats(listOf(tune.copy(likesCount = 0, viewsCount = 0))).single()
        assertEquals(home.likesCount, tune.likesCount)
        assertEquals(home.viewsCount, tune.viewsCount)
    }

    @Test
    fun ringtoneNotMadeByCallerHasNoGenerationId() {
        assertNull(NameRingtonesParser.parse(200, body(row(generationId = null))).single().generationId)
        assertNull(NameRingtonesParser.parse(200, body(row(generationId = " "))).single().generationId)
    }

    @Test
    fun blankTitleFallsBackToBaseTuneName() {
        assertEquals("Jai Shri Shyam", NameRingtonesParser.parse(200, body(row(title = null))).single().name)
        assertEquals("Jai Shri Shyam", NameRingtonesParser.parse(200, body(row(title = "  "))).single().name)
    }

    @Test
    fun malformedRowsAreDroppedNotFatal() {
        val tunes = NameRingtonesParser.parse(
            200,
            body(
                row(tuneId = "ok"),
                row(tuneId = "no-url", url = null),
                row(tuneId = "http", url = "http://cdn.example/x.mp3"),
                """{ "title": "no tune", "ringtone_url": "https://x/y.mp3" }""",
                """{ "tune": { "id": "t2", "name": null }, "ringtone_url": "https://x/y.mp3" }""",
                "42",
            ),
        )
        assertEquals(listOf("ok"), tunes.map { it.id })
    }

    @Test
    fun unknownFieldsAndNullDefaultsAreTolerated() {
        val tune = NameRingtonesParser.parse(
            200,
            body(row(tuneExtra = """, "is_active": null, "future_column": 1""")),
        ).single()
        assertTrue(tune.isActive)
    }

    @Test
    fun emptyListIsNotAnError() {
        assertTrue(NameRingtonesParser.parse(200, """{ "mode": "mine", "ringtones": [] }""").isEmpty())
    }

    @Test
    fun mineResponseCarriesThePlanQuota() {
        val result = NameRingtonesParser.parseResult(
            200,
            """
                { "mode": "mine", "ringtones": [${row(generationId = "g1")}],
                  "quota": { "used_today": 2, "daily_limit": 2, "plan": "trial", "period": "day",
                             "used": 2, "limit": 2, "resets_at": "2026-09-28T18:30:00.000Z",
                             "member_monthly_limit": 50, "future_field": true } }
            """.trimIndent(),
        )
        assertEquals(listOf("g1"), result.ringtones.map { it.generationId })
        val quota = result.quota!!
        assertEquals(GenerationQuota.PLAN_TRIAL, quota.plan)
        assertEquals(GenerationQuota.PERIOD_DAY, quota.period)
        assertEquals(2, quota.usedCount)
        assertEquals(2, quota.limitCount)
        assertEquals("2026-09-28T18:30:00.000Z", quota.resetsAt)
        assertEquals(50, quota.memberMonthlyLimit)
        assertNull(quota.exceeded)
        assertFalse(quota.isMonthly)

        val member = NameRingtonesParser.parseResult(
            200,
            """
                { "mode": "mine", "ringtones": [],
                  "quota": { "used_today": 12, "daily_limit": 50, "plan": "member", "period": "month",
                             "used": 12, "limit": 50, "resets_at": "2026-09-30T18:30:00.000Z",
                             "member_monthly_limit": 50 } }
            """.trimIndent(),
        ).quota!!
        assertTrue(member.isMonthly)
        assertEquals(12, member.usedCount)
        assertEquals(50, member.limitCount)
    }

    @Test
    fun mineResponseWithOnlyLegacyQuotaFields() {
        val quota = NameRingtonesParser.parseResult(
            200,
            """{ "mode": "mine", "ringtones": [], "quota": { "used_today": 3, "daily_limit": 5 } }""",
        ).quota!!
        assertEquals(3, quota.usedCount)
        assertEquals(5, quota.limitCount)
        assertNull(quota.plan)
        assertNull(quota.period)
        assertNull(quota.resetsAt)
        assertNull(quota.memberMonthlyLimit)
        assertFalse(quota.isMonthly)
    }

    @Test
    fun mineResponseWithoutQuota() {
        val result = NameRingtonesParser.parseResult(200, body(row(generationId = "g1"), mode = "mine"))
        assertEquals(1, result.ringtones.size)
        assertNull(result.quota)
        // A null quota (count query failed server side) is the same.
        assertNull(NameRingtonesParser.parseResult(200, """{ "mode": "mine", "ringtones": [], "quota": null }""").quota)
    }

    @Test
    fun serverErrorCarriesItsCode() {
        val error = expectFailure(400, """{ "error": "This name cannot be used", "error_code": "NAME_REJECTED" }""")
        assertEquals(GenerationErrorCode.NAME_REJECTED, error.code)
        assertEquals("This name cannot be used", error.message)
        assertEquals(400, error.httpStatus)
        assertTrue(error.fromServer)
        assertFalse(error.retryable)

        val unauthorized = expectFailure(401, """{ "error": "Session expired", "error_code": "UNAUTHORIZED" }""")
        assertEquals(GenerationErrorCode.UNAUTHORIZED, unauthorized.code)
    }

    @Test
    fun unreadableOrUnexpectedResponses() {
        assertEquals(GenerationErrorCode.INVALID_RESPONSE, expectFailure(502, "<html>bad gateway</html>").code)
        assertEquals(GenerationErrorCode.UNAUTHORIZED, expectFailure(401, "Invalid JWT").code)
        assertEquals(GenerationErrorCode.TIMEOUT, expectFailure(504, "").code)
        assertEquals(GenerationErrorCode.INVALID_RESPONSE, expectFailure(200, """{ "mode": "name" }""").code)
        assertEquals(GenerationErrorCode.UNAUTHORIZED, expectFailure(403, "{}").code)
        assertEquals(GenerationErrorCode.INVALID_RESPONSE, expectFailure(500, "{}").code)
        assertFalse(expectFailure(500, "{}").fromServer)
    }
}
