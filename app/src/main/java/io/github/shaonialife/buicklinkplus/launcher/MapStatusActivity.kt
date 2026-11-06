package io.github.shaonialife.buicklinkplus.launcher

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.ui.GlassActivity
import io.github.shaonialife.buicklinkplus.ui.setAppearanceTextColor

/**
 * 地图入口不可用时在地图屏显示处理办法，避免空白画面或静默退回手机版
 */
class MapStatusActivity : GlassActivity() {
    /**
     * 创建地图状态页并隐藏虚拟屏系统栏
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        showMessage()
    }

    /**
     * 复用状态页时更新当前地图提示
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showMessage()
    }

    /**
     * 在地图可用区域内展示中文提示
     */
    private fun showMessage() {
        val padding = (24 * resources.displayMetrics.density).toInt()
        setContentView(
            android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(
                    TextView(this@MapStatusActivity).apply {
                        text = intent.getStringExtra("message") ?: "地图暂未打开，请从车机地图入口重试"
                        textSize = 16f
                        gravity = Gravity.CENTER
                        setAppearanceTextColor(R.color.m3_on_surface)
                        setPadding(padding, padding, padding, padding)
                    },
                )
                addView(
                    android.widget.Button(this@MapStatusActivity).apply {
                        text = "重试地图"
                        setOnClickListener { VirtualLauncher.retryMap() }
                    },
                )
            },
        )
    }
}
