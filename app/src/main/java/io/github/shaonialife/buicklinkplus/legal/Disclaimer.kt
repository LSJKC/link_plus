package io.github.shaonialife.buicklinkplus.legal

import android.content.Context
import androidx.core.content.edit

/** 按版本保存免责声明接受状态，更新条款后可要求重新确认 */
object DisclaimerPrefs {
    /** 声明版本，修改正文时同步递增 */
    const val CURRENT_VERSION = 3

    private const val PREFS = "disclaimer"
    private const val KEY_ACCEPTED_VERSION = "accepted_version"

    fun acceptedVersion(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ACCEPTED_VERSION, 0)

    fun hasAcceptedCurrent(context: Context): Boolean = acceptedVersion(context) >= CURRENT_VERSION

    fun accept(context: Context) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putInt(KEY_ACCEPTED_VERSION, CURRENT_VERSION)
            }
    }

    /** 撤销声明接受状态，供测试使用 */
    fun reset(context: Context) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                remove(KEY_ACCEPTED_VERSION)
            }
    }
}

/** 倒计时结束，且内容无需滚动或已滚到底部时，允许接受声明 */
internal fun canAcceptDisclaimer(
    countdownDone: Boolean,
    contentScrollable: Boolean,
    scrolledToBottom: Boolean,
): Boolean = countdownDone && (!contentScrollable || scrolledToBottom)

/** 是否尚未接受当前版本声明 */
internal fun needsDisclaimerAtLaunch(
    acceptedVersion: Int,
    currentVersion: Int,
): Boolean = acceptedVersion < currentVersion

/** 按钮不可用时的说明，可用时返回 null */
internal fun disclaimerBlockReason(
    countdownDone: Boolean,
    contentScrollable: Boolean,
    scrolledToBottom: Boolean,
    remainingSeconds: Int,
): String? =
    when {
        !countdownDone && contentScrollable && !scrolledToBottom ->
            "请滑动阅读至底部，并等待 $remainingSeconds 秒"

        !countdownDone -> "请等待 $remainingSeconds 秒后接受"
        contentScrollable && !scrolledToBottom -> "请滑动阅读至底部"
        else -> null
    }
