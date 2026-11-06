/*
 * Ported from hybrid63 inject/src/tl/CarLifeNavAmap.java (this project's own prior injected implementation).
 * AIDL interfaces copied from hybrid63 inject/src/com/amap/linksdk/aidldefine/ — see
 * app/src/main/aidl/com/amap/linksdk/aidldefine/{ILinkSdkControl,ILinkSdkCallback}.aidl
 * (包名必须保持 com.amap.linksdk.aidldefine，否则跨进程 binder descriptor 对不上).
 */

package io.github.shaonialife.buicklinkplus.carlife

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.amap.linksdk.aidldefine.ILinkSdkCallback
import com.amap.linksdk.aidldefine.ILinkSdkControl
import org.json.JSONArray
import org.json.JSONObject

/**
 * 高德 AMap LinkSDK 结构化导航源（P4 可选第二数据源）。
 *
 * 背景：CarLife `0x10030` 只对 CarLife 内置百度导航有效；手机里用高德导航时 CarLife 不发 `0x10030`。
 * 旧工程（hybrid63）因此接了 AMap LinkSDK（车机/眼镜用的进程间接口）：绑定高德 `AmapLinkService` →
 * `authenticate()` → `registerListener()` → 每 1250ms 轮换 `execute(cmd=5/7)`，从回调 JSON 里解析出
 * 机动、剩余距离/时间，再交给 [CarLifeNav] 编码成 iAP2 `0x5201/0x5202`。
 *
 * 真机依据：旧工程审计记录「导航：amap connected/register；nav iAP2 0x5201」；绑定参数原样保留
 * （API key 留空也能连上，旧工程实测如此）。
 *
 * 使用：
 * ```
 * CarLifeNavAmap.log = ::appendLog
 * CarLifeNavAmap.onHud = { turn, remain, total, road -> CarLifeNav.submitHud(turn, remain, total, road) }
 * CarLifeNavAmap.onStop = { CarLifeNav.stop() }
 * CarLifeNavAmap.start(context)   // 车机 0x5200 订阅到达后
 * CarLifeNavAmap.stop()           // 0x5203 / 断链
 * ```
 *
 * ## 注意/限制
 *
 * - 这是**可选**能力：高德没装、版本不含该服务、或系统限制后台绑定时只会打一行日志，不影响其它链路。
 * - 目标 SDK 34 的包可见性：为稳定命中 `com.autonavi.minimap`，正式接线时建议在 AndroidManifest 加
 *   `<queries><package android:name="com.autonavi.minimap" /></queries>`；本类不依赖包可见性判断，
 *   直接 `bindService`（显式组件），拿不到服务时由 `bindService` 返回 false。
 * - 回调线程是高德 binder 线程，[onHud] 里如果要做主线程 UI 工作请自行切线程；[CarLifeNav.submitHud]
 *   与 iAP2 发送侧都是线程安全的。
 * - 尚未实车验证（当前无设备）；先保证逻辑与旧工程一致、可编译，字段解析失败只记日志不抛。
 */
object CarLifeNavAmap {
    private const val TAG = "CarLifeNavAmap"
    private const val PKG = "com.autonavi.minimap"
    private const val SERVICE = "com.amap.bundle.wearable.connect.glass.AmapLinkService"
    private const val POLL_INTERVAL_MS = 1_250L
    private const val MAX_CALLBACK_CHARS = 1 shl 20

    @Volatile
    private var running = false

    @Volatile
    private var connected = false

    @Volatile
    private var registered = false

    private var appContext: Context? = null
    private var control: ILinkSdkControl? = null
    private var token: String? = null
    private var cmd = 5
    private var callback: ILinkSdkCallback? = null
    private var handler: Handler? = null
    private var poll: Runnable? = null

    /** 导航 HUD 快照回调（turn = BOX OFF HUD turn 索引，remain/total 为米）。 */
    @Volatile
    var onHud: ((turn: Int, remainMeters: Int, totalMeters: Int, road: String) -> Unit)? = null

    /** 导航结束回调（到达目的地 / naviState=0）。 */
    @Volatile
    var onStop: (() -> Unit)? = null

    /** 诊断日志出口（可选）。 */
    @Volatile
    var log: ((String) -> Unit)? = null

    /** 是否已启动（绑定流程进行中或已连上）。 */
    val isRunning: Boolean get() = running

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?,
            ) {
                try {
                    val remote = ILinkSdkControl.Stub.asInterface(binder) ?: return
                    control = remote
                    diag("amap connected $name")
                    token = parseToken(remote.authenticate())
                    val currentCallback = callback
                    registered = currentCallback != null && remote.registerListener(currentCallback)
                    connected = true
                    diag("amap auth token=${token != null} register=$registered")
                    startPoll()
                } catch (error: Throwable) {
                    diag("amap service init failed: $error")
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                handleLinkLost("amap disconnected")
            }

            override fun onBindingDied(name: ComponentName?) {
                handleLinkLost("amap binding died")
            }
        }

    /** 绑定高德 LinkSDK；重复调用无副作用。 */
    @Synchronized
    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext ?: context
        appContext = app
        handler = Handler(Looper.getMainLooper())
        callback = createCallback()
        running = true
        try {
            val intent =
                Intent().apply {
                    component = ComponentName(PKG, SERVICE)
                    putExtra("com.amap.linksdk.extra.API_KEY", "")
                    putExtra("com.amap.linksdk.extra.CLIENT_VERSION", "1.0.0")
                    putExtra(
                        "com.amap.linksdk.extra.API_KEY_AUTH_TS",
                        System.currentTimeMillis().toString(),
                    )
                    putExtra("com.amap.linksdk.extra.API_KEY_AUTH_SCODE", "")
                    putExtra("com.amap.linksdk.extra.API_KEY_AUTH_DOMAIN", app.packageName)
                }
            val bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            diag("amap bind=$bound")
            if (!bound) running = false
        } catch (error: Throwable) {
            running = false
            diag("amap bind failed: $error")
        }
    }

    /** 解绑并停止轮询；重复调用无副作用。 */
    @Synchronized
    fun stop() {
        running = false
        val currentHandler = handler
        val currentPoll = poll
        if (currentHandler != null && currentPoll != null) {
            try {
                currentHandler.removeCallbacks(currentPoll)
            } catch (_: Throwable) {
            }
        }
        try {
            val currentCallback = callback
            val currentControl = control
            if (registered && currentCallback != null && currentControl != null) {
                currentControl.unregisterListener(currentCallback)
            }
        } catch (_: Throwable) {
        }
        try {
            appContext?.unbindService(connection)
        } catch (_: Throwable) {
        }
        control = null
        connected = false
        registered = false
        poll = null
        handler = null
        callback = null
        appContext = null
    }

    private fun handleLinkLost(reason: String) {
        connected = false
        registered = false
        control = null
        diag(reason)
    }

    private fun createCallback(): ILinkSdkCallback =
        object : ILinkSdkCallback.Stub() {
            override fun onAmapCallback(data: String?) {
                try {
                    onAmapJson(data)
                } catch (error: Throwable) {
                    diag("amap callback parse failed: $error")
                }
            }

            override fun onAmapCallbackWithFd(
                data: String?,
                fd: ParcelFileDescriptor?,
            ) {
                try {
                    fd?.close()
                } catch (_: Throwable) {
                }
                try {
                    onAmapJson(data)
                } catch (_: Throwable) {
                }
            }
        }

    private fun startPoll() {
        val currentHandler = handler ?: return
        if (!running) return
        val runnable = poll ?: createPoll().also { poll = it }
        currentHandler.removeCallbacks(runnable)
        currentHandler.post(runnable)
    }

    private fun createPoll(): Runnable =
        object : Runnable {
            override fun run() {
                val currentHandler = handler
                if (currentHandler == null || !running || !connected || control == null) return
                try {
                    val body =
                        JSONObject().apply {
                            put("cmd", cmd)
                            put("requestId", SystemClock.elapsedRealtime().toString())
                            put("data", JSONObject())
                            token?.let { put("token", it) }
                        }
                    control?.execute(body.toString())
                    cmd = if (cmd == 5) 7 else 5
                } catch (error: Throwable) {
                    diag("amap poll failed: $error")
                }
                if (running) currentHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

    private fun parseToken(auth: String?): String? {
        if (auth == null) return null
        return try {
            val root = JSONObject(auth)
            val data = root.optJSONObject("data")
            if (root.optString("status") != "success" || data == null) {
                null
            } else {
                data.optString("token").takeIf { it.isNotEmpty() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun onAmapJson(raw: String?) {
        if (raw == null || raw.isEmpty() || raw.length > MAX_CALLBACK_CHARS) return
        val root = JSONObject(raw)
        val datas =
            when (val value = root.opt("datas")) {
                is JSONArray -> value
                is String -> JSONArray(value)
                else -> return
            }

        var action = -1
        var routeRemainDist = -1L
        var routeRemainTime = -1L
        var segmentRemainDist = -1L
        var curRoad = ""
        var nextRoad = ""
        var arrived = false
        var naviState = -1

        for (index in 0 until minOf(datas.length(), 256)) {
            val item = datas.optJSONObject(index) ?: continue
            val type = item.optInt("type", -1)
            var parsedData = item.optJSONObject("data")
            if (parsedData == null) {
                val value = item.opt("data")
                if (value is String) {
                    parsedData =
                        try {
                            JSONObject(value)
                        } catch (_: Throwable) {
                            null
                        }
                }
            }
            val data = parsedData ?: continue
            when (type) {
                4 -> {
                    routeRemainDist = num(data, "routeRemainDist", routeRemainDist)
                    routeRemainTime = num(data, "routeRemainTime", routeRemainTime)
                    segmentRemainDist = num(data, "segmentRemainDist", segmentRemainDist)
                    val maneuver = data.optInt("curManeuverID", -1)
                    if (maneuver >= 0) action = maneuver
                    val currentRoad = data.optString("curRouteName", "")
                    val followingRoad = data.optString("nextRouteName", "")
                    if (currentRoad.isNotEmpty()) curRoad = currentRoad
                    if (followingRoad.isNotEmpty()) nextRoad = followingRoad
                }

                5 -> {
                    val maneuver = data.optInt("maneuverID", -1)
                    if (maneuver >= 0) action = maneuver
                }

                200 -> {
                    naviState = data.optInt("naviState", naviState)
                    arrived = data.optBoolean("isArrived", arrived)
                }

                21 -> {
                    val report = data.optJSONObject("reportNaviData")
                    var infos = report?.optJSONArray("navi_structured_info")
                    if (infos == null) {
                        val text = report?.optString("navi_structured_info", "") ?: ""
                        if (text.isNotEmpty()) {
                            infos =
                                try {
                                    JSONArray(text)
                                } catch (_: Throwable) {
                                    null
                                }
                        }
                    }
                    if (infos != null) {
                        for (entryIndex in 0 until infos.length()) {
                            val entry = infos.optJSONObject(entryIndex) ?: continue
                            val key = entry.optString("actionKey")
                            val value = entry.optLong("actionValue", -1L)
                            when (key) {
                                "remain_dist" -> routeRemainDist = value
                                "remain_eta" -> routeRemainTime = value
                                "next_action" -> if (action < 0) action = value.toInt()
                            }
                        }
                    }
                }
            }
        }

        if (arrived || naviState == 0) {
            onStop?.invoke()
            diag("amap arrived/naviState=$naviState")
            return
        }
        if (action < 0 || routeRemainDist <= 0) return
        val remain =
            (if (segmentRemainDist >= 0) segmentRemainDist else routeRemainDist)
                .coerceAtLeast(0)
                .toInt()
        val total = routeRemainDist.coerceAtLeast(0).toInt()
        if (total <= 0) return
        val road = if (curRoad.isNotEmpty()) curRoad else nextRoad
        onHud?.invoke(mapHudTurn(action), remain, total, road)
        diag("amap nav action=$action remain=$remain total=$total road=$road etaSec=$routeRemainTime")
    }

    private fun num(
        data: JSONObject,
        key: String,
        fallback: Long,
    ): Long {
        val value = data.opt(key) ?: return fallback
        return try {
            value.toString().toLong()
        } catch (_: Throwable) {
            fallback
        }
    }

    /** AMap maneuverID → BOX OFF HUD turn 索引（原样移植旧工程 mapHudTurn）。 */
    private fun mapHudTurn(action: Int): Int =
        when (action) {
            2, 3 -> 7 // 左
            4, 5 -> 3 // 右
            6, 7, 8 -> 14 // 环岛
            19 -> 5 // 掉头
            65, 66 -> 24 // 到达
            else -> 1 // 直行
        }

    private fun diag(message: String) {
        log?.invoke("$TAG: $message")
    }
}
