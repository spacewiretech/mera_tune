package com.spacewire.meratune.model

import android.content.pm.PackageManager
import com.spacewire.meratune.R

enum class PaymentApp(
    val displayName: String,
    val packageName: String,
    val iconBackgroundRes: Int,
    val iconLabel: String,
    /**
     * A bundled brand logo for the round badge, used when the installed app's launcher icon cannot
     * be loaded (see `ui/PaymentAppBadge`); null shows [iconLabel] on [iconBackgroundRes] then.
     */
    val logoRes: Int? = null,
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
    ;

    fun isInstalled(packageManager: PackageManager): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    companion object {
        val DEFAULT = PHONEPE

        fun installed(packageManager: PackageManager): List<PaymentApp> =
            entries.filter { it.isInstalled(packageManager) }
    }
}
