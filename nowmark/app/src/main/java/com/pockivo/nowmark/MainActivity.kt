package com.pockivo.nowmark

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.pockivo.nowmark.billing.BillingManager
import com.pockivo.nowmark.ui.NowmarkRoot
import com.pockivo.nowmark.ui.theme.NowmarkTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** Entry requests coming from the widget, the app shortcut or a notification. */
object EntryRequests {
    /** Set when the user asked to start recording from outside the app UI. */
    val startRecording = MutableStateFlow(false)
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent { NowmarkTheme { NowmarkRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        BillingManager.refresh()
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_RECORD) {
            EntryRequests.startRecording.value = true
            intent.action = null
        }
    }

    companion object {
        const val ACTION_RECORD = "com.pockivo.nowmark.action.RECORD"
    }
}
