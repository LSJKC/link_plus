package io.github.shaonialife.buicklinkplus.carlife

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 本机 CarLife 引擎检测（**只读**：不启动/不强杀/不强制任何包名，不使用危险权限）。
 *
 * 用途：让设置页/诊断页回答三个问题——
 * 1. 这台手机上装的是哪种 CarLife/车联（荣耀车联、官方 CarLife+、小米 CarWith、OPPO 车联、vivo 车机、三星 CarLink…）；
 * 2. 引擎现在有没有进入「无线连接」态（本机回环 **7 个端口** 7240/8240/9240/9241/9242/9340/9440 是否监听）；
 * 3. 没连上时下一步该做什么。
 *
 * 连接原理（详见 `docs/carlife-mechanism.md`）：CarLife+ 在「无线连接」态是**本机 TCP 服务端**，
 * 本应用扮演**车机端（HU）**去连 `127.0.0.1` 的 7240 等端口，不修改/改包/接管 CarLife+ 进程。
 *
 * ## 包可见性（Android 11+）
 *
 * `detect()` 对**已知包名**用 `getPackageInfo`；对其它 CarLife 变体用 `queryIntentActivities`
 * 的 MAIN/LAUNCHER 结果按 label/包名模糊匹配。本仓库 AndroidManifest 已有
 * `<intent MAIN/LAUNCHER>` 的 `<queries>`，因此带桌面入口的车联/引擎 App 通常可见；
 * 若某版本引擎没有桌面入口，需要在 `<queries>` 里补 `<package android:name="…"/>`
 * （具体列表见 `docs/carlife-mechanism.md`）。
 *
 * ## 线程
 *
 * [portsListening]/[statusText]/[isAnyPortListening] 会做 TCP connect，最坏约
 * `端口数 × PORT_PROBE_TIMEOUT_MS`，**禁止在主线程调用**（放后台线程/Bg.run）。
 */
object CarLifeEngineDetector {
    /** 单个端口探测超时（毫秒）。 */
    const val PORT_PROBE_TIMEOUT_MS = 150

    /** 本机 CarLife 引擎端口 → 用途（与 [CarLifeProtocol] 的 7 个 PORT_* 常量一一对应）。 */
    private val PORTS: Map<Int, String> =
        linkedMapOf(
            7240 to "命令/握手",
            8240 to "H.264 视频",
            9240 to "媒体 PCM",
            9241 to "TTS PCM",
            9242 to "VR/语音",
            9340 to "触摸回传",
            9440 to "数据通道",
        )

    /** 检测到的 CarLife/车联条目。 */
    data class EngineInfo(
        /** 应用显示名（PackageManager 读到的 label；读不到时用已知名称兜底）。 */
        val displayName: String,
        /** 包名。 */
        val packageName: String,
        /** versionName；读不到为 null。 */
        val versionName: String?,
        /** longVersionCode；读不到为 0。 */
        val versionCode: Long,
        /** 变体类型。 */
        val kind: Kind,
    ) {
        /** 单行摘要，便于日志/设置页直接展示。 */
        fun summary(): String =
            buildString {
                append(displayName.ifBlank { packageName })
                versionName?.takeIf { it.isNotBlank() }?.let { append(" v").append(it) }
                if (versionCode > 0) append(" (").append(versionCode).append(')')
                append(" · ").append(packageName)
                append(" · ").append(kind.label)
            }
    }

    /** CarLife 变体（按 OEM 定制区分；荣耀是当前首要验证目标）。 */
    enum class Kind(
        val label: String,
    ) {
        HONOR("荣耀车联"),
        OFFICIAL("百度 CarLife+"),
        XIAOMI("小米 CarWith"),
        OPPO("OPPO 车联"),
        VIVO("vivo 车机"),
        SAMSUNG("三星 CarLink"),
        OTHER("其它 CarLife"),
    }

    /** 已知包名（来源：历史参考实现的 `tl/CarLifeLauncher.java` Flavor 表 + 百度 CarLife 常见 OEM 包名）。 */
    private data class Known(
        val packageName: String,
        val kind: Kind,
        val fallbackName: String,
    )

    private val KNOWN =
        listOf(
            // 荣耀：宿主（荣耀车联）优先，引擎包为 com.baidu.carlife.honor（实测 8.8.5 / CarLife+ 4.0.0）
            Known("com.hihonor.auto", Kind.HONOR, "荣耀车联（宿主）"),
            Known("com.honor.auto", Kind.HONOR, "荣耀车联（别名宿主）"),
            Known("com.baidu.carlife.honor", Kind.HONOR, "CarLife+（荣耀版）"),
            Known("com.baidu.carlife", Kind.OFFICIAL, "CarLife+"),
            Known("com.miui.carlink", Kind.XIAOMI, "小米 CarWith（宿主）"),
            Known("com.baidu.carlife.xiaomi", Kind.XIAOMI, "CarLife+（小米版）"),
            Known("com.oplus.ocar", Kind.OPPO, "OPPO 车联（宿主）"),
            Known("com.baidu.carlife.oppo", Kind.OPPO, "CarLife+（OPPO 版）"),
            Known("com.vivo.car.networking", Kind.VIVO, "vivo 车机（宿主）"),
            Known("com.baidu.carlife.vivo", Kind.VIVO, "CarLife+（vivo 版）"),
            Known("com.samsung.android.carlink", Kind.SAMSUNG, "三星 CarLink（宿主）"),
            Known("com.baidu.carlife.samsung", Kind.SAMSUNG, "CarLife+（三星版）"),
            Known("com.baidu.carlife.lenovo", Kind.OTHER, "CarLife+（联想版）"),
            Known("com.baidu.carlife.motorola", Kind.OTHER, "CarLife+（摩托罗拉版）"),
        )

    /** 品牌排序权重：荣耀优先，其次官方，再其它 OEM（同品牌内保持已知顺序/发现顺序）。 */
    private fun kindPriority(kind: Kind): Int =
        when (kind) {
            Kind.HONOR -> 0
            Kind.OFFICIAL -> 1
            Kind.XIAOMI -> 2
            Kind.OPPO -> 3
            Kind.VIVO -> 4
            Kind.SAMSUNG -> 5
            Kind.OTHER -> 6
        }

    /**
     * 检测本机所有 CarLife/车联相关包（已知包名 + label/包名模糊匹配），按荣耀优先去重排序。
     *
     * 读不到任何包（未安装 / Android 11+ 包可见性限制）时返回空列表，不抛异常。
     */
    fun detect(context: Context): List<EngineInfo> =
        try {
            val packageManager = context.packageManager
            val found = LinkedHashMap<String, EngineInfo>()
            // 1) 已知包名（引擎 + OEM 宿主 App）
            for (known in KNOWN) {
                val packageInfo = readPackageInfo(packageManager, known.packageName) ?: continue
                found[known.packageName] = toEngineInfo(packageManager, packageInfo, known)
            }
            // 2) 模糊匹配：桌面入口 label/包名含 CarLife，或 label 为荣耀车联
            for ((packageName, label) in launcherPackages(packageManager)) {
                if (found.containsKey(packageName)) continue
                val lowerLabel = label.lowercase()
                val matches =
                    lowerLabel.contains("carlife") ||
                        label.contains("荣耀车联") ||
                        packageName.contains("carlife")
                if (!matches) continue
                val packageInfo = readPackageInfo(packageManager, packageName)
                val kind =
                    when {
                        label.contains("荣耀车联") ||
                            packageName == "com.hihonor.auto" ||
                            packageName == "com.honor.auto" -> Kind.HONOR

                        else -> Kind.OTHER
                    }
                val known = Known(packageName, kind, label.ifBlank { packageName })
                found[packageName] =
                    if (packageInfo != null) {
                        toEngineInfo(packageManager, packageInfo, known)
                    } else {
                        EngineInfo(label.ifBlank { packageName }, packageName, null, 0L, kind)
                    }
            }
            found.values.sortedWith(compareBy<EngineInfo> { kindPriority(it.kind) })
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * 探测本机回环端口监听状态（**阻塞**，约 `端口数 × PORT_PROBE_TIMEOUT_MS`，禁止主线程调用）。
     *
     * 端口列表与顺序固定为 CarLife 引擎完整的 **7 个端口**：
     * 7240/8240/9240/9241/9242/9340/9440（与 [CarLifeProtocol] 的 PORT_* 常量一一对应）。
     *
     * 注意：**TCP 能连上 ≠ 协议已应答**。荣耀车的 CarLife+ 在未进入「无线连接」等待态时，
     * 端口也可能 accept 但 98305 版本协商不回（实车链路日志记录），最终以日志
     * `CARLIFE: carlife connected` 为准。
     */
    fun portsListening(): List<Pair<Int, Boolean>> = PORTS.keys.map { port -> port to isPortOpen(port) }

    /** 是否有任一 CarLife 端口监听（阻塞，禁止主线程调用）。 */
    fun isAnyPortListening(): Boolean = portsListening().any { it.second }

    /** 探测单个本机回环端口（阻塞 ≤ [timeoutMs]，禁止主线程调用）。 */
    fun isPortOpen(
        port: Int,
        timeoutMs: Int = PORT_PROBE_TIMEOUT_MS,
    ): Boolean =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }

    /** 多行状态摘要（已装引擎 + 版本 + 端口监听 + 下一步提示）。阻塞，禁止主线程调用。 */
    fun statusText(context: Context): String {
        val engines = detect(context)
        val ports = portsListening()
        return buildString {
            appendLine("CarLife 引擎检测")
            appendLine("———— 已安装 ————")
            if (engines.isEmpty()) {
                appendLine("未识别到 CarLife/车联应用（未安装，或 Android 11+ 包可见性受限）")
            } else {
                for (engine in engines) appendLine("• ${engine.summary()}")
            }
            appendLine("———— 本机端口（127.0.0.1）————")
            for ((port, open) in ports) {
                appendLine("${if (open) "✓" else "✗"} $port ${PORTS[port]}：${if (open) "监听中" else "未监听"}")
            }
            val listening = ports.count { it.second }
            appendLine("———— 结论 ————")
            when {
                listening >= 2 ->
                    appendLine(
                        "引擎进程正在监听（$listening 个端口）。若投屏仍连不上，请确认已进入 CarLife+ 的「无线连接」" +
                            "并停在「正在连接」界面，再回本应用点启动连接。",
                    )

                listening == 1 ->
                    appendLine("只检测到 1 个端口（引擎可能正在启动/退出），稍等几秒再刷新。")

                engines.isNotEmpty() ->
                    appendLine(
                        "已安装引擎但端口未监听：请打开「${openHint(engines)}」，按提示进入「无线连接」，" +
                            "停在「正在连接」界面，再回本应用重试。",
                    )

                else ->
                    appendLine(
                        "未检测到引擎或端口：荣耀手机请打开「荣耀车联 → CarLife → 无线连接」；" +
                            "其它手机请打开对应车联/CarLife+ 应用并进入「无线连接」。",
                    )
            }
            if (engines.isEmpty() && listening > 0) {
                append("（端口有监听但未列出包名：Android 11+ 包可见性所致，不影响连接。）")
            } else {
                append("提示：本检测只读，不会启动或强制任何应用；连接成功与否以日志 CARLIFE: carlife connected 为准。")
            }
        }
    }

    /** 给用户的打开入口提示（优先荣耀车联宿主的真实入口，其次官方 CarLife+）。 */
    private fun openHint(engines: List<EngineInfo>): String =
        when {
            engines.any { it.kind == Kind.HONOR } -> "荣耀车联 → CarLife"
            engines.any { it.kind == Kind.OFFICIAL } -> "CarLife+"
            engines.any { it.kind == Kind.XIAOMI } -> "小米 CarWith"
            engines.any { it.kind == Kind.OPPO } -> "OPPO 车联"
            engines.any { it.kind == Kind.VIVO } -> "vivo 车机"
            engines.any { it.kind == Kind.SAMSUNG } -> "三星 CarLink"
            else -> "手机上的 CarLife/车联应用"
        }

    private fun toEngineInfo(
        packageManager: PackageManager,
        packageInfo: PackageInfo,
        known: Known,
    ): EngineInfo {
        val label =
            packageInfo.applicationInfo
                ?.let { applicationInfo -> packageManager.getApplicationLabel(applicationInfo).toString().trim() }
                .orEmpty()
        return EngineInfo(
            displayName = label.ifBlank { known.fallbackName },
            packageName = known.packageName,
            versionName = packageInfo.versionName?.takeIf { it.isNotBlank() },
            versionCode = packageInfo.longVersionCode,
            kind = known.kind,
        )
    }

    private fun readPackageInfo(
        packageManager: PackageManager,
        packageName: String,
    ): PackageInfo? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                readPackageInfoLegacy(packageManager, packageName)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }

    @Suppress("DEPRECATION")
    private fun readPackageInfoLegacy(
        packageManager: PackageManager,
        packageName: String,
    ): PackageInfo = packageManager.getPackageInfo(packageName, 0)

    /** 桌面入口应用（MAIN/LAUNCHER）：包名 → 显示名。 */
    private fun launcherPackages(packageManager: PackageManager): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    queryIntentActivitiesLegacy(packageManager, intent)
                }
            } catch (_: Exception) {
                emptyList()
            }
        return resolveInfos.mapNotNull { resolveInfo ->
            val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
            val label =
                resolveInfo
                    .loadLabel(packageManager)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            packageName to label
        }
    }

    @Suppress("DEPRECATION")
    private fun queryIntentActivitiesLegacy(
        packageManager: PackageManager,
        intent: Intent,
    ): List<android.content.pm.ResolveInfo> = packageManager.queryIntentActivities(intent, 0)
}
