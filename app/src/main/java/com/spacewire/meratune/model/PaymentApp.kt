package com.spacewire.meratune.model

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import com.spacewire.meratune.R

enum class PaymentApp(
    val displayName: String,
    /** The UPI app's package; `null` for [UPI_ID], which is not an app. */
    val packageName: String?,
    val iconBackgroundRes: Int,
    val iconLabel: String,
    /**
     * A bundled brand logo for the round badge, used when the installed app's launcher icon cannot
     * be loaded (see `ui/PaymentAppBadge`); null shows [iconLabel] on [iconBackgroundRes] then.
     */
    val logoRes: Int? = null,
    /** A localized label that replaces [displayName] on screen (brand names stay untranslated). */
    @param:StringRes val labelRes: Int? = null,
) {
    PHONEPE(
        displayName = "PhonePe",
        packageName = "com.phonepe.app",
        iconBackgroundRes = R.drawable.bg_phonepe_icon,
        iconLabel = "पे",
    ),
    GOOGLE_PAY(
        displayName = "Google Pay",
        packageName = "com.google.android.apps.nbu.paisa.user",
        iconBackgroundRes = R.drawable.bg_gpay_icon,
        iconLabel = "G",
    ),
    PAYTM(
        displayName = "Paytm",
        packageName = "net.one97.paytm",
        iconBackgroundRes = R.drawable.bg_paytm_icon,
        iconLabel = "Pa",
    ),
    BHIM(
        displayName = "BHIM UPI",
        packageName = "in.org.npci.upiapp",
        iconBackgroundRes = R.drawable.bg_bhim_icon,
        iconLabel = "BH",
    ),

    /**
     * Cashfree's hosted subscription checkout, where the user types a UPI ID (or picks any UPI app
     * the web page finds). The only option when none of the apps above is installed, and the last
     * row of the payment-app sheet otherwise.
     */
    UPI_ID(
        displayName = "UPI ID",
        packageName = null,
        iconBackgroundRes = R.drawable.ic_upi_id,
        iconLabel = "",
        logoRes = R.drawable.ic_upi_id,
        labelRes = R.string.paywall_upi_id,
    ),
    ;

    /** True for the UPI apps opened by intent; false for [UPI_ID]. */
    val isUpiApp: Boolean
        get() = packageName != null

    /** Always false for [UPI_ID]: there is no app to find. */
    fun isInstalled(packageManager: PackageManager): Boolean {
        val pkg = packageName ?: return false
        return isPackageInstalled(packageManager, pkg)
    }

    /** The pill / sheet label: [labelRes] when set, else the brand [displayName]. */
    fun label(context: Context): String = labelRes?.let(context::getString) ?: displayName

    companion object {
        /** The apps opened by UPI intent, in sheet order. */
        val UPI_APPS: List<PaymentApp> = entries.filter { it.isUpiApp }

        fun installed(packageManager: PackageManager): List<PaymentApp> =
            installedFrom { pkg -> isPackageInstalled(packageManager, pkg) }

        /** [UPI_APPS] whose package [hasPackage] reports as installed, in order; never [UPI_ID]. */
        fun installedFrom(hasPackage: (String) -> Boolean): List<PaymentApp> =
            UPI_APPS.filter { app -> app.packageName?.let(hasPackage) == true }

        /** The payment-app sheet rows: the installed apps, then [UPI_ID] last. */
        fun sheetOptions(installed: List<PaymentApp>): List<PaymentApp> =
            installed.filter { it.isUpiApp } + UPI_ID

        /** The paywall's starting choice: the first installed app, else [UPI_ID]. */
        fun defaultSelection(installed: List<PaymentApp>): PaymentApp =
            installed.firstOrNull { it.isUpiApp } ?: UPI_ID

        /** The [UPI_ID] sheet row reads "other UPI app / UPI ID" only when apps are listed above it. */
        @StringRes
        fun upiIdSheetLabelRes(installed: List<PaymentApp>): Int =
            if (installed.any { it.isUpiApp }) R.string.paywall_upi_id_other else R.string.paywall_upi_id_pay

        /** The sheet title: "select payment app", or "no UPI app installed" above a lone [UPI_ID] row. */
        @StringRes
        fun sheetTitleRes(options: List<PaymentApp>): Int =
            if (options.any { it.isUpiApp }) R.string.subscription_select_payment_app
            else R.string.subscription_no_payment_app_installed

        private fun isPackageInstalled(packageManager: PackageManager, pkg: String): Boolean = try {
            packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
