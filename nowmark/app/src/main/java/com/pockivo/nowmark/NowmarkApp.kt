package com.pockivo.nowmark

import android.app.Application
import com.pockivo.nowmark.ads.AdsManager
import com.pockivo.nowmark.billing.BillingManager
import com.pockivo.nowmark.data.AppDatabase
import com.pockivo.nowmark.data.SettingsRepository
import com.pockivo.nowmark.recorder.RecorderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Minimal service locator; the app is small enough not to need a DI framework. */
object AppGraph {
    lateinit var app: Application
    lateinit var db: AppDatabase
    lateinit var settings: SettingsRepository
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

class NowmarkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.app = this
        AppGraph.db = AppDatabase.create(this)
        AppGraph.settings = SettingsRepository(this)
        RecorderService.ensureChannel(this)
        // Billing first: a cached, verified purchase means the ad SDK is never initialized.
        BillingManager.init(this)
        AdsManager.init(this)
    }
}
