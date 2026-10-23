package com.pockivo.nowmark.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pockivo.nowmark.EntryRequests
import com.pockivo.nowmark.R
import com.pockivo.nowmark.recorder.Phase
import com.pockivo.nowmark.recorder.RecorderState
import com.pockivo.nowmark.ui.screens.DetailScreen
import com.pockivo.nowmark.ui.screens.LibraryScreen
import com.pockivo.nowmark.ui.screens.RecordScreen
import com.pockivo.nowmark.ui.screens.RecoveryScreen
import com.pockivo.nowmark.ui.screens.RemoveAdsScreen
import com.pockivo.nowmark.ui.screens.ReviewScreen
import com.pockivo.nowmark.ui.screens.SavedScreen
import com.pockivo.nowmark.ui.screens.SettingsScreen
import com.pockivo.nowmark.ui.screens.StatusScreen
import com.pockivo.nowmark.ui.screens.TrashScreen
import com.pockivo.nowmark.ui.screens.TrimScreen
import com.pockivo.nowmark.ui.theme.Nm

object Routes {
    const val RECORD = "record"
    const val LIBRARY = "library"
    const val REVIEW = "review"
    const val DETAIL = "detail/{id}"
    const val SAVED = "saved/{id}?fresh={fresh}"
    const val TRIM = "trim/{id}"
    const val STATUS = "status"
    const val SETTINGS = "settings"
    const val TRASH = "trash"
    const val RECOVERY = "recovery"
    const val REMOVE_ADS = "remove_ads"
    fun detail(id: String) = "detail/$id"
    fun saved(id: String, fresh: Boolean = false) = "saved/$id?fresh=$fresh"
    fun trim(id: String) = "trim/$id"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.RECORD, R.string.tab_record, Icons.Outlined.Mic),
    Tab(Routes.LIBRARY, R.string.tab_library, Icons.AutoMirrored.Outlined.Article),
    Tab(Routes.REVIEW, R.string.tab_review, Icons.Outlined.Headphones),
)

fun NavHostController.goTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun NowmarkRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val live by RecorderState.state.collectAsState()
    val startFromOutside by EntryRequests.startRecording.collectAsState()

    // Widget / shortcut: always land on the record tab, which then starts recording.
    LaunchedEffect(startFromOutside) {
        if (startFromOutside && route != Routes.RECORD) nav.goTab(Routes.RECORD)
    }
    // When saving finishes, show the result screen once.
    LaunchedEffect(live.phase, live.savedRecordingId) {
        val id = live.savedRecordingId
        if (live.phase == Phase.SAVED && id != null) {
            RecorderState.consumeSaved()
            nav.navigate(Routes.saved(id, fresh = true)) { launchSingleTop = true }
        }
    }

    val showBar = route in setOf(Routes.RECORD, Routes.LIBRARY, Routes.REVIEW, Routes.DETAIL)
    Scaffold(
        containerColor = Nm.c.ground,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = Nm.c.panel) {
                    val currentTab = when (route) {
                        Routes.DETAIL -> Routes.LIBRARY
                        else -> route
                    }
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = currentTab == t.route,
                            onClick = { nav.goTab(t.route) },
                            icon = { Icon(t.icon, null) },
                            label = { Text(stringResource(t.label)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Nm.c.accent,
                                selectedTextColor = Nm.c.accent,
                                indicatorColor = Nm.c.accentTint,
                                unselectedIconColor = Nm.c.inkSoft,
                                unselectedTextColor = Nm.c.inkSoft,
                            ),
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            NavHost(nav, startDestination = Routes.RECORD) {
                composable(Routes.RECORD) { RecordScreen(nav) }
                composable(Routes.LIBRARY) { LibraryScreen(nav) }
                composable(Routes.REVIEW) { ReviewScreen(nav) }
                composable(Routes.DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    DetailScreen(nav, it.arguments?.getString("id").orEmpty())
                }
                composable(
                    Routes.SAVED,
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType },
                        navArgument("fresh") { type = NavType.BoolType; defaultValue = false },
                    ),
                ) {
                    SavedScreen(nav, it.arguments?.getString("id").orEmpty(), it.arguments?.getBoolean("fresh") ?: false)
                }
                composable(Routes.TRIM, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    TrimScreen(nav, it.arguments?.getString("id").orEmpty())
                }
                composable(Routes.STATUS) { StatusScreen(nav) }
                composable(Routes.SETTINGS) { SettingsScreen(nav) }
                composable(Routes.TRASH) { TrashScreen(nav) }
                composable(Routes.RECOVERY) { RecoveryScreen(nav) }
                composable(Routes.REMOVE_ADS) { RemoveAdsScreen(nav) }
            }
        }
    }
}
