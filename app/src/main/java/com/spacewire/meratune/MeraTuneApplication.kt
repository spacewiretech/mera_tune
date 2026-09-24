package com.spacewire.meratune

import android.app.Application
import com.spacewire.meratune.analytics.AnalyticsLifecycleCallbacks
import com.spacewire.meratune.analytics.AnalyticsStateStore
import com.spacewire.meratune.analytics.MixpanelAnalytics
import com.spacewire.meratune.analytics.MetaAnalytics
import com.spacewire.meratune.analytics.FirebasePurchaseAnalytics
import com.spacewire.meratune.calltheme.IncomingCallMonitor
import com.spacewire.meratune.calltheme.IncomingCallNotifier
import com.spacewire.meratune.util.LocaleHelper

class MeraTuneApplication : Application() {
    lateinit var mixpanelAnalytics: MixpanelAnalytics
        private set
    lateinit var metaAnalytics: MetaAnalytics
        private set
    lateinit var firebaseAnalytics: FirebasePurchaseAnalytics
        private set

    override fun onCreate() {
        super.onCreate()
        LocaleHelper.applyStoredLocale(this)
        mixpanelAnalytics = MixpanelAnalytics.init(this)
        registerActivityLifecycleCallbacks(AnalyticsLifecycleCallbacks(mixpanelAnalytics, AnalyticsStateStore(this)))
        metaAnalytics = MetaAnalytics.init(this)
        firebaseAnalytics = FirebasePurchaseAnalytics.init(this)
        IncomingCallNotifier.ensureChannel(this)
        IncomingCallMonitor.start(this)
    }
}
