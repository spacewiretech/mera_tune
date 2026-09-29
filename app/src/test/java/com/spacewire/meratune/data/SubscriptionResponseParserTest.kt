package com.spacewire.meratune.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SubscriptionResponseParserTest {

    private val trialCreate = """
        {
          "subscription_id": "mt_907759_1727600000000",
          "subscription_session_id": "sub_session_abc",
          "cf_subscription_id": "589140",
          "environment": "production",
          "auth_amount": 3,
          "recurring_amount": 299,
          "offer": "trial",
          "first_charge_at": "2026-09-30T10:00:00+05:30"
        }
    """.trimIndent()

    private val paidCreate = """
        {
          "subscription_id": "mt_907759_1727600000001",
          "subscription_session_id": "sub_session_def",
          "cf_subscription_id": "589141",
          "environment": "production",
          "auth_amount": 299,
          "recurring_amount": 299,
          "offer": "paid",
          "first_charge_at": "2026-10-29T10:00:00+05:30"
        }
    """.trimIndent()

    /** create-subscription before the paid offer: no `offer`, always a ₹3 trial. */
    private val legacyCreate = """
        {
          "subscription_id": "mt_1_1", "subscription_session_id": "s1", "cf_subscription_id": "1",
          "environment": "sandbox", "auth_amount": 3, "recurring_amount": 299
        }
    """.trimIndent()

    private fun expectApiError(block: () -> Unit): SubscriptionApiException {
        try {
            block()
        } catch (error: SubscriptionApiException) {
            return error
        }
        fail("expected SubscriptionApiException")
        throw AssertionError()
    }

    @Test
    fun requestAlwaysSaysThePaidOfferIsSupported() {
        val json = SubscriptionResponseParser.json
        assertEquals(
            """{"user_id":42,"paid_offer_supported":true}""",
            json.encodeToString(CreateSubscriptionRequest.serializer(), CreateSubscriptionRequest(42, paidOfferSupported = true)),
        )
        assertEquals(
            """{"user_id":42,"paid_offer_supported":true,"preview":true}""",
            json.encodeToString(
                CreateSubscriptionRequest.serializer(),
                CreateSubscriptionRequest(42, paidOfferSupported = true, preview = true),
            ),
        )
    }

    @Test
    fun trialCreate() {
        val created = SubscriptionResponseParser.parseCreate(200, trialCreate)
        assertEquals(SubscriptionOfferType.TRIAL, created.offer)
        assertEquals("mt_907759_1727600000000", created.subscriptionId)
        assertEquals("sub_session_abc", created.sessionId)
        assertEquals("589140", created.cfSubscriptionId)
        assertEquals("production", created.environment)
        assertEquals(3.0, created.authAmount, 0.0)
        assertEquals(299.0, created.recurringAmount, 0.0)
        assertEquals("2026-09-30T10:00:00+05:30", created.firstChargeAt)
    }

    @Test
    fun paidCreateChargesTheFirstMonthAtTheMandate() {
        val created = SubscriptionResponseParser.parseCreate(200, paidCreate)
        assertEquals(SubscriptionOfferType.PAID, created.offer)
        assertEquals(299.0, created.authAmount, 0.0)
        assertEquals(299.0, created.recurringAmount, 0.0)
        assertEquals("2026-10-29T10:00:00+05:30", created.firstChargeAt)
    }

    @Test
    fun createWithoutOfferIsALegacyTrial() {
        val created = SubscriptionResponseParser.parseCreate(200, legacyCreate)
        assertEquals(SubscriptionOfferType.TRIAL, created.offer)
        assertEquals(3.0, created.authAmount, 0.0)
        assertNull(created.firstChargeAt)
    }

    @Test
    fun offerIsCaseInsensitive() {
        val created = SubscriptionResponseParser.parseCreate(200, paidCreate.replace("\"paid\"", "\" PAID \""))
        assertEquals(SubscriptionOfferType.PAID, created.offer)
    }

    @Test
    fun unusableCreateAnswersNeverReachTheCheckout() {
        val bodies = listOf(
            paidCreate.replace("\"paid\"", "\"promo\""),
            paidCreate.replace("\"subscription_session_id\": \"sub_session_def\",", ""),
            paidCreate.replace("\"subscription_id\": \"mt_907759_1727600000001\",", "\"subscription_id\": \" \","),
            paidCreate.replace("\"auth_amount\": 299,", ""),
            paidCreate.replace("\"auth_amount\": 299", "\"auth_amount\": 0"),
            paidCreate.replace("\"recurring_amount\": 299,", "\"recurring_amount\": -1,"),
            "<html>502 Bad Gateway</html>",
            "",
            "[]",
        )
        for (body in bodies) {
            val error = expectApiError { SubscriptionResponseParser.parseCreate(200, body) }
            assertTrue(body, error.invalidResponse)
            assertEquals(body, SubscriptionFailureReason.INVALID_RESPONSE, SubscriptionFailureReason.forCreate(error))
            assertFalse(error.isAlreadyActive)
        }
    }

    @Test
    fun alreadyActiveIsDistinguishable() {
        val bodies = listOf(
            """{"error":"Subscription already active","error_code":"ALREADY_ACTIVE"}""",
            // Servers from before the paid offer send no error_code.
            """{"error":"Subscription already active"}""",
        )
        for (body in bodies) {
            for (parse in listOf(SubscriptionResponseParser::parseCreate, SubscriptionResponseParser::parseOffer)) {
                val error = expectApiError { parse(409, body) }
                assertTrue(error.isAlreadyActive)
                assertFalse(error.isAppUpdateRequired)
                assertFalse(error.invalidResponse)
                assertEquals(409, error.httpStatus)
                assertEquals("Subscription already active", error.message)
                assertEquals(SubscriptionFailureReason.ALREADY_ACTIVE, SubscriptionFailureReason.forCreate(error))
            }
        }
    }

    @Test
    fun appUpdateRequiredKeepsTheServerText() {
        val body = """{"error":"Naya plan dekhne ke liye MeraTune app update karein.","error_code":"APP_UPDATE_REQUIRED"}"""
        for (parse in listOf(SubscriptionResponseParser::parseCreate, SubscriptionResponseParser::parseOffer)) {
            val error = expectApiError { parse(426, body) }
            assertTrue(error.isAppUpdateRequired)
            assertFalse(error.isAlreadyActive)
            assertEquals("Naya plan dekhne ke liye MeraTune app update karein.", error.message)
            assertEquals("APP_UPDATE_REQUIRED", error.errorCode)
            assertEquals(SubscriptionFailureReason.APP_UPDATE_REQUIRED, SubscriptionFailureReason.forCreate(error))
        }
    }

    @Test
    fun otherErrorsMapByStatus() {
        val gateway = expectApiError {
            SubscriptionResponseParser.parseCreate(502, """{"error":"customer_phone is invalid"}""")
        }
        assertEquals(SubscriptionFailureReason.GATEWAY_ERROR, SubscriptionFailureReason.forCreate(gateway))
        assertEquals("customer_phone is invalid", gateway.message)

        val server = expectApiError { SubscriptionResponseParser.parseCreate(500, """{"error":"Internal server error"}""") }
        assertEquals(SubscriptionFailureReason.SERVER_ERROR, SubscriptionFailureReason.forCreate(server))

        // A non-2xx JSON body without error text: no message (the app shows its own), mapped by status.
        val bare = expectApiError { SubscriptionResponseParser.parseCreate(503, "{}") }
        assertFalse(bare.invalidResponse)
        assertNull(bare.message)
        assertEquals(SubscriptionFailureReason.GATEWAY_NOT_CONFIGURED, SubscriptionFailureReason.forCreate(bare))

        // A success status that still carries an error is a failure.
        val errorOn200 = expectApiError { SubscriptionResponseParser.parseCreate(200, """{"error":"x"}""") }
        assertFalse(errorOn200.invalidResponse)
    }

    @Test
    fun trialPreview() {
        val offer = SubscriptionResponseParser.parseOffer(
            200,
            """{"offer":"trial","trial_eligible":true,"auth_amount":3,"recurring_amount":299,"interval_months":1,"trial_days":1}""",
        )
        assertEquals(
            SubscriptionOffer(
                offer = SubscriptionOfferType.TRIAL,
                trialEligible = true,
                authAmount = 3.0,
                recurringAmount = 299.0,
                intervalMonths = 1,
                trialDays = 1,
            ),
            offer,
        )
    }

    @Test
    fun paidPreview() {
        val offer = SubscriptionResponseParser.parseOffer(
            200,
            """{"offer":"paid","trial_eligible":false,"auth_amount":299,"recurring_amount":299,"interval_months":1,"trial_days":1}""",
        )
        assertEquals(SubscriptionOfferType.PAID, offer.offer)
        assertFalse(offer.trialEligible)
        assertEquals(299.0, offer.authAmount, 0.0)
        assertEquals(299.0, offer.recurringAmount, 0.0)
    }

    @Test
    fun previewDefaultsForOptionalFields() {
        val offer = SubscriptionResponseParser.parseOffer(200, """{"offer":"paid","auth_amount":299,"recurring_amount":299}""")
        assertFalse(offer.trialEligible)
        assertEquals(1, offer.intervalMonths)
        assertEquals(1, offer.trialDays)
        val trial = SubscriptionResponseParser.parseOffer(200, """{"offer":"trial","auth_amount":3,"recurring_amount":299}""")
        assertTrue(trial.trialEligible)
    }

    @Test
    fun previewNeedsAKnownOfferAndAmounts() {
        val bodies = listOf(
            // A server from before the preview ignores it and answers with a created mandate.
            legacyCreate,
            """{"offer":"promo","auth_amount":3,"recurring_amount":299}""",
            """{"offer":"trial","recurring_amount":299}""",
            """{"offer":"paid","auth_amount":299,"recurring_amount":0}""",
            "not json",
        )
        for (body in bodies) {
            val error = expectApiError { SubscriptionResponseParser.parseOffer(200, body) }
            assertTrue(body, error.invalidResponse)
        }
    }
}
