package io.github.happytechca.overland

import android.content.Context
import android.text.format.DateUtils
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import java.util.Locale

/** Draws behind the system bars (enforced from Android 15) and pads [root] so content stays clear of them. */
fun AppCompatActivity.edgeToEdge(root: View) {
    enableEdgeToEdge()
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
        insets
    }
}

fun View.themeColor(@AttrRes attr: Int): Int = MaterialColors.getColor(this, attr)

fun View.snack(text: CharSequence) = Snackbar.make(this, text, Snackbar.LENGTH_SHORT).show()

/** "just now", "40 sec. ago", "3 min. ago"… */
fun Context.ago(time: Long): CharSequence {
    val now = System.currentTimeMillis()
    if (now - time < 5_000) return getString(R.string.just_now)
    return DateUtils.getRelativeTimeSpanString(time, now, DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
}

/** 38:12 or 1:05:09 */
fun clock(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    val h = sec / 3600
    val m = sec % 3600 / 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}

/** Icon and label for an Overland motion value */
enum class MotionUi(val value: String?, @DrawableRes val icon: Int, @StringRes val label: Int) {
    DRIVING("driving", R.drawable.ic_directions_car, R.string.motion_driving),
    CYCLING("cycling", R.drawable.ic_directions_bike, R.string.motion_cycling),
    WALKING("walking", R.drawable.ic_directions_walk, R.string.motion_walking),
    RUNNING("running", R.drawable.ic_directions_run, R.string.motion_running),
    STATIONARY("stationary", R.drawable.ic_pause_circle, R.string.motion_stationary),
    UNKNOWN(null, R.drawable.ic_help, R.string.motion_unknown);

    companion object {
        fun of(motion: String?) = entries.firstOrNull { it.value == motion } ?: UNKNOWN
    }
}
