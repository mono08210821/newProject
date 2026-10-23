package com.pockivo.nowmark.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.ui.graphics.vector.ImageVector
import com.pockivo.nowmark.R
import com.pockivo.nowmark.core.AudioFormatKind
import com.pockivo.nowmark.data.HealthType
import com.pockivo.nowmark.data.MarkCategory
import com.pockivo.nowmark.data.Preset
import java.util.Date
import java.util.Locale

object Labels {
    @StringRes fun preset(p: Preset) = when (p) {
        Preset.VOICE -> R.string.preset_voice
        Preset.MEETING -> R.string.preset_meeting
        Preset.MUSIC -> R.string.preset_music
    }

    @StringRes fun presetDesc(p: Preset) = when (p) {
        Preset.VOICE -> R.string.preset_voice_desc
        Preset.MEETING -> R.string.preset_meeting_desc
        Preset.MUSIC -> R.string.preset_music_desc
    }

    @StringRes fun category(c: MarkCategory) = when (c) {
        MarkCategory.IMPORTANT -> R.string.cat_important
        MarkCategory.TODO -> R.string.cat_todo
        MarkCategory.QUESTION -> R.string.cat_question
        MarkCategory.IDEA -> R.string.cat_idea
    }

    fun categoryIcon(c: MarkCategory): ImageVector = when (c) {
        MarkCategory.IMPORTANT -> Icons.Outlined.BookmarkBorder
        MarkCategory.TODO -> Icons.Outlined.CheckBox
        MarkCategory.QUESTION -> Icons.AutoMirrored.Outlined.HelpOutline
        MarkCategory.IDEA -> Icons.Outlined.Lightbulb
    }

    /** "Done" wording depends on category: to-do completed, question resolved. */
    @StringRes fun doneAction(c: MarkCategory) = when (c) {
        MarkCategory.TODO -> R.string.action_todo_done
        MarkCategory.QUESTION -> R.string.action_question_resolved
        else -> R.string.action_mark_reviewed
    }

    @StringRes fun health(t: HealthType) = when (t) {
        HealthType.INPUT_SILENCED -> R.string.health_silenced
        HealthType.LOW_INPUT -> R.string.health_low
        HealthType.DEVICE_CHANGED -> R.string.health_device
        HealthType.WRITE_DELAY -> R.string.health_write_delay
        HealthType.WRITE_FAILED -> R.string.health_write_failed
        HealthType.READ_ERROR -> R.string.health_read_error
        HealthType.UNFINISHED_SESSION -> R.string.health_unfinished
    }

    @StringRes fun device(type: Int?) = when (type) {
        null -> R.string.device_unknown
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> R.string.device_builtin
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> R.string.device_wired
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> R.string.device_usb
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26 /* BLE_HEADSET */ -> R.string.device_bluetooth
        else -> R.string.device_other
    }

    fun format(f: AudioFormatKind) = f.extension.uppercase(Locale.ROOT)

    /** "Today · 10:02", "Yesterday", or a short date. */
    fun whenShort(context: Context, at: Long): String {
        val time = DateFormat.getTimeFormat(context).format(Date(at))
        return when {
            DateUtils.isToday(at) -> context.getString(R.string.today_at, time)
            DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.yesterday)
            else -> DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        }
    }

    fun dateTime(context: Context, at: Long): String =
        DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
}
