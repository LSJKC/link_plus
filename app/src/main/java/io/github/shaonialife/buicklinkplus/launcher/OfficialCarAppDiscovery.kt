package io.github.shaonialife.buicklinkplus.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/** 读取已安装车载应用的桌面入口，未安装时不生成入口 */
data class InstalledCarApp(
    val definition: OfficialCarApps.Definition,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val signatureMatches: Boolean = false,
    val component: ComponentName? = null,
    val problem: String? = null,
) {
    val canAddToDesktop: Boolean get() = signatureMatches && component != null
}

/**
 * 包与证书读取仅供后台调用，包更新后重新读取，不永久缓存可用结论
 */
object OfficialCarAppDiscovery {
    /**
     * 只读取已安装版本和可用的真实桌面入口，多入口无法确认时不猜测组件
     */
    @Suppress("DEPRECATION")
    fun scan(
        context: Context,
        apps: List<VirtualLauncher.AppEntry>,
    ): List<InstalledCarApp> {
        val pm = context.packageManager
        return OfficialCarApps.entries.map { definition ->
            try {
                val info = pm.getPackageInfo(definition.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signing = info.signingInfo
                val certificates =
                    if (signing?.hasMultipleSigners() == true) {
                        // 本轮只核对过单签名发行包，多签名身份需要重新核验
                        null
                    } else {
                        signing?.signingCertificateHistory
                    }
                val digests =
                    certificates
                        .orEmpty()
                        .map { signature ->
                            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02X".format(it) }
                        }.toSet()
                val candidates = apps.filter { it.component.packageName == definition.packageName }.map { it.component }
                val preferred = pm.getLaunchIntentForPackage(definition.packageName)?.component
                val component = preferred?.takeIf { it in candidates } ?: candidates.singleOrNull()
                val signatureMatches = definition.matchesCertificates(digests)
                InstalledCarApp(
                    definition,
                    info.versionName.orEmpty(),
                    info.longVersionCode,
                    signatureMatches,
                    component,
                    when {
                        !signatureMatches -> "签名与已核对的官网包不一致，请核对安装来源"
                        component == null -> "未找到唯一可用的桌面入口，请检查应用是否被停用"
                        else -> null
                    },
                )
            } catch (_: PackageManager.NameNotFoundException) {
                InstalledCarApp(definition, problem = "未安装，请先在手机上按条目说明从官网安装")
            } catch (_: Exception) {
                InstalledCarApp(definition, problem = "暂时无法读取安装信息，请重试")
            }
        }
    }
}
