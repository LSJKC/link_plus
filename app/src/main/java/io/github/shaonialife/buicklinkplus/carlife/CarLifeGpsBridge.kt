/* P4 GPS 回填：Android Location → CarLife 0x18010 `CarlifeCarGps`。
 *
 * 数据方向：本机 Android 定位（GPS/网络 provider）→ [CarGpsFix] → sink
 * （接线方转给 [io.github.shaonialife.buicklinkplus.carlife.CarLifeHuClient.sendCarGps]）。
 * CarLife+ 地图在「车机定位优先」时使用这条通道的坐标，可改善车内手机定位差（贴膜/仪表台）
 * 时的导航定位。改编自 CarPToCarL `carlife/CarGpsOption.kt` 的开关语义与
 * `CarlifeHuClient.sendCarGps` 的单位约定（1/1,000,000 度 / 0.1 米 / 0.01 km/h）。
 *
 * 说明：
 * - 当前数据源是 **Android Location**；将来接 iAP2 0xFFFB 的车机 GPS 时，把 NMEA 解析成
 *   [CarGpsFix] 后走同样的 [submitFix] 即可（同一把开关、同一路 sink），不需要改本类；
 * - 开关偏好 `carlife_use_car_gps`（`settings` SharedPreferences，默认开）：
 *   "车机 GPS 优先/回填" 关闭时一条都不发，CarLife 完全按手机自己的定位走；
 * - 1Hz 节流（Android Location 可能比 1Hz 更密；HuClient 侧还有 900ms 兜底节流）；
 * - 定位回调放在独立 HandlerThread 上，sink（通常直接写 CarLife 控制通道）不在主线程执行；
 * - 权限缺失/定位关闭/开关关闭时安全空转，只记限频日志。
 *
 * 接线（Lead 在 SessionController 完成）：
 * 1. `ensure()`/会话启动时创建并 `sink = { fix -> (currentScreenSource as? CarLifeScreenSource)?.sendCarGps(fix) == true }`；
 *    建议 CarLifeScreenSource 增加一行 `fun sendCarGps(fix: CarGpsFix): Boolean =
 *    session?.client?.sendCarGps(fix) ?: false` 供取当前客户端；
 * 2. 会话建立/定位权限就绪后 `start()`；会话结束/退出时 `stop()`（也可常驻，开关关时零下发）；
 * 3. 开关 UI 可调 [isEnabled]/[setEnabled]（偏好键即 `carlife_use_car_gps`）。
 */
package io.github.shaonialife.buicklinkplus.carlife

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.util.Calendar
import java.util.TimeZone

/**
 * Android Location → CarLife `0x18010 CarlifeCarGps` 的回填桥。
 *
 * 生命周期：[start] 注册 GPS/网络两个 provider 的定位回调（独立线程），[stop] 注销并退出线程；
 * 两者幂等。是否真正下发还受 `carlife_use_car_gps` 开关（默认开）与 1Hz 节流约束。
 * [sink] 未接线时安全丢弃（只记首次日志）。
 */
class CarLifeGpsBridge(
    context: Context,
    private val log: (String) -> Unit = {},
) {
    private val appContext: Context = context.applicationContext ?: context

    /** 定位快照输出口；接线方转给 `CarLifeHuClient.sendCarGps`。 */
    @Volatile
    var sink: ((CarGpsFix) -> Unit)? = null

    @Volatile
    private var running = false

    private var manager: LocationManager? = null

    private var callbackThread: HandlerThread? = null

    /** 上次实际输出时刻（uptimeMillis，1Hz 节流）。 */
    private var lastEmitAt = 0L

    /** 上一条 Location 的时间戳，用于丢弃重复的同一帧。 */
    private var lastLocationTime = 0L

    /** 已输出条数（诊断）。 */
    private var emitted = 0L

    private var lastDisabledLogAt = 0L

    private var missingSinkLogged = false

    /** 定位回调（独立 HandlerThread 上触发，不阻塞主线程）。 */
    private val listener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                submit(location)
            }

            override fun onProviderEnabled(provider: String) {
                log("GPS: 定位 provider 已启用 $provider")
            }

            override fun onProviderDisabled(provider: String) {
                log("GPS: 定位 provider 已停用 $provider（等待其恢复或被其它 provider 兜底）")
            }
        }

    /** 是否正在监听定位。 */
    val isRunning: Boolean
        get() = running

    /**
     * 开始监听 Android 定位（幂等）。
     *
     * @return true = 已注册至少一个 provider；false = 开关关闭/无权限/定位服务不可用/已停止
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        if (!isEnabled(appContext)) {
            log("GPS: 「车机 GPS 回填」已关闭（${PREF_KEY_ENABLED}=false），不启动定位监听")
            return false
        }
        val locationManager =
            appContext.getSystemService(LocationManager::class.java)
                ?: run {
                    log("GPS: LocationManager 不可用，无法启动定位监听")
                    return false
                }
        val fineGranted =
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val coarseGranted =
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            log("GPS: 未授予定位权限（ACCESS_FINE/COARSE_LOCATION 都没有），不启动定位监听")
            return false
        }

        val thread = HandlerThread(GPS_THREAD_NAME).apply { start() }
        val providers = ArrayList<String>(2)
        if (fineGranted) providers += LocationManager.GPS_PROVIDER
        // 网络 provider 只要有任一权限即可用（COARSE 定位的主要来源）
        providers += LocationManager.NETWORK_PROVIDER
        var registered = 0
        for (provider in providers) {
            val ok =
                runCatching {
                    locationManager.requestLocationUpdates(provider, MIN_INTERVAL_MS, 0f, listener, thread.looper)
                    true
                }.getOrElse { error ->
                    log("GPS: 注册 $provider 失败 - ${error.message}")
                    false
                }
            if (ok) registered++
        }
        if (registered == 0) {
            thread.quitSafely()
            log("GPS: 所有 provider 注册失败，定位回填未启动")
            return false
        }
        manager = locationManager
        callbackThread = thread
        running = true
        log(
            "GPS: 已启动 Android 定位回填（provider=${providers.joinToString()}，" +
                "1s 节流，FINE=$fineGranted，sink=${if (sink != null) "已接线" else "未接线"})",
        )
        return true
    }

    /** 停止监听并退出回调线程（幂等）。 */
    @Synchronized
    fun stop() {
        if (!running) return
        runCatching { manager?.removeUpdates(listener) }
        callbackThread?.quitSafely()
        manager = null
        callbackThread = null
        running = false
        log("GPS: 已停止定位回填（累计输出 $emitted 条）")
    }

    /**
     * 提交一条 Android Location（定位回调入口，也可由调试/测试直接调用）。
     *
     * 依次检查：开关 → 1Hz 节流 → 重复帧 → 转换 → 输出。失败安全返回 false。
     */
    fun submit(location: Location): Boolean {
        if (!ensureEnabled()) return false
        val now = SystemClock.uptimeMillis()
        if (lastEmitAt != 0L && now - lastEmitAt < MIN_INTERVAL_MS) return false
        if (location.time > 0L && location.time == lastLocationTime) return false
        val fix = toCarGpsFix(location) ?: return false
        lastEmitAt = now
        lastLocationTime = location.time
        return emit(fix)
    }

    /**
     * 提交一条已经转换好的 CarLife 定位快照（供将来 iAP2 0xFFFB 车机 GPS 等其它数据源复用；
     * 同一把开关、同一路 sink、同一 1Hz 节流）。
     */
    fun submitFix(fix: CarGpsFix): Boolean {
        if (!ensureEnabled()) return false
        val now = SystemClock.uptimeMillis()
        if (lastEmitAt != 0L && now - lastEmitAt < MIN_INTERVAL_MS) return false
        lastEmitAt = now
        return emit(fix)
    }

    /** 诊断摘要（会话日志/自检用）。 */
    fun statusText(): String {
        val switch = if (isEnabled(appContext)) "开" else "关"
        return "定位回填=${if (running) "监听中" else "未启动"} 开关=$switch " +
            "sink=${if (sink != null) "已接线" else "未接线"} 已输出=$emitted"
    }

    /** Android Location → CarLife GPS 单位约定（纬经度度、高度 m、速度 km/h、航向度）。 */
    fun toCarGpsFix(location: Location): CarGpsFix? {
        val timeMillis = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = timeMillis }
        val satellites = runCatching { location.extras?.getInt("satellites", 0) ?: 0 }.getOrDefault(0)
        // Android 给的是水平精度（68% 圆半径，米）；NMEA hdop 约 5m/单位，用 accuracy/5 折算
        val hdop = if (location.hasAccuracy()) location.accuracy.toDouble() / 5.0 else null
        return CarGpsFix(
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = if (location.hasAltitude()) location.altitude else null,
            speedKmh = if (location.hasSpeed()) location.speed * 3.6 else null,
            headingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
            // 0=无效；GPS/网络定位都按 1（GPS fix）上报，CarLife 侧只要求非 0
            fixQuality = 1,
            satellitesUsed = satellites,
            satellitesVisible = satellites,
            hdop = hdop,
            pdop = null,
            vdop = null,
            utcYear = utc.get(Calendar.YEAR),
            utcMonth = utc.get(Calendar.MONTH) + 1,
            utcDay = utc.get(Calendar.DAY_OF_MONTH),
            utcHour = utc.get(Calendar.HOUR_OF_DAY),
            utcMinute = utc.get(Calendar.MINUTE),
            utcSecond = utc.get(Calendar.SECOND),
        )
    }

    // ---- 内部实现 ----

    /** 开关检查（可在运行中动态关闭：UI 切开关后下一条定位立即生效）。 */
    private fun ensureEnabled(): Boolean {
        if (isEnabled(appContext)) return true
        val now = SystemClock.uptimeMillis()
        if (now - lastDisabledLogAt >= DISABLED_LOG_INTERVAL_MS) {
            lastDisabledLogAt = now
            log("GPS: 「车机 GPS 回填」已关闭（${PREF_KEY_ENABLED}=false），丢弃定位样本")
        }
        return false
    }

    private fun emit(fix: CarGpsFix): Boolean {
        val target = sink
        if (target == null) {
            if (!missingSinkLogged) {
                missingSinkLogged = true
                log("GPS: 定位回填 sink 未接线，样本丢弃（Lead 接线后恢复）")
            }
            return false
        }
        val ok = runCatching { target.invoke(fix) }.isSuccess
        if (!ok) {
            log("GPS: 定位回填 sink 抛异常，已忽略（不影响会话）")
            return false
        }
        emitted++
        if (emitted <= 3L || emitted % 30L == 0L) {
            log(
                "GPS: 已输出定位 #$emitted " +
                    "lat=${"%.6f".format(fix.latitude)} lon=${"%.6f".format(fix.longitude)} " +
                    "alt=${fix.altitudeMeters?.let { "%.1f".format(it) } ?: "未知"}m " +
                    "speed=${fix.speedKmh?.let { "%.1f".format(it) } ?: "未知"}km/h " +
                    "heading=${fix.headingDeg?.let { "%.0f".format(it) } ?: "未知"}° " +
                    "sat=$($fix.satellitesUsed)",
            )
        }
        return true
    }

    companion object {
        /** 「车机 GPS 回填」开关偏好键（`settings` SharedPreferences，默认开）。 */
        const val PREF_KEY_ENABLED = "carlife_use_car_gps"

        /** 偏好文件名：与 link_plus 其它 CarLife 开关共用 `settings`。 */
        private const val PREFS_NAME = "settings"

        /** 输出最小间隔：Android Location 常比 1Hz 更密，CarLife 侧也是 ~1Hz 消费。 */
        const val MIN_INTERVAL_MS = 1_000L

        private const val GPS_THREAD_NAME = "carlife-gps"

        /** 开关关闭时的丢弃日志限频。 */
        private const val DISABLED_LOG_INTERVAL_MS = 60_000L

        private fun prefs(context: Context) = (context.applicationContext ?: context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /** 开关当前是否开启（默认开；UI 切换后下一帧定位立即生效）。 */
        fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(PREF_KEY_ENABLED, true)

        /** 写开关（供设置 UI 调用；Bridge 运行中也会读到新值）。 */
        fun setEnabled(
            context: Context,
            enabled: Boolean,
        ) {
            prefs(context).edit { putBoolean(PREF_KEY_ENABLED, enabled) }
        }
    }
}
