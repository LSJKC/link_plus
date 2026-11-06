package io.github.shaonialife.buicklinkplus.launcher

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.shaonialife.buicklinkplus.PhoneSettingsActivity
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.launcher.settings.SettingsMenus
import io.github.shaonialife.buicklinkplus.launcher.settings.SettingsToastHost
import io.github.shaonialife.buicklinkplus.ui.GlassActivity
import io.github.shaonialife.buicklinkplus.ui.setAppearanceBackground
import io.github.shaonialife.buicklinkplus.ui.setAppearanceImageResource
import io.github.shaonialife.buicklinkplus.ui.setAppearanceImageTint
import io.github.shaonialife.buicklinkplus.ui.setAppearanceTextColor

/** 车机双栏设置宿主，右侧 Fragment 与手机设置共用 */
class SettingsActivity :
    GlassActivity(),
    SettingsToastHost {
    private var toastView: TextView? = null
    private val menus = mutableListOf<LinearLayout>()
    private var currentMenu = -1
    private var currentPanel = -1

    // 触摸监听仅阻止父容器拦截并返回 false，点击仍由 btnBack 的 click 监听处理
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VirtualLauncher.dockAtBottom) {
            // 竖屏窄屏：双栏挤压——转发手机侧二级菜单（列表 → 内容页）
            startActivity(
                android.content.Intent(
                    this,
                    PhoneSettingsActivity::class.java,
                ),
            )
            finish()
            return
        }
        setContentView(R.layout.activity_settings)
        // 车机虚拟屏内隐藏系统栏，按钮仍避让实际窗口 Insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val root = findViewById<View>(R.id.settingsRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { target, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        findViewById<TextView>(R.id.btnBack).setOnTouchListener { button, _ ->
            button.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        toastView = findViewById(R.id.settingsToast)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { close() }

        collectMenus()
        if (savedInstanceState == null) {
            showMenu(0)
        } else {
            currentMenu = savedInstanceState.getInt("currentMenu", 0).coerceIn(SettingsMenus.VISIBLE.indices)
            applyMenuStyle(currentMenu)
            val restored = supportFragmentManager.fragments.firstOrNull { it.id == R.id.settingsContainer && !it.isHidden }
            currentPanel = restored?.tag?.substringAfter("settings:")?.toIntOrNull() ?: SettingsMenus.VISIBLE[currentMenu].id
            openPanel(currentPanel)
        }
    }

    /**
     * 菜单按共享分组创建，窄高车机允许滚动
     */
    private fun collectMenus() {
        val column = findViewById<LinearLayout>(R.id.settingsMenu)
        val back = findViewById<TextView>(R.id.btnBack)
        column.removeAllViews()
        column.addView(back)
        val entries = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(android.widget.ScrollView(this).apply { addView(entries) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val density = resources.displayMetrics.density
        val iconSize = (23 * density).toInt()
        val iconPadding = (5 * density).toInt()
        SettingsMenus.VISIBLE.forEachIndexed { index, item ->
            val row =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val padding = (12 * density).toInt()
                    setPadding(padding, padding, padding, padding)
                    addView(
                        ImageView(this@SettingsActivity).apply {
                            setAppearanceBackground(R.drawable.bg_menu_chip)
                            setAppearanceImageResource(item.iconRes)
                            setAppearanceImageTint(R.color.m3_on_primary)
                            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                        },
                        LinearLayout.LayoutParams(iconSize, iconSize),
                    )
                    addView(
                        TextView(this@SettingsActivity).apply {
                            text = item.title
                            textSize = 15f
                        },
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = (9 * density).toInt() },
                    )
                    setOnClickListener { showMenu(index) }
                }
            menus += row
            entries.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /**
     * 复用已创建的面板和滚动状态，隐藏页面降至 STARTED 停止刷新
     */
    private fun showMenu(index: Int) {
        if (supportFragmentManager.isStateSaved) return
        val panel = SettingsMenus.VISIBLE[index].id
        if (currentMenu == index &&
            currentPanel == panel &&
            supportFragmentManager.findFragmentByTag("settings:$panel")?.isHidden == false
        ) {
            return
        }
        currentMenu = index
        applyMenuStyle(index)
        openPanel(SettingsMenus.VISIBLE[index].id)
    }

    /**
     * 分组内的二级面板仍留在双栏右侧，并复用已经创建的列表
     */
    fun openPanel(id: Int) {
        val manager = supportFragmentManager
        if (manager.isStateSaved) return
        val tag = "settings:$id"
        val target = manager.findFragmentByTag(tag)
        if (target != null && target.isAdded && !target.isHidden && currentPanel == id) return
        currentPanel = id
        val transaction = manager.beginTransaction().setReorderingAllowed(true)
        manager.fragments.filter { it.id == R.id.settingsContainer && it !== target && !it.isHidden }.forEach {
            transaction.hide(it).setMaxLifecycle(it, androidx.lifecycle.Lifecycle.State.STARTED)
        }
        if (target == null) {
            val fragment = SettingsMenus.createFragment(id)
            transaction.add(R.id.settingsContainer, fragment, tag).setMaxLifecycle(fragment, androidx.lifecycle.Lifecycle.State.RESUMED)
        } else {
            transaction.show(target).setMaxLifecycle(target, androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        transaction.commitNow()
    }

    /**
     * 重建时保留当前菜单与二级面板
     */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("currentMenu", currentMenu)
        super.onSaveInstanceState(outState)
    }

    private fun applyMenuStyle(index: Int) {
        menus.forEachIndexed { i, menu ->
            menu.setAppearanceBackground(if (i == index) R.drawable.bg_menu_item else R.drawable.bg_tbtn)
            for (c in 0 until menu.childCount) {
                val child = menu.getChildAt(c)
                if (child is TextView) {
                    child.setAppearanceTextColor(if (i == index) R.color.m3_on_surface else R.color.m3_on_var)
                    child.paint.isFakeBoldText = i == index
                }
            }
        }
    }

    private fun close() {
        if (currentMenu >= 0 && currentPanel != SettingsMenus.VISIBLE[currentMenu].id) showMenu(currentMenu) else finish()
    }

    override fun onBackPressed() = close()

    /** 页内轻提示（不用系统 Toast：虚拟屏上显示位置不可控）。 */
    override fun showToast(text: String) {
        val view = toastView ?: return
        view.text = text
        view.animate().cancel()
        view.alpha = 0f
        view
            .animate()
            .alpha(1f)
            .setDuration(160)
            .withEndAction {
                view.postDelayed({
                    view
                        .animate()
                        .alpha(0f)
                        .setDuration(220)
                        .start()
                }, 1_250)
            }.start()
    }
}
