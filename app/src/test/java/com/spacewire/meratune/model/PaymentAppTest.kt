package com.spacewire.meratune.model

import com.spacewire.meratune.R
import com.spacewire.meratune.analytics.PaymentAppSlug
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentAppTest {

    private fun installed(vararg apps: PaymentApp): List<PaymentApp> {
        val packages = apps.mapNotNull { it.packageName }.toSet()
        return PaymentApp.installedFrom { it in packages }
    }

    @Test
    fun `installed lists only UPI apps, in sheet order, never UPI ID`() {
        assertEquals(listOf(PaymentApp.GOOGLE_PAY, PaymentApp.BHIM), installed(PaymentApp.BHIM, PaymentApp.GOOGLE_PAY))
        assertEquals(PaymentApp.UPI_APPS, PaymentApp.installedFrom { true })
        assertFalse(PaymentApp.UPI_ID in PaymentApp.installedFrom { true })
        assertTrue(PaymentApp.installedFrom { false }.isEmpty())
    }

    @Test
    fun `default selection is the first installed app`() {
        assertEquals(PaymentApp.PHONEPE, PaymentApp.defaultSelection(installed(PaymentApp.PAYTM, PaymentApp.PHONEPE)))
        assertEquals(PaymentApp.PAYTM, PaymentApp.defaultSelection(installed(PaymentApp.PAYTM)))
    }

    @Test
    fun `no installed app selects UPI ID, never PhonePe`() {
        assertEquals(PaymentApp.UPI_ID, PaymentApp.defaultSelection(emptyList()))
        assertEquals(PaymentApp.UPI_ID, PaymentApp.defaultSelection(installed()))
    }

    @Test
    fun `sheet shows the installed apps then UPI ID last`() {
        assertEquals(
            listOf(PaymentApp.PHONEPE, PaymentApp.GOOGLE_PAY, PaymentApp.UPI_ID),
            PaymentApp.sheetOptions(installed(PaymentApp.PHONEPE, PaymentApp.GOOGLE_PAY)),
        )
        assertEquals(listOf(PaymentApp.UPI_ID), PaymentApp.sheetOptions(emptyList()))
        // A stray UPI ID in the input is not duplicated.
        assertEquals(listOf(PaymentApp.UPI_ID), PaymentApp.sheetOptions(listOf(PaymentApp.UPI_ID)))
    }

    @Test
    fun `UPI ID row label depends on whether apps are listed above it`() {
        assertEquals(R.string.paywall_upi_id_other, PaymentApp.upiIdSheetLabelRes(installed(PaymentApp.BHIM)))
        assertEquals(R.string.paywall_upi_id_pay, PaymentApp.upiIdSheetLabelRes(emptyList()))
        assertEquals(R.string.paywall_upi_id_pay, PaymentApp.upiIdSheetLabelRes(listOf(PaymentApp.UPI_ID)))
    }

    @Test
    fun `sheet title says no UPI app is installed above a lone UPI ID row`() {
        assertEquals(
            R.string.subscription_select_payment_app,
            PaymentApp.sheetTitleRes(PaymentApp.sheetOptions(installed(PaymentApp.PAYTM))),
        )
        assertEquals(
            R.string.subscription_no_payment_app_installed,
            PaymentApp.sheetTitleRes(PaymentApp.sheetOptions(emptyList())),
        )
    }

    @Test
    fun `UPI ID has no package and a localized label, the apps keep their brand names`() {
        assertNull(PaymentApp.UPI_ID.packageName)
        assertFalse(PaymentApp.UPI_ID.isUpiApp)
        assertEquals(R.string.paywall_upi_id, PaymentApp.UPI_ID.labelRes)
        assertEquals(R.drawable.ic_upi_id, PaymentApp.UPI_ID.logoRes)
        PaymentApp.UPI_APPS.forEach {
            assertTrue(it.isUpiApp)
            assertNull(it.labelRes)
        }
    }

    @Test
    fun `analytics slugs are bounded and UPI ID is upi_id`() {
        assertEquals("upi_id", PaymentAppSlug.of(PaymentApp.UPI_ID))
        assertEquals(
            listOf("phonepe", "google_pay", "paytm", "bhim", "upi_id"),
            PaymentApp.entries.map(PaymentAppSlug::of),
        )
    }
}
