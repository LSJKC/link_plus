package io.github.shaonialife.buicklinkplus.launcher.settings

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.util.Bg

/** 设置页轻提示接口，车机使用页内提示，手机使用系统 Toast */
interface SettingsToastHost {
    fun showToast(text: String)
}

/** 手机设置宿主标记，供共享面板选择布局 */
interface PhoneSettingsHost

/** 设置面板基类，共用应用缓存和轻提示；缓存缺失时异步扫描 */
abstract class SettingsPanelFragment : Fragment() {
    protected val isPhonePanel: Boolean
        get() =
            activity is PhoneSettingsHost &&
                !io.github.shaonialife.buicklinkplus.ui.UiAppearance
                    .isCar(requireContext())

    var apps: List<VirtualLauncher.AppEntry> = emptyList()
        private set

    private var requestGeneration = 0L
    private var reload: (() -> Unit)? = null
    private var registered = false
    private val changes =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "desktopApps" || key?.startsWith("ss_") == true || key?.startsWith("force_") == true) reload?.invoke()
        }
    private val packagesChanged: () -> Unit = { reload?.invoke() }

    /** 清单就绪回调（主线程；缓存命中时同步回调）。 */
    protected fun loadApps(onReady: () -> Unit) {
        val root = view ?: return
        val appContext = root.context.applicationContext
        val request = ++requestGeneration
        val version = VirtualLauncher.appsCacheVersion
        VirtualLauncher.peekCachedApps()?.let {
            apps = it
            onReady()
            return
        }
        Bg.run("apps-scan") {
            val scanned = VirtualLauncher.cachedApps(appContext)
            root.post {
                if (view !== root || !isAdded || requestGeneration != request) return@post
                if (VirtualLauncher.appsCacheVersion != version) {
                    loadApps(onReady)
                    return@post
                }
                apps = scanned
                onReady()
            }
        }
    }

    /**
     * 无列表适配器的面板也按可见生命周期重新对账
     */
    protected fun reloadWhenVisible(action: () -> Unit) {
        reload = action
        action()
    }

    /** 安装列表布局、字母导航和异步加载状态 */
    protected fun setupAppList(
        container: View,
        listId: Int,
        indexBarId: Int,
        adapter: AppRowsAdapter,
        onAppsReady: () -> Unit = {},
    ) {
        val list = container.findViewById<RecyclerView>(listId)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        SettingsRows.setupIndexBar(this, container.findViewById<LinearLayout>(indexBarId), list) { adapter.rows }
        val loading = container.findViewById<TextView>(R.id.listLoading)
        reload = {
            loadApps {
                loading.visibility = View.GONE
                onAppsReady()
                adapter.refreshRows()
            }
        }
        reload?.invoke()
    }

    /**
     * 仅可见面板接收应用和桌面选择变化，缓存页面再次显示时重新对账
     */
    override fun onResume() {
        super.onResume()
        if (!registered) {
            val context = requireContext()
            context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changes)
            context
                .getSharedPreferences(
                    "app_orientation",
                    android.content.Context.MODE_PRIVATE,
                ).registerOnSharedPreferenceChangeListener(changes)
            VirtualLauncher.addAppsListener(packagesChanged)
            registered = true
        }
        reload?.invoke()
    }

    /**
     * 隐藏页面停止接收刷新，禁止旧加载回调覆盖新的视图
     */
    override fun onPause() {
        requestGeneration++
        if (registered) {
            VirtualLauncher.removeAppsListener(packagesChanged)
            requireContext()
                .getSharedPreferences(
                    "settings",
                    android.content.Context.MODE_PRIVATE,
                ).unregisterOnSharedPreferenceChangeListener(changes)
            requireContext()
                .getSharedPreferences(
                    "app_orientation",
                    android.content.Context.MODE_PRIVATE,
                ).unregisterOnSharedPreferenceChangeListener(changes)
            registered = false
        }
        super.onPause()
    }

    /**
     * 销毁页面时使异步回调失效并释放列表适配器
     */
    override fun onDestroyView() {
        requestGeneration++
        reload = null
        super.onDestroyView()
    }

    protected fun hostToast(text: String) {
        val host = activity
        if (host is SettingsToastHost) {
            host.showToast(text)
        } else {
            android.widget.Toast
                .makeText(requireContext(), text, android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        onViewCreated(view)
    }

    /** onViewCreated 简化版（本页无状态恢复需求）。 */
    protected abstract fun onViewCreated(view: View)
}
