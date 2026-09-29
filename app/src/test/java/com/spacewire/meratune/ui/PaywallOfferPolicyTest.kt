package com.spacewire.meratune.ui

import com.spacewire.meratune.R
import com.spacewire.meratune.data.GenerationQuota
import com.spacewire.meratune.data.SubscriptionOfferType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class PaywallOfferPolicyTest {

    private val trial = PaywallOfferPolicy.defaults(SubscriptionOfferType.TRIAL)
    private val paid = PaywallOfferPolicy.defaults(SubscriptionOfferType.PAID)

    @Test
    fun newUsersSeeTheTrialFirst() {
        listOf("none", "", " ", null, "unknown", "pending").forEach {
            assertEquals("status=$it", SubscriptionOfferType.TRIAL, PaywallOfferPolicy.localGuess(it))
        }
    }

    @Test
    fun returningUsersSeeThePaidPlanFirst() {
        // Every one of these statuses needed an authorised mandate, so the server never offers a trial again.
        listOf("cancelled", "expired", "CANCELLED", " expired ", "trial", "active").forEach {
            assertEquals("status=$it", SubscriptionOfferType.PAID, PaywallOfferPolicy.localGuess(it))
        }
    }

    @Test
    fun defaultAmounts() {
        assertTrue(trial.isTrial)
        assertEquals(3.0, trial.authAmount, 0.0)
        assertEquals(299.0, trial.recurringAmount, 0.0)
        assertEquals(3.0, trial.heroAmount, 0.0)

        // The paid plan charges the first month at the mandate: no ₹3 anywhere.
        assertFalse(paid.isTrial)
        assertEquals(299.0, paid.authAmount, 0.0)
        assertEquals(299.0, paid.recurringAmount, 0.0)
        assertEquals(299.0, paid.heroAmount, 0.0)
    }

    @Test
    fun heroAmountFollowsTheOffer() {
        assertEquals(5.0, PaywallPricing(SubscriptionOfferType.TRIAL, 5.0, 349.0).heroAmount, 0.0)
        assertEquals(349.0, PaywallPricing(SubscriptionOfferType.PAID, 299.0, 349.0).heroAmount, 0.0)
    }

    @Test
    fun sameOfferAndPriceOpensTheCheckout() {
        assertFalse(PaywallOfferPolicy.mustStopCheckout(trial, trial))
        assertFalse(PaywallOfferPolicy.mustStopCheckout(paid, paid))
        assertFalse(PaywallOfferPolicy.mustStopCheckout(paid, PaywallPricing(SubscriptionOfferType.PAID, 299.0000001, 299.0)))
    }

    @Test
    fun anotherOfferThanShownStopsTheCheckout() {
        // The reported bug: a cancelled user saw (or would have paid) the ₹3 trial again.
        assertTrue(PaywallOfferPolicy.mustStopCheckout(trial, paid))
        assertTrue(PaywallOfferPolicy.mustStopCheckout(paid, trial))
    }

    @Test
    fun anotherPriceThanShownStopsTheCheckout() {
        assertTrue(PaywallOfferPolicy.mustStopCheckout(trial, PaywallPricing(SubscriptionOfferType.TRIAL, 5.0, 299.0)))
        assertTrue(PaywallOfferPolicy.mustStopCheckout(trial, PaywallPricing(SubscriptionOfferType.TRIAL, 3.0, 349.0)))
        assertTrue(PaywallOfferPolicy.mustStopCheckout(paid, PaywallPricing(SubscriptionOfferType.PAID, 349.0, 349.0)))
    }

    @Test
    fun trialCopyIsUnchanged() {
        assertEquals(
            PaywallCopy(
                title = R.string.paywall_title,
                renewal = R.string.paywall_then_price,
                faqQuestion2 = R.string.paywall_faq_q2,
                faqAnswer2 = R.string.paywall_faq_a2,
                faqAnswer4 = R.string.paywall_faq_a4,
                memberSubtitle = R.string.member_subtitle,
            ),
            PaywallOfferPolicy.copy(SubscriptionOfferType.TRIAL),
        )
    }

    @Test
    fun paidCopyHasItsOwnStrings() {
        assertEquals(
            PaywallCopy(
                title = R.string.paywall_title_paid,
                renewal = R.string.paywall_renewal_paid,
                faqQuestion2 = R.string.paywall_faq_q2_paid,
                faqAnswer2 = R.string.paywall_faq_a2_paid,
                faqAnswer4 = R.string.paywall_faq_a4_paid,
                memberSubtitle = R.string.member_subtitle_paid,
            ),
            PaywallOfferPolicy.copy(SubscriptionOfferType.PAID),
        )
    }

    @Test
    fun paidCopyNeverMentionsTheTrial() {
        val strings = defaultStrings()
        val trialWording = Regex("trial|1-day|1 day|1 din|agle din|₹3\\b", RegexOption.IGNORE_CASE)
        for (name in paidStringNames + "paywall_faq_q4") {
            val text = strings[name] ?: error("values/strings.xml has no $name")
            assertFalse("$name mentions the trial: $text", trialWording.containsMatchIn(text))
        }
    }

    @Test
    fun paidAnswersFormatTodayAndMonthlyAmounts() {
        val strings = defaultStrings()
        listOf("paywall_faq_a2_paid", "paywall_faq_a4_paid").forEach { name ->
            val text = strings.getValue(name)
            assertTrue(name, text.contains("%1\$s") && text.contains("%2\$s"))
        }
        listOf("paywall_faq_q2_paid", "member_subtitle_paid").forEach { name ->
            assertTrue(name, strings.getValue(name).contains("%1\$s"))
        }
        // Formatted with the recurring amount like the trial line; the paid line shows none.
        assertFalse(strings.getValue("paywall_renewal_paid").contains("%"))
    }

    @Test
    fun alreadyMemberStatusFromTheServerPlan() {
        assertEquals("trial", PaywallOfferPolicy.statusForPlan(GenerationQuota.PLAN_TRIAL))
        assertEquals("active", PaywallOfferPolicy.statusForPlan(GenerationQuota.PLAN_MEMBER))
        assertEquals("active", PaywallOfferPolicy.statusForPlan(" MEMBER "))
        listOf(GenerationQuota.PLAN_DEFAULT, null, "", "gold").forEach {
            assertNull("plan=$it", PaywallOfferPolicy.statusForPlan(it))
        }
    }

    @Test
    fun amountLabels() {
        assertEquals("3", PaywallOfferPolicy.amountLabel(3.0))
        assertEquals("299", PaywallOfferPolicy.amountLabel(299.0))
        assertEquals("2.5", PaywallOfferPolicy.amountLabel(2.5))
    }

    private val paidStringNames = listOf(
        "paywall_title_paid",
        "paywall_renewal_paid",
        "paywall_faq_q2_paid",
        "paywall_faq_a2_paid",
        "paywall_faq_a4_paid",
        "member_subtitle_paid",
    )

    private fun defaultStrings(): Map<String, String> {
        val resDir = listOf(
            File("src/main/res"),
            File("app/src/main/res"),
            File(System.getProperty("user.dir"), "src/main/res"),
            File(System.getProperty("user.dir"), "app/src/main/res"),
        ).firstOrNull { File(it, "values/strings.xml").isFile }
            ?: error("Could not locate app/src/main/res from ${System.getProperty("user.dir")}")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(resDir, "values/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
    }
}
