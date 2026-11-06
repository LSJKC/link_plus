package io.github.shaonialife.buicklinkplus.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip

/** 应用图标位图化工具（iOS 风格圆角；桌面网格 / dock / 设置页共用）。 */
object AppIcons {
    private val executor =
        java.util.concurrent.Executors
            .newFixedThreadPool(2)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val cache =
        object : android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.allocationByteCount
        }

    /**
     * 后台生成列表图标，回收后的行不能接收上一个应用的结果
     */
    fun bind(
        view: android.widget.ImageView,
        entry: VirtualLauncher.AppEntry,
        size: Int,
        owner: androidx.lifecycle.LifecycleOwner? = null,
    ) {
        val version = VirtualLauncher.appsCacheVersion
        val key = "${entry.component.flattenToString()}:$size:$version"
        val token = Any()
        view.setTag(io.github.shaonialife.buicklinkplus.R.id.appIconRequest, token)
        cache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        val target = java.lang.ref.WeakReference(view)
        executor.execute {
            val bitmap = runCatching { cache.get(key) ?: rounded(entry.icon, size).also { cache.put(key, it) } }.getOrNull()
            mainHandler.post {
                val image = target.get() ?: return@post
                if (image.getTag(io.github.shaonialife.buicklinkplus.R.id.appIconRequest) === token &&
                    VirtualLauncher.appsCacheVersion == version &&
                    owner?.lifecycle?.currentState != androidx.lifecycle.Lifecycle.State.DESTROYED
                ) {
                    image.setImageBitmap(bitmap)
                }
            }
        }
    }

    /**
     * 把 Drawable 副本绘制成圆角位图，圆角比例由 [radiusFraction] 指定
     * 无法复制时保存并恢复 bounds，避免改坏共享图标
     */
    fun rounded(
        drawable: Drawable,
        size: Int,
        radiusFraction: Float = 0.22f,
    ): Bitmap {
        if (size <= 0) return createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = size * radiusFraction
        val path =
            Path().apply {
                addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), radius, radius, Path.Direction.CW)
            }
        val target =
            runCatching { drawable.constantState?.newDrawable()?.mutate() }
                .getOrNull() ?: drawable
        val savedBounds = Rect(target.bounds)
        canvas.withClip(path) {
            target.setBounds(0, 0, size, size)
            target.draw(canvas)
        }
        target.bounds = savedBounds
        return bitmap
    }
}
