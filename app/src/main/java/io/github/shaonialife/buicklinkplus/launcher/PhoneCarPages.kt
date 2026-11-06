package io.github.shaonialife.buicklinkplus.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.edit

/** 记录手机版应用内的车载入口，与独立车载包分开 */
internal object PhoneCarPages {
    const val QQ_MUSIC = "com.tencent.qqmusic"
    private const val QQ_CAR_PAGE = "com.tencent.qqmusic.business.carwith.ui.CarWithPlayerActivity"
    private const val UCAR_ACTION = "com.ucar.intent.action.UCAR"
    private const val UCAR_CATEGORY = "com.ucar.intent.category.UCAR"
    private const val QQ_CERTIFICATE_SHA256 = "BF3DB8C6CBBF11104EB62FC20E9DFB27B37445D84BDB529241ED498B0044FA1E"
    private const val PREFS_NAME = "settings"
    private const val KEY_QQ_CAR_PAGE = "qq_music_car_page"
    private val qqCertificate =
        ByteArray(32) { index ->
            QQ_CERTIFICATE_SHA256.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    data class Channel(
        val title: String,
        val packageName: String,
        val status: String,
    )

    val channels =
        listOf(
            Channel("高德地图", "com.autonavi.minimap", "手机版含 UCAR 地图页面；在“地图与交接”中选择显示模式"),
            Channel("百度地图", "com.baidu.BaiduMap", "手机版含荣耀 HiCar 地图入口；当前车机链路不是 HiCar 接收端"),
            Channel("QQ 音乐", QQ_MUSIC, "手机版含 UCAR／CarWith 播放页面；可在下方选择试用"),
            Channel("酷狗音乐", "com.kugou.android", "手机版含 HiCar 入口；当前车机链路不是 HiCar 接收端"),
            Channel("酷我音乐", "cn.kuwo.player", "未核实到可由当前链路直接打开的车载页面"),
            Channel("网易云音乐", "com.netease.cloudmusic", "手机版含 UCAR 媒体浏览服务；需要相应平台宿主，当前不能显示其车载页面"),
            Channel("抖音", "com.ss.android.ugc.aweme", "未核实到可由当前链路直接打开的车载视频页面"),
            Channel("腾讯视频", "com.tencent.qqlive", "未核实到可由当前链路直接打开的车载视频页面"),
            Channel("爱奇艺", "com.qiyi.video", "未核实到可由当前链路直接打开的车载视频页面"),
            Channel("优酷", "com.youku.phone", "未核实到可由当前链路直接打开的车载视频页面"),
            Channel("芒果 TV", "com.hunantv.imgo.activity", "未核实到可由当前链路直接打开的车载视频页面"),
        )

    /** 只接受安装包声明的 UCAR 入口和已核对页面 */
    fun qqCarComponent(context: Context): ComponentName? {
        val pm = context.packageManager
        val intent = Intent(UCAR_ACTION).addCategory(UCAR_CATEGORY).setPackage(QQ_MUSIC)
        return runCatching {
            if (!pm.hasSigningCertificate(QQ_MUSIC, qqCertificate, PackageManager.CERT_INPUT_SHA256)) return@runCatching null
            pm
                .queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo }
                .filter {
                    it.packageName == QQ_MUSIC &&
                        it.name == QQ_CAR_PAGE &&
                        it.enabled &&
                        it.exported &&
                        it.applicationInfo.enabled &&
                        it.permission.isNullOrBlank()
                }.map { ComponentName(it.packageName, it.name) }
                .distinct()
                .singleOrNull()
        }.getOrNull()
    }

    /** 车载页面必须由用户主动开启，普通图标默认仍启动手机版 */
    fun qqCarEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_QQ_CAR_PAGE, false)

    /** 保存 QQ 音乐同包页面的显示选择 */
    fun setQqCarEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_QQ_CAR_PAGE, enabled)
            }
    }

    /** 只有已核对的实际活动页面可以记为 QQ 车载页面 */
    fun isQqCarPage(page: String?): Boolean = page == QQ_CAR_PAGE

    /** 使用应用声明的 UCAR 动作，不附加私有参数 */
    fun qqCarArguments(): String = " -a $UCAR_ACTION -c $UCAR_CATEGORY"
}
