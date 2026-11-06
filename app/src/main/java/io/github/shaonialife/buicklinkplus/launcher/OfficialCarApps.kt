package io.github.shaonialife.buicklinkplus.launcher

/** 支持的官方车载包，入口从当前安装信息读取 */
object OfficialCarApps {
    enum class Category(
        val title: String,
    ) {
        MUSIC("音乐"),
        MAP("地图"),
    }

    data class Definition(
        val packageName: String,
        val title: String,
        val website: String,
        val certificateSha256: String,
        val observedVersionCode: Long,
        val note: String,
        val category: Category = Category.MUSIC,
        val independentCar: Boolean = true,
        val contentObserved: Boolean = true,
    ) {
        /**
         * 核对当前签名或系统确认的签名轮换历史，不凭包名认定官方来源
         */
        fun matchesCertificates(certificates: Set<String>): Boolean = certificates.any { it.equals(certificateSha256, ignoreCase = true) }
    }

    val entries =
        listOf(
            Definition(
                "com.tencent.qqmusiccar",
                "QQ 音乐（车载版）",
                "https://y.qq.com/download/download.html",
                "BF3DB8C6CBBF11104EB62FC20E9DFB27B37445D84BDB529241ED498B0044FA1E",
                3160002L,
                "官网选择 Android 车载版。公版限个人消费使用，登录与会员按车载版规则办理。",
            ),
            Definition(
                "com.kugou.android.auto",
                "酷狗音乐（车载版）",
                "https://download.kugou.com/",
                "F82FCBAD134685BFD459F937066995043CD11FC0F96588E24BA3D04A7563BCF1",
                10800L,
                "官网选择酷狗音乐车载版，安装后单独登录。",
            ),
            Definition(
                "com.netease.cloudmusic.iot",
                "网易云音乐（车载版）",
                "https://car.music.163.com/",
                "54254D2BE09DAEF48DEDC2B4A4F497D153E14ED9D70814FC9C360EE9240827F7",
                3005012L,
                "已观察版本为官网历史车载包 3.5.12，当前下载以官网为准；账号和权益按车载版规则办理。",
            ),
            Definition(
                "com.autonavi.amapauto",
                "高德地图（独立车载版）",
                "https://www.amapauto.com/download",
                "B36C0B80CB2417E7FE4C3DF01A2D163A7D8443604DD276A2BC1AB0D325102E3E",
                26000001L,
                "已核对官网 9.5.0.600013 的包、签名和导出入口。先在手机完成首次配置，虚拟屏导航、账号和声音仍需实际验证。",
                Category.MAP,
                contentObserved = false,
            ),
            Definition(
                "com.baidu.BaiduMap",
                "百度地图（手机版·普通任务）",
                "https://map.baidu.com/",
                "A6EF817BFD6C083442A149856E51036F6912C2DB6B6009DB8127CDD641E295A9",
                1665L,
                "已核对官网手机版 22.1.20；使用普通地图任务交接，不能等同于独立车载版。百度汽车版下载当前拒绝访问，未将其登记为已核验包。",
                Category.MAP,
                independentCar = false,
                contentObserved = false,
            ),
        )

    /**
     * 手机版和车载版使用不同包名，不替换用户原有图标或应用偏好
     */
    fun find(packageName: String): Definition? = entries.firstOrNull { it.packageName == packageName }

    /**
     * 在桌面与应用列表中明确区分同品牌的手机版和独立车载版
     */
    fun displayLabel(
        packageName: String,
        originalLabel: String,
    ): String = find(packageName)?.title ?: originalLabel
}
