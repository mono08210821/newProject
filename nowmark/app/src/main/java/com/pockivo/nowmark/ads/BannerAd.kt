package com.pockivo.nowmark.ads

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.pockivo.nowmark.BuildConfig

/**
 * Library-only anchored adaptive banner. It takes no space until an ad has loaded, and is
 * removed (destroyed, no refresh) as soon as recording/playback starts or ads are removed.
 */
@Composable
fun LibraryBanner() {
    val allowed by AdsManager.adsAllowed.collectAsState()
    if (!allowed) return
    val context = LocalContext.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    var loadedHeight by remember { mutableIntStateOf(0) }
    val view = remember {
        AdView(context).apply {
            adUnitId = BuildConfig.ADMOB_BANNER_ID
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp))
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    if (!AdsManager.isAllowedNow()) { visibility = android.view.View.GONE; return }
                    loadedHeight = adSize?.height ?: 50
                }
                override fun onAdFailedToLoad(error: LoadAdError) { loadedHeight = 0 }
            }
        }
    }
    DisposableEffect(view) {
        if (AdsManager.isAllowedNow()) view.loadAd(AdsManager.request())
        onDispose { view.destroy() }
    }
    AndroidView(
        factory = { view },
        modifier = Modifier.fillMaxWidth().height(loadedHeight.dp),
    )
}
