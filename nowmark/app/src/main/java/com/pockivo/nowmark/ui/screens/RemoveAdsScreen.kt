package com.pockivo.nowmark.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.pockivo.nowmark.R
import com.pockivo.nowmark.billing.BillingManager
import com.pockivo.nowmark.billing.PriceState
import com.pockivo.nowmark.billing.PurchaseFlow
import com.pockivo.nowmark.ui.components.BigButton
import com.pockivo.nowmark.ui.components.IconTile
import com.pockivo.nowmark.ui.theme.Nm

/** "All features are free; a one-time purchase removes ads only." No pressure, no popups. */
@Composable
fun RemoveAdsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val adFree by BillingManager.adFree.collectAsState()
    val price by BillingManager.price.collectAsState()
    val flow by BillingManager.flow.collectAsState()
    DisposableEffect(Unit) { onDispose { BillingManager.clearFlow() } }

    Column(Modifier.fillMaxSize()) {
        BackBar(nav)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            IconTile(if (adFree) Icons.Outlined.CheckCircle else Icons.Outlined.Block, Nm.c.accent, Nm.c.accentTint, 64)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(if (adFree) R.string.ads_removed else R.string.remove_ads_title), style = MaterialTheme.typography.headlineMedium, color = Nm.c.ink)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.remove_ads_body), style = MaterialTheme.typography.bodyLarge, color = Nm.c.inkSoft)
            Spacer(Modifier.height(16.dp))
            listOf(R.string.remove_ads_point1, R.string.remove_ads_point2, R.string.remove_ads_point3).forEach {
                Text("• " + stringResource(it), style = MaterialTheme.typography.bodyLarge, color = Nm.c.ink, modifier = Modifier.padding(vertical = 4.dp))
            }
            Spacer(Modifier.height(16.dp))
            val msg = when (flow) {
                PurchaseFlow.PENDING -> R.string.purchase_pending
                PurchaseFlow.SUCCESS -> R.string.purchase_success
                PurchaseFlow.CANCELLED -> R.string.purchase_cancelled
                PurchaseFlow.ERROR -> R.string.purchase_error
                PurchaseFlow.RESTORED_NONE -> R.string.restore_none
                PurchaseFlow.RESTORE_FAILED -> R.string.restore_failed
                PurchaseFlow.IDLE -> null
            }
            if (msg != null) Text(stringResource(msg), style = MaterialTheme.typography.bodyLarge, color = Nm.c.accent)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.purchase_account_note), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft)
        }
        Column(Modifier.padding(20.dp)) {
            if (!adFree) {
                when (val p = price) {
                    is PriceState.Ready -> BigButton(stringResource(R.string.buy_remove_ads, p.formatted), onClick = {
                        context.findActivity()?.let { BillingManager.launch(it) }
                    }, enabled = flow != PurchaseFlow.PENDING)
                    PriceState.Loading -> BigButton(stringResource(R.string.price_loading), onClick = {}, enabled = false)
                    // No made-up price: offer a retry instead.
                    PriceState.Unavailable -> BigButton(stringResource(R.string.price_retry), onClick = { BillingManager.refresh() })
                }
            }
            TextButton(onClick = { BillingManager.restore() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.restore_purchase)) }
        }
    }
}
