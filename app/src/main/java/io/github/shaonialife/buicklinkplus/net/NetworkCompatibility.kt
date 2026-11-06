package io.github.shaonialife.buicklinkplus.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.InetAddress

/** 入网前读取已连接热点快照，不依赖后续回调 */
@Suppress("DEPRECATION")
internal fun ConnectivityManager.existingNetworks(): Array<Network> = allNetworks

/**
 * 新系统读取多地址公开接口，Android 12 和 13 保留原单地址接口
 */
internal fun NsdServiceInfo.compatHost(): InetAddress? {
    if (Build.VERSION.SDK_INT >= 34) return hostAddresses.firstOrNull()
    @Suppress("DEPRECATION")
    val legacy = host
    return legacy
}
