package com.pockivo.nowmark.ads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.pockivo.nowmark.AppGraph
import com.pockivo.nowmark.BuildConfig
import com.pockivo.nowmark.billing.BillingManager
import com.pockivo.nowmark.core.AdFrequency
import com.pockivo.nowmark.player.PlayerHub
import com.pockivo.nowmark.recorder.RecorderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central ad policy. Every load AND every show re-checks: not purchased, consent allows
 * ads, no recording session (recording, paused, saving) and no playback (playing or
 * buffering). Screens alone are not enough: opening the library during a recording must
 * still show no ads.
 */
object AdsManager {
    private lateinit var consent: ConsentInformation
    private val sdkStarted = AtomicBoolean(false)
    private val _canRequestAds = MutableStateFlow(false)
    private val _sdkReady = MutableStateFlow(false)
    private val _privacyOptionsRequired = MutableStateFlow(false)
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired

    private val frequency = AdFrequency()
    private var interstitial: InterstitialAd? = null
    private var interstitialLoading = false

    /** True only while ads may be requested and shown. Drives banner visibility too. */
    lateinit var adsAllowed: StateFlow<Boolean>
        private set

    fun init(context: Context) {
        consent = UserMessagingPlatform.getConsentInformation(context)
        _canRequestAds.value = consent.canRequestAds()
        adsAllowed = combine(
            BillingManager.adFree, _canRequestAds, _sdkReady, RecorderState.state, PlayerHub.ui,
        ) { adFree, can, ready, rec, play ->
            !adFree && can && ready && !rec.isActive && !play.isActive
        }.stateIn(AppGraph.scope, SharingStarted.Eagerly, false)
        if (!BillingManager.adFree.value && _canRequestAds.value) startSdk(context)
        AppGraph.scope.launch {
            BillingManager.adFree.collect { if (it) dropLoadedAds() }
        }
    }

    /** Synchronous re-check right before any load or show (handles late callbacks). */
    fun isAllowedNow(): Boolean =
        !BillingManager.adFree.value && _canRequestAds.value && _sdkReady.value &&
            !RecorderState.state.value.isActive && !PlayerHub.ui.value.isActive

    /**
     * Asks for consent where required (UMP). Called only from the library, never before a
     * recording starts, so the consent form cannot delay recording.
     */
    fun gatherConsent(activity: Activity) {
        if (BillingManager.adFree.value || RecorderState.state.value.isActive) return
        val params = ConsentRequestParameters.Builder().build()
        consent.requestConsentInfoUpdate(activity, params, {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                _canRequestAds.value = consent.canRequestAds()
                _privacyOptionsRequired.value =
                    consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
                if (_canRequestAds.value) startSdk(activity.applicationContext)
            }
        }, { _ ->
            // Consent info unavailable (e.g. offline): use what the SDK already knows.
            _canRequestAds.value = consent.canRequestAds()
            if (_canRequestAds.value) startSdk(activity.applicationContext)
        })
    }

    fun showPrivacyOptions(activity: Activity, done: () -> Unit) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { _ ->
            _canRequestAds.value = consent.canRequestAds()
            if (!_canRequestAds.value) dropLoadedAds()
            done()
        }
    }

    private fun startSdk(context: Context) {
        if (BillingManager.adFree.value || !sdkStarted.compareAndSet(false, true)) return
        AppGraph.scope.launch(Dispatchers.IO) {
            MobileAds.setRequestConfiguration(
                RequestConfiguration.Builder()
                    // Recording content is never sent. These settings keep ads general-audience.
                    .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_T)
                    .build()
            )
            MobileAds.initialize(context) { _ -> _sdkReady.value = true }
            // Ads in a recorder app should never make sound.
            MobileAds.setAppMuted(true)
            MobileAds.setAppVolume(0f)
        }
    }

    fun request(): AdRequest = AdRequest.Builder().build()

    /** Preload only when an interstitial would actually be due after this save. */
    fun preloadInterstitialIfDue(context: Context) {
        if (!isAllowedNow() || interstitial != null || interstitialLoading) return
        AppGraph.scope.launch(Dispatchers.Main) {
            val (saves, last) = AppGraph.settings.adCounters()
            if (!frequency.isDue(saves, last, System.currentTimeMillis()) || !isAllowedNow()) return@launch
            interstitialLoading = true
            InterstitialAd.load(context, BuildConfig.ADMOB_INTERSTITIAL_ID, request(), object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialLoading = false
                    // A late load is kept but never shown by itself.
                    interstitial = if (isAllowedNow()) ad else null
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialLoading = false
                    interstitial = null
                }
            })
        }
    }

    /**
     * Shows the interstitial at the "saved -> library" transition if it is ready, due and
     * allowed; otherwise calls [onDone] immediately. Never waits for loading.
     */
    fun showInterstitialOr(activity: Activity, onDone: () -> Unit) {
        val ad = interstitial
        if (ad == null || !isAllowedNow()) { onDone(); return }
        AppGraph.scope.launch(Dispatchers.Main) {
            val (saves, last) = AppGraph.settings.adCounters()
            val now = System.currentTimeMillis()
            if (!frequency.isDue(saves, last, now) || !isAllowedNow()) { onDone(); return@launch }
            interstitial = null
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() = onDone()
                override fun onAdFailedToShowFullScreenContent(e: AdError) = onDone()
            }
            AppGraph.settings.interstitialShown(now)
            ad.show(activity)
        }
    }

    private fun dropLoadedAds() {
        interstitial = null
    }
}
