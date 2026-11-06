/* P4 车机 HUD 广播 → CarLife 导航上行（iAP2 0x5201/0x5202）。
 *
 * 移植自 hybrid63 `inject/src/tl/CarLifeHudReceiver.java`（本项目旧插桩实现）：
 * 原版在宿主里注册普通动态广播接收器，收 CarWith/CarLife 的
 * `carlife_show_hud_info` / `mix_notify_start_navi`，解码成 HUD 快照后交给
 * `CarLifeNav.onHud/onHudStop`。本实现不做任何宿主 Hook，只做普通广播接收，
 * 数据统一汇入 `CarLifeNav.submitHud` / `CarLifeNav.stop`（与 CarLife 0x10030、
 * 高德 AMap 两条数据源共用同一条 iAP2 上行与去重逻辑）。
 *
 * 另外提供一个**调试注入** action（`io.github.shaonialife.buicklinkplus.CARLIFE_HUD`），
 * 方便实车/模拟器用 adb 直接灌 HUD 测试数据：
 *
 * ```
 * # 灌一条 HUD：turn=3（右转）、剩余 800m、总程 4200m
 * adb shell am broadcast -a io.github.shaonialife.buicklinkplus.CARLIFE_HUD \
 *     --ei turn 3 --ei remain 800 --ei total 4200 --es road "人民路"
 * # 结束导航（清除车机 HUD）
 * adb shell am broadcast -a io.github.shaonialife.buicklinkplus.CARLIFE_HUD --ez stop true
 * # 兼容旧版 CarWith/CarLife 的原始广播
 * adb shell am broadcast -a carlife_show_hud_info --ei action 3
 * ```
 *
 * 注册方式（Lead 决定）：任选其一，不需要在 Manifest 注册也能工作——
 * 1. 动态注册：在 `SenderApp.onCreate` / `SessionController.ensure` 里调用
 *    [CarLifeHudReceiver.install]（推荐，未安装 App 时不会暴露接收器面）；
 * 2. Manifest 静态注册（若要接收应用被杀后的广播）：
 *    ```xml
 *    <receiver android:name=".ui.CarLifeHudReceiver" android:exported="true">
 *        <intent-filter>
 *            <action android:name="carlife_show_hud_info" />
 *            <action android:name="mix_notify_start_navi" />
 *            <action android:name="io.github.shaonialife.buicklinkplus.CARLIFE_HUD" />
 *        </intent-filter>
 *    </receiver>
 *    ```
 *    静态注册时系统会走无参构造，本类无状态、直接可用。
 *
 * 可靠性：收不到广播（部分 ROM 用显式广播）不影响导航主链路——CarLife 0x10030 与
 * 高德 AMap 数据源仍照常汇入 [CarLifeNav]；失败只留诊断日志。
 */
package io.github.shaonialife.buicklinkplus.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.shaonialife.buicklinkplus.carlife.CarLifeNav

/** 车机 HUD 广播接收器（CarWith/CarLife 原始广播 + adb 调试注入），数据汇入 [CarLifeNav]。 */
class CarLifeHudReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context?,
        intent: Intent?,
    ) {
        if (intent == null) return
        val action = intent.action ?: return
        try {
            when (action) {
                ACTION_DEBUG_HUD -> handleDebugHud(intent)
                ACTION_LEGACY_HUD -> handleLegacyHud(intent)
                ACTION_LEGACY_NAVI -> handleLegacyNavi(intent)
                else -> Unit
            }
        } catch (error: Throwable) {
            // 广播处理异常不能影响进程/其它接收器
            Log.w(TAG, "onReceive failed", error)
            hudLog("HUD: 广播处理失败 action=$action - ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    // ---- 调试注入 ----

    /** 自定义调试 action：`stop`=结束导航；否则 turn/remain/total/road 灌一条 HUD。 */
    private fun handleDebugHud(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_STOP, false)) {
            val sent = CarLifeNav.stop()
            hudLog("HUD: [调试] 停止导航 → 已发 $sent 条清理帧")
            return
        }
        submit(
            turn = intent.getIntExtra(EXTRA_TURN, 0),
            remainMeters = intent.getIntExtra(EXTRA_REMAIN, 0),
            totalMeters = intent.getIntExtra(EXTRA_TOTAL, 0),
            road = intent.getStringExtra(EXTRA_ROAD).orEmpty(),
            source = "调试广播",
        )
    }

    // ---- 兼容 hybrid63 的原版广播 ----

    /** CarWith/CarLife：`action`=3 停止；否则 navi_turn/remain_distance/total_distance/road_name。 */
    private fun handleLegacyHud(intent: Intent) {
        val action = intent.getIntExtra(EXTRA_LEGACY_ACTION, 1)
        if (action == 3) {
            val sent = CarLifeNav.stop()
            hudLog("HUD: 车机广播停止导航 → 已发 $sent 条清理帧")
            return
        }
        submit(
            turn = intent.getIntExtra(EXTRA_LEGACY_TURN, 0),
            remainMeters = intent.getIntExtra(EXTRA_LEGACY_REMAIN, 0),
            totalMeters = intent.getIntExtra(EXTRA_LEGACY_TOTAL, 0),
            road = intent.getStringExtra(EXTRA_LEGACY_ROAD).orEmpty(),
            source = "车机广播 action=$action",
        )
    }

    /** CarWith/CarLife：isNavi=false 表示导航结束。 */
    private fun handleLegacyNavi(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_LEGACY_IS_NAVI, false)) {
            hudLog("HUD: 车机广播导航开始（等待 HUD 数据）")
            return
        }
        val sent = CarLifeNav.stop()
        hudLog("HUD: 车机广播导航结束 → 已发 $sent 条清理帧")
    }

    private fun submit(
        turn: Int,
        remainMeters: Int,
        totalMeters: Int,
        road: String,
        source: String,
    ) {
        // CarLifeNav 内部：total<=0 不发（避免把车机已有 HUD 覆盖成空）、700ms 去重、未订阅时返回 0
        val sent = CarLifeNav.submitHud(turn, remainMeters, totalMeters, road)
        hudLog(
            "HUD: [$source] turn=$turn remain=${remainMeters.coerceAtLeast(0)}m " +
                "total=${totalMeters.coerceAtLeast(0)}m road=$road → 已发 $sent 条",
        )
    }

    /** 诊断日志：优先走会话日志出口（SessionController 已接 [CarLifeNav.log]），次之 android Log。 */
    private fun hudLog(message: String) {
        Log.i(TAG, message)
        CarLifeNav.log?.invoke(message)
    }

    companion object {
        private const val TAG = "CarLifeHud"

        /** 调试注入 action（adb broadcast 灌 HUD 测试数据，见文件头注释）。 */
        const val ACTION_DEBUG_HUD = "io.github.shaonialife.buicklinkplus.CARLIFE_HUD"

        /** hybrid63/BOFF 参考的 CarWith/CarLife HUD 广播。 */
        const val ACTION_LEGACY_HUD = "carlife_show_hud_info"

        /** hybrid63/BOFF 参考的 CarWith 导航开始/结束广播。 */
        const val ACTION_LEGACY_NAVI = "mix_notify_start_navi"

        const val EXTRA_STOP = "stop"
        const val EXTRA_TURN = "turn"
        const val EXTRA_REMAIN = "remain"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_ROAD = "road"
        const val EXTRA_LEGACY_ACTION = "action"
        const val EXTRA_LEGACY_TURN = "navi_turn"
        const val EXTRA_LEGACY_REMAIN = "remain_distance"
        const val EXTRA_LEGACY_TOTAL = "total_distance"
        const val EXTRA_LEGACY_ROAD = "road_name"
        const val EXTRA_LEGACY_IS_NAVI = "isNavi"

        @Volatile
        private var installed = false

        /** 是否已动态注册。 */
        val isInstalled: Boolean
            get() = installed

        /**
         * 动态注册 HUD 广播接收器（幂等；应用生命周期内只需一次）。
         *
         * 使用 RECEIVER_EXPORTED：这些广播来自车机/CarWith 系统或 adb 调试，不是本应用进程内广播；
         * 只解析 HUD 数据、无敏感副作用。若只在 debug 包启用，调用方加 BuildConfig.DEBUG 判断即可。
         */
        @Synchronized
        fun install(context: Context?): Boolean {
            if (installed) return true
            if (context == null) return false
            val app = context.applicationContext ?: context
            return runCatching {
                val filter =
                    IntentFilter().apply {
                        addAction(ACTION_DEBUG_HUD)
                        addAction(ACTION_LEGACY_HUD)
                        addAction(ACTION_LEGACY_NAVI)
                    }
                ContextCompat.registerReceiver(app, CarLifeHudReceiver(), filter, ContextCompat.RECEIVER_EXPORTED)
                installed = true
                Log.i(TAG, "HUD receiver installed")
                CarLifeNav.log?.invoke(
                    "HUD: 广播接收器已注册（$ACTION_DEBUG_HUD / $ACTION_LEGACY_HUD / $ACTION_LEGACY_NAVI）",
                )
                true
            }.getOrElse { error ->
                Log.w(TAG, "install failed", error)
                CarLifeNav.log?.invoke("HUD: 广播接收器注册失败 - ${error.message}")
                false
            }
        }
    }
}
