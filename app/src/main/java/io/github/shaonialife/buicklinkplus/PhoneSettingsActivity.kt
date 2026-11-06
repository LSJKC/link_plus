package io.github.shaonialife.buicklinkplus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.shaonialife.buicklinkplus.launcher.settings.PhoneSettingsHost
import io.github.shaonialife.buicklinkplus.launcher.settings.SettingsMenus
import io.github.shaonialife.buicklinkplus.launcher.settings.SettingsToastHost
import io.github.shaonialife.buicklinkplus.ui.GlassActivity
import io.github.shaonialife.buicklinkplus.ui.setAppearanceImageResource

/** 手机二级设置页，内容 Fragment 与车机设置共用 */
class PhoneSettingsActivity : GlassActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_settings)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        val list = findViewById<RecyclerView>(R.id.menuList)
        list.layoutManager = LinearLayoutManager(this)
        list.isVerticalScrollBarEnabled = false
        list.isHorizontalScrollBarEnabled = false
        list.adapter = MenuAdapter()
    }

    private inner class MenuAdapter : RecyclerView.Adapter<MenuHolder>() {
        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): MenuHolder {
            val view = layoutInflater.inflate(R.layout.item_phone_settings_menu, parent, false)
            return MenuHolder(view)
        }

        override fun getItemCount() = SettingsMenus.PANELS.size

        override fun onBindViewHolder(
            holder: MenuHolder,
            position: Int,
        ) {
            val item = SettingsMenus.PANELS[position]
            holder.icon.setAppearanceImageResource(item.iconRes)
            holder.title.text = item.title
            holder.desc.text = item.desc
            holder.itemView.setOnClickListener {
                SettingsContentActivity.start(this@PhoneSettingsActivity, item)
            }
        }
    }

    private class MenuHolder(
        view: View,
    ) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.menuIcon)
        val title: TextView = view.findViewById(R.id.menuTitle)
        val desc: TextView = view.findViewById(R.id.menuDesc)
    }
}

/**
 * 手机侧设置内容宿主：承载与车机双栏共用的内容 Fragment。
 */
class SettingsContentActivity :
    GlassActivity(),
    PhoneSettingsHost,
    SettingsToastHost {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestedMenu = intent.getIntExtra(EXTRA_MENU, SettingsMenus.DESKTOP)
        val menuId = SettingsMenus.directPhonePanel(requestedMenu) ?: requestedMenu
        setContentView(R.layout.activity_settings_content)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.contentTitle).text = SettingsMenus.titleOf(menuId)

        // 只处理手机内容宿主，车机双栏的共享面板保留原有行为
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    manager: androidx.fragment.app.FragmentManager,
                    fragment: androidx.fragment.app.Fragment,
                    view: View,
                    savedInstanceState: Bundle?,
                ) {
                    val pending = java.util.ArrayDeque<View>()
                    pending.add(view)
                    while (pending.isNotEmpty()) {
                        val item = pending.removeFirst()
                        item.isVerticalScrollBarEnabled = false
                        item.isHorizontalScrollBarEnabled = false
                        if (item is ViewGroup) {
                            for (index in 0 until item.childCount) pending.add(item.getChildAt(index))
                        }
                    }
                }
            },
            true,
        )

        if (supportFragmentManager.findFragmentById(R.id.settingsContainer) == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settingsContainer, SettingsMenus.createFragment(menuId))
                .commit()
        }
    }

    override fun showToast(text: String) {
        android.widget.Toast
            .makeText(this, text, android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    companion object {
        private const val EXTRA_MENU = "menu"

        fun start(
            context: Context,
            item: SettingsMenus.Item,
            onPhone: Boolean = false,
        ) {
            context.startActivity(
                Intent(context, SettingsContentActivity::class.java)
                    .putExtra(EXTRA_MENU, SettingsMenus.directPhonePanel(item.id) ?: item.id)
                    .apply {
                        // 手机配置使用新任务，避免把承载仪表盘的车机任务一起移到主屏
                        if (onPhone) {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                            )
                        }
                    },
                if (onPhone) {
                    android.app.ActivityOptions
                        .makeBasic()
                        .apply { launchDisplayId = 0 }
                        .toBundle()
                } else {
                    null
                },
            )
        }
    }
}
