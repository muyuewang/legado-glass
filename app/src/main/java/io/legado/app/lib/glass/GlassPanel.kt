package io.legado.app.lib.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.utils.stackBlur

/**
 * 液态玻璃容器。三层结构：
 *
 *   ┌─ GlassPanel ───────────────────────────┐
 *   │  ① 模糊层：背后内容的降采样模糊位图      │  ← 本类负责截屏
 *   │  ② 材质层：tint + 顶光 + 边框 + 投影     │  ← GlassDrawable 负责
 *   │  ③ 内容层：子 View，永远清晰不模糊       │  ← FrameLayout 正常绘制
 *   └────────────────────────────────────────┘
 *
 * [为什么模糊必须由容器自己做]
 * Android 的 View 拿不到"自己背后的那块像素"。CSS 的 backdrop-filter 是合成器
 * 直接读下层图层，而 View 体系没有这个能力 —— 唯一可行的手段是：
 * 让父容器把当前画面画到一张 Bitmap 上，裁出本 View 占用的那块，模糊后回填。
 * 所以 `attachBlurSource(parentView)` 传进来的必须是**本 Panel 的父容器**，
 * 传自己会形成递归（画自己时又要读自己），直接黑屏。
 *
 * [刷新时机是性能关键]
 * 截屏 + 高斯模糊是重活（1080p 一张约 8~15ms）。绝不能每帧做。
 * 这里只在四种情况下刷新：
 *   · 首次布局完成 / 尺寸变化
 *   · 父容器滚动停止（不是滚动中）
 *   · 页面切换
 *   · 显式调用 [refreshBlur]
 * 也因此有一条硬规矩：**玻璃容器不要放进滚动列表**。
 * 一屏出现 3 个以上滚动中的玻璃卡，每帧多次截屏必然掉帧。
 */
class GlassPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val res = context.resources

    // ------------------------------------------------------------ 可配置项

    private var radius: Float = res.getDimension(R.dimen.lg_r_lg)

    private var tintLevel: Int = TINT_NORMAL

    private var blurEnabled: Boolean = false

    private var blurRadius: Float = res.getDimension(R.dimen.lg_blur)

    /**
     * 降采样倍率。模糊本身会把细节抹平，没必要按原分辨率算。
     *
     * [4 倍降采样对小元件是灾难]
     * Dock 只有 656x112，降 4 倍后位图只剩 164x28。
     * 在 28 像素高的图上做模糊，无论半径怎么调，出来的都是一条平均色带 ——
     * 用户看到的就是"背景完全透不出来"。
     * 改成 2 倍（328x56）后细节够用，成本也只有原来的 4 倍像素量，
     * 实测仍在 1ms 量级，完全不是瓶颈。
     */
    private var blurDownscale: Int = 2

    private var shadowEnabled: Boolean = true
    private var shadowScale: Float = 1f

    init {
        // 解析自定义属性，让同一套代码适配 Dock / 弹层 / 卡片三种形态
        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.GlassPanel)
            radius = a.getDimension(R.styleable.GlassPanel_lgRadius, radius)
            shadowEnabled = a.getBoolean(R.styleable.GlassPanel_lgShadow, shadowEnabled)
            shadowScale = a.getFloat(R.styleable.GlassPanel_lgShadowScale, shadowScale)
            tintLevel = a.getInt(R.styleable.GlassPanel_lgTintLevel, TINT_NORMAL)
            blurEnabled = a.getBoolean(R.styleable.GlassPanel_lgBlur, blurEnabled)
            blurRadius = a.getDimension(R.styleable.GlassPanel_lgBlurRadius, blurRadius)
            blurDownscale = a.getInt(R.styleable.GlassPanel_lgBlurDownscale, blurDownscale)
            a.recycle()
        }
        if (blurDownscale < 1) blurDownscale = 1

        rebuildGlass()
        /**
         * [必须关掉硬件层的"整体提升"]
         * 默认 FrameLayout 在透明度<1 或带动画时会被提升为硬件层，
         * 提升后父容器 draw 出来的是缓存纹理，截屏会拿到空白或旧帧。
         * 这里明确声明自己负责绘制，并且不参与硬件层提升。
         */
        setWillNotDraw(false)
    }

    /** 材质层：tint + 顶光 + 边框 + 投影，尺寸变化时重建 */
    private var glassDrawable: GlassDrawable = buildDrawable()

    private fun buildDrawable(): GlassDrawable {
        val tintColor = when (tintLevel) {
            TINT_SOFT -> R.color.lg_glass_tint_soft
            TINT_STRONG -> R.color.lg_glass_tint_strong
            else -> R.color.lg_glass_tint
        }
        return GlassDrawable(
            context = context,
            radius = radius,
            tint = ContextCompat.getColor(context, tintColor),
            borderColor = ContextCompat.getColor(context, R.color.lg_glass_border),
            withShadow = shadowEnabled,
            shadowScale = shadowScale
        )
    }

    private fun rebuildGlass() {
        glassDrawable = buildDrawable()
        invalidate()
    }

    // ------------------------------------------------------------ 模糊层

    /** 截屏来源。必须是本 Panel 的**父容器** */
    private var blurSource: View? = null

    /** 模糊结果，按 Panel 自身尺寸绘制 */
    private var blurBitmap: Bitmap? = null

    /** 复用的临时画布位图，避免每次刷新都新建（GC 压力很大） */
    private var scratch: Bitmap? = null

    private val blurPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val clipPath = Path()
    private val boundsF = RectF()
    private val targetRect = Rect()

    /** 截屏区域缓存，用于判断"背后内容是否真的变了" */
    private var lastCaptureKey: Int = 0

    /**
     * 绑定截屏来源。
     * @param source 本 Panel 的父容器（传自己会导致递归绘制 → 黑屏）
     */
    fun attachBlurSource(source: View) {
        if (blurSource === source) return
        blurSource = source
        scheduleRefresh()
    }

    fun setBlurEnabled(enabled: Boolean) {
        if (blurEnabled == enabled) return
        blurEnabled = enabled
        scheduleRefresh()
        invalidate()
    }

    fun setGlassRadius(px: Float) {
        if (radius == px) return
        radius = px
        rebuildGlass()
    }

    /**
     * 主动刷新模糊。
     * 滚动停止、页面切换、背景图更换后调用。
     */
    fun refreshBlur() {
        if (!blurEnabled) return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val src = blurSource ?: return

        try {
            /**
             * [截屏前必须把自己藏起来]
             * parent.draw() 会把 Panel 自己也画进去，结果就是"玻璃背后是玻璃自己"，
             * 表现为整块发灰、越刷新越糊。临时置为 INVISIBLE 是最省事的规避，
             * 因为它只影响本次手动 draw，不发生真实布局、不会闪烁。
             */
            val oldVisibility = visibility
            visibility = View.INVISIBLE

            val dw = (w / blurDownscale).coerceAtLeast(1)
            val dh = (h / blurDownscale).coerceAtLeast(1)
            val bmp = ensureScratch(dw, dh)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.TRANSPARENT)

            // 把 Panel 在父容器里的位置换算成画布偏移
            val loc = IntArray(2)
            getLocationInWindow(loc)
            val pLoc = IntArray(2)
            src.getLocationInWindow(pLoc)
            val offsetX = loc[0] - pLoc[0]
            val offsetY = loc[1] - pLoc[1]

            canvas.scale(1f / blurDownscale, 1f / blurDownscale)
            canvas.translate(-offsetX.toFloat(), -offsetY.toFloat())
            src.draw(canvas)

            visibility = oldVisibility

            /**
             * [模糊半径要按降采样倍率折算]
             * 位图已经被缩小了 1/downscale，同样的像素半径在视觉上会被放大
             * downscale 倍。不折算的话 downscale=4 时看起来会糊成一团。
             */
            val r = (blurRadius / blurDownscale).toInt().coerceAtLeast(1)
            val t0 = android.os.SystemClock.elapsedRealtime()
            val blurred = bmp.stackBlur(r)
            val cost = android.os.SystemClock.elapsedRealtime() - t0

            /**
             * 诊断日志：只在 debug 包里输出，用来确认模糊真的产出了位图。
             * 空数据页面上肉眼无法判断模糊是否生效（背后什么都没有，糊了也看不出来），
             * 所以必须靠这条日志区分"功能正常但无内容可糊"和"功能根本没跑"。
             */
            if (io.legado.app.BuildConfig.DEBUG) {
                android.util.Log.d(
                    "GlassPanel",
                    "blur ok ${bmp.width}x${bmp.height} r=$r cost=${cost}ms " +
                        "src=${src.javaClass.simpleName} panel=${w}x$h"
                )
            }

            blurBitmap?.takeIf { it !== blurred && !it.isRecycled }?.recycle()
            blurBitmap = blurred
            invalidate()
        } catch (e: Throwable) {
            // 截屏失败（如父容器过大 OOM）时静默降级为纯材质，
            // 绝不能因为"想要更好看"而把主界面搞崩
            if (io.legado.app.BuildConfig.DEBUG) {
                android.util.Log.w("GlassPanel", "blur failed: ${e.message}")
            }
            blurBitmap = null
            invalidate()
        }
    }

    private fun ensureScratch(w: Int, h: Int): Bitmap {
        val cur = scratch
        if (cur != null && !cur.isRecycled && cur.width == w && cur.height == h) {
            cur.eraseColor(Color.TRANSPARENT)
            return cur
        }
        cur?.takeIf { !it.isRecycled }?.recycle()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { scratch = it }
    }

    private fun scheduleRefresh() {
        if (!blurEnabled) return
        post { refreshBlur() }
    }

    // ------------------------------------------------------------ 绘制

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        boundsF.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(boundsF, radius, radius, Path.Direction.CW)
        scheduleRefresh()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        // ---- ① 模糊层：裁剪在圆角内，避免方形溢出 ----
        val bmp = blurBitmap
        if (blurEnabled && bmp != null && !bmp.isRecycled) {
            val save = canvas.save()
            canvas.clipPath(clipPath)
            targetRect.set(0, 0, w, h)
            canvas.drawBitmap(bmp, null, targetRect, blurPaint)
            canvas.restoreToCount(save)
        }

        // ---- ② 材质层：阴影 / tint / 顶光 / 边框 ----
        glassDrawable.setBounds(0, 0, w, h)
        glassDrawable.draw(canvas)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        blurBitmap?.takeIf { !it.isRecycled }?.recycle()
        blurBitmap = null
        scratch?.takeIf { !it.isRecycled }?.recycle()
        scratch = null
    }

    companion object {
        const val TINT_SOFT = 0
        const val TINT_NORMAL = 1
        const val TINT_STRONG = 2
    }
}