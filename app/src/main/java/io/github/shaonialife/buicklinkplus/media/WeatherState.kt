package io.github.shaonialife.buicklinkplus.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL

/** 通过 Open-Meteo 获取当前天气，每 30 分钟后台刷新；失败保留旧数据 */
internal object WeatherState {
    /** 当前天气：城市名、摄氏温度、天气描述 */
    data class Weather(
        val city: String,
        val temperatureC: Double,
        val text: String,
    )

    /** 城市预设（显示名 + 经纬度），设置页供用户选择 */
    data class City(
        val name: String,
        val latitude: Double,
        val longitude: Double,
    )

    val presetCities =
        listOf(
            City("北京", 39.90, 116.40),
            City("上海", 31.23, 121.47),
            City("广州", 23.13, 113.26),
            City("深圳", 22.54, 114.06),
            City("成都", 30.57, 104.07),
            City("杭州", 30.27, 120.15),
            City("武汉", 30.59, 114.31),
            City("西安", 34.34, 108.94),
        )

    /** 天气位置的「当前定位」选项值 */
    const val AUTO = "auto"

    @Volatile
    var current: Weather? = null
        private set

    /** 当前城市最近一次刷新是否失败，保留旧数据 */
    @Volatile
    var lastRefreshFailed: Boolean = false
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    private val refreshState = WeatherRefreshState()

    @Volatile
    var updatedAt: Long = 0L
        private set

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 选项的展示名：auto 显示「当前定位」 */
    fun label(choice: String): String = if (choice == AUTO) "当前定位" else choice

    /**
     * 首次立即刷新并按城市合并后台请求
     */
    fun ensureFresh(
        context: Context,
        choice: String,
        elapsed: Long,
    ) {
        val request = refreshState.begin(choice, elapsed) ?: return
        fetchRequests(context.applicationContext, request)
    }

    /**
     * 显式刷新绕过间隔并保留最新城市选择
     */
    fun refresh(
        context: Context,
        choice: String,
    ) {
        val request = refreshState.begin(choice, android.os.SystemClock.elapsedRealtime(), force = true) ?: return
        fetchRequests(context.applicationContext, request)
    }

    /** 顺序处理合并请求，忽略旧城市响应 */
    private fun fetchRequests(
        context: Context,
        first: WeatherRefreshState.Request,
    ) {
        io.github.shaonialife.buicklinkplus.util.Bg.run("weather") {
            var request: WeatherRefreshState.Request? = first
            while (request != null) {
                val active = request
                val fetched = runCatching { fetch(resolvePlace(context, active.choice)) }.getOrNull()
                val now = android.os.SystemClock.elapsedRealtime()
                var changed = false
                refreshState.complete(active, now) {
                    lastRefreshFailed = fetched == null
                    if (fetched != null) {
                        updatedAt = now
                        changed = fetched != current
                        current = fetched
                    }
                }
                if (changed) listeners.forEach { runCatching { it() } }
                request = refreshState.next(now)
            }
        }
    }

    /** auto 使用系统定位，不可用时回退默认城市；其他值使用预设城市 */
    fun resolvePlace(
        context: Context,
        choice: String,
    ): City {
        if (choice != AUTO) {
            return presetCities.firstOrNull { it.name == choice } ?: presetCities.first()
        }
        return currentPlace(context) ?: presetCities.first()
    }

    /** 最近一次系统定位（GPS 优先、网络补充），无权限或无缓存定位返回 null */
    fun currentPlace(context: Context): City? {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val manager = context.getSystemService(android.location.LocationManager::class.java) ?: return null
        val location =
            listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
            ).firstNotNullOfOrNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            } ?: return null
        return City(AUTO_PLACE_LABEL, location.latitude, location.longitude)
    }

    private const val AUTO_PLACE_LABEL = "当前定位"

    private fun fetch(city: City): Weather? =
        runCatching {
            val url =
                URL(
                    "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=${city.latitude}&longitude=${city.longitude}" +
                        "&current=temperature_2m,weather_code&timezone=auto",
                )
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            try {
                if (connection.responseCode != 200) return null
                val body = connection.inputStream.bufferedReader().readText()
                val current = org.json.JSONObject(body).getJSONObject("current")
                Weather(city.name, current.getDouble("temperature_2m"), describeCode(current.getInt("weather_code")))
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

    /** WMO 天气代码 → 中文描述（纯函数） */
    fun describeCode(code: Int): String =
        when (code) {
            0 -> "晴"
            1 -> "晴间多云"
            2 -> "多云"
            3 -> "阴"
            45, 48 -> "雾"
            in 51..67 -> "雨"
            in 71..77 -> "雪"
            in 80..82 -> "阵雨"
            85, 86 -> "阵雪"
            in 95..99 -> "雷雨"
            else -> "多云"
        }
}
