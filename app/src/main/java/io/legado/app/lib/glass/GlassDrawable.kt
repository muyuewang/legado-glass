package io.legado.app.lib.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import io.legado.app.R

/**
 * 液态玻璃材质绘制核心。
 *
 * [为什么自己画，而不用 shape drawable + 多层叠加]
 * 玻璃不是"一个半透明圆角矩形"那么简单，它由四层视觉要素叠出来：
 *   1) 投影      —— 落下时与页面的关系
 *   2) tint 填充 —— 材质本体的着色调
 *   3) 顶光渐变  —— 光线从左上传入玻璃厚度，制造体积感
 *   4) 边框+内高光 —— 边缘那道亮线，玻璃感的主要来源
 * 用 4 个 shape 叠在 4 个 View 上，任何一层要调参数都得改布局结构；
 * 自己画只需改这里的常量，且能保证四层严格对齐同一个圆角路径
 * （分层 View 在圆角处极易露出 1~2px 的白边）。
 *
 * [为什么不在这里做真实模糊]
 * View 体系拿不到"自己背后那块像素"，模糊必须由父容器截屏后喂进来，
 * 属于 [GlassBlurLayer] 的职责。本类只管材质本身，
 * 因此在任何 API 级别、任何设备上都能完整渲染 —— 这是降级路径的底座。
 */
class GlassDrawable(
    context: Context,
    /** 圆角半径（px） */
    private val radius: Float = context.resources.getDimension(R.dimen.lg_r_lg),
    /** tint 着色，默认用标准强度 */
    @ColorInt private val tint: Int = ContextCompat.getColor(context, R.color.lg_glass_tint),
    /** 边框色 */
    @ColorInt private val borderColor: Int = ContextCompat.getColor(context, R.color.lg_glass_border),
    /** 是否绘制投影。贴着屏幕底边的悬浮条通常要，嵌在列表里的卡片通常不要 */
    private val withShadow: Boolean = true,
    /** 投影强度倍率，1.0 = 标准 */
    private val shadowScale: Float = 1f,
    /**
     * 只圆下方两角。
     *
     * [什么场景需要它]
     * 标题栏、底部面板这类**贴着屏幕边**的浮层：它们的上（下）沿与屏幕边缘齐平，
     * 如果把这一侧也画成圆角，会露出两个"缺口" —— 缺口后面是页面底色，
     * 看起来像玻璃没铺满，比不圆角更糟。
     *
     * [为什么不直接传 8 个半径]
     * 实际只有"全圆"和"单侧圆"两种真实需求（Dock 浮空 / 标题栏贴顶），
     * 传数组会让每个调用点都得重复写 8 个值，反而容易写错顺序。
     * 用一个语义明确的布尔量，意图更清楚。
     */
    private val roundBottomOnly: Boolean = false,
    /**
     * 只圆上方两角。与 [roundBottomOnly] 互斥，同时为 true 时以本参数为准。
     *
     * [什么场景需要它]
     * 阅读页底部工具栏、底部面板这类**贴着屏幕下边**的浮层。
     * 它们是 roundBottomOnly 的镜像：下沿被系统导航栏吃掉，
     * 圆了也看不见，而横屏时还会在两侧露出缺口；
     * 上沿是浮层的"正面"，才是需要圆角的地方。
     */
    private val roundTopOnly: Boolean = false
) : Drawable() {

    private val res = context.resources

    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = tint
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = res.getDimension(R.dimen.lg_border_width)
        color = borderColor
    }

    /** 顶光：从左上角往右下衰减，两端色标在 [setBounds] 里按实际尺寸重建 */
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 顶部高光线：内缩 1px 的一条弧，比 border 更亮，是"那道边"的点睛 */
    private val hiTopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = res.getDimension(R.dimen.lg_hairline)
        color = ContextCompat.getColor(context, R.color.lg_hi_top)
    }

    private val hiBottomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = res.getDimension(R.dimen.lg_hairline)
        color = ContextCompat.getColor(context, R.color.lg_hi_bottom)
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // [投影必须偏暖]
        // 纯黑投影落在米色底上会发脏，实测肉眼可辨。
        // 这里用同族深褐，靠 alpha 控制浓度，叠三层模拟柔和扩散。
        color = Color.TRANSPARENT
    }

    private val path = Path()
    private val boundsF = RectF()

    private val sheenStart = ContextCompat.getColor(context, R.color.lg_sheen_start)
    private val sheenMid = ContextCompat.getColor(context, R.color.lg_sheen_mid)
    private val sheenEnd = ContextCompat.getColor(context, R.color.lg_sheen_end)

    private val shadow1 = ContextCompat.getColor(context, R.color.lg_shadow_1)
    private val shadow2 = ContextCompat.getColor(context, R.color.lg_shadow_2)
    private val shadow3 = ContextCompat.getColor(context, R.color.lg_shadow_3)

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        boundsF.set(bounds)
        buildShapePath(path, boundsF, radius)

        /**
         * 顶光渐变 160° 方向（与 CSS 的 linear-gradient(160deg,...) 对齐）。
         * 起点放在左上偏外一点，终点拉到右下，保证小尺寸下也能看到过渡。
         */
        val w = bounds.width().toFloat().coerceAtLeast(1f)
        val h = bounds.height().toFloat().coerceAtLeast(1f)
        sheenPaint.shader = LinearGradient(
            -w * 0.1f, -h * 0.3f,
            w * 0.9f, h * 1.2f,
            intArrayOf(sheenStart, sheenMid, sheenEnd),
            floatArrayOf(0f, 0.34f, 0.72f),
            Shader.TileMode.CLAMP
        )
    }

    /**
     * 按"分角"语义构建圆角矩形路径。
     *
     * [为什么必须统一走这一个函数]
     * 玻璃的四层（本体 tint / 边框 / 内高光 / 投影）必须严格落在**同一条**轮廓上。
     * 之前每层各自 `addRoundRect(rect, radius, radius)`，四角一致时看不出问题；
     * 但一旦引入"只圆下角"，哪一层忘了改，就会露馅：
     *   · 边框忘了改 → 圆角处出现矛出的直角描边
     *   · 投影忘了改 → 顶部两角投出圆弧阴影，像贴了张纸
     * 所以这里把半径数组的构造集中一处，所有层都调它。
     *
     * [radii 数组顺序是固定的]
     * Android 的约定是 左上、右上、右下、左下，每个角占 2 个 float（x/y 半径）。
     * 顺序写错不会报错，只会让圆角长在错误的角上 —— 这类 bug 极难靠肉眼定位。
     */
    private fun buildShapePath(target: Path, rect: RectF, r: Float, grow: Float = 0f) {
        val rr = r + grow
        val rect0 = if (grow == 0f) rect else RectF(rect).apply { inset(-grow, -grow) }
        // 顺序固定：左上、右上、右下、左下，每角两个 float（x/y 半径）
        val radii = when {
            roundTopOnly -> floatArrayOf(rr, rr, rr, rr, 0f, 0f, 0f, 0f)
            roundBottomOnly -> floatArrayOf(0f, 0f, 0f, 0f, rr, rr, rr, rr)
            else -> floatArrayOf(rr, rr, rr, rr, rr, rr, rr, rr)
        }
        target.reset()
        target.addRoundRect(rect0, radii, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return

        // ---- 1. 投影：三层同心圆角，由外到内递减浓度，模拟柔和扩散 ----
        if (withShadow) {
            val spread = res.getDimension(R.dimen.lg_shadow_spread) * shadowScale
            drawShadowLayer(canvas, spread, shadow3)
            drawShadowLayer(canvas, spread * 0.5f, shadow2)
            drawShadowLayer(canvas, 0f, shadow1)
        }

        // ---- 2. tint 本体 ----
        canvas.drawPath(path, tintPaint)

        // ---- 3. 顶光渐变（裁剪在圆角内，避免溢出成方块）----
        val save = canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(boundsF, sheenPaint)
        canvas.restoreToCount(save)

        // ---- 4. 边框 ----
        /**
         * [描边必须内缩半个线宽]
         * Paint.Style.STROKE 是沿着路径中心向两侧各画 0.5 线宽，
         * 不内缩的话边框会有一半落在 bounds 外面，被父布局裁掉，
         * 表现为"某几边没有边线"，在圆角处尤其明显。
         */
        val inset = borderPaint.strokeWidth / 2f
        val borderRect = RectF(boundsF).apply { inset(inset, inset) }
        val borderPath = Path()
        buildShapePath(borderPath, borderRect, radius - inset)
        canvas.drawPath(borderPath, borderPaint)

        // ---- 5. 内高光：顶亮底暗，玻璃厚度的暗示 ----
        /**
         * [顶光与底光不能画在同一条路径上]
         * 两条路径若完全相同，后画的会把先画的整圈盖掉 —— 结果只剩底光，
         * 顶部那道亮边消失，玻璃立刻变成"一块塑料片"。
         * 正确做法是把各自裁剪到自己的半边再画。
         */
        val hair = hiTopPaint.strokeWidth / 2f
        val hiRect = RectF(boundsF).apply { inset(hair * 2, hair * 2) }
        val hiPath = Path()
        buildShapePath(hiPath, hiRect, radius - hair * 2)

        // 顶光：只保留上半部分
        val saveTop = canvas.save()
        canvas.clipRect(0f, 0f, boundsF.width(), boundsF.height() * 0.5f)
        canvas.drawPath(hiPath, hiTopPaint)
        canvas.restoreToCount(saveTop)

        // 底光：只保留下半部分
        val saveBottom = canvas.save()
        canvas.clipRect(0f, boundsF.height() * 0.5f, boundsF.width(), boundsF.height())
        canvas.drawPath(hiPath, hiBottomPaint)
        canvas.restoreToCount(saveBottom)
    }

    /**
     * 单层投影。
     * [为什么用 clipPath 差集而不是直接画]
     * 直接画一个大圆角会盖住玻璃本体，导致 tint 底下透出一层灰。
     * 这里把"玻璃自身的形状"从投影区域里挖掉，只留外扩的那一圈。
     */
    private fun drawShadowLayer(canvas: Canvas, spread: Float, @ColorInt color: Int) {
        shadowPaint.color = color
        val outer = RectF(boundsF)
        val outerPath = Path()
        buildShapePath(outerPath, outer, radius, grow = spread)
        val save = canvas.save()
        // minSdk 已提到 26，clipOutPath 可用，比 clipPath(DIFFERENCE) 少一层 Region 构造
        canvas.clipOutPath(path)
        canvas.drawPath(outerPath, shadowPaint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        // 整体透明度：同时作用于填充与边框，避免只淡一边出现断层
        tintPaint.alpha = (Color.alpha(tint) * alpha / 255f).toInt()
        borderPaint.alpha = (Color.alpha(borderColor) * alpha / 255f).toInt()
        invalidateSelf()
    }

    /**
     * 轮廓。供 `View.clipToOutline = true` 使用。
     *
     * [没有这个方法，圆角就是假的]
     * View 的 clipToOutline 只认 outline，而自定义 Drawable 默认不提供 outline。
     * 缺了它，玻璃自己虽然是圆角，但**子 View 仍按方形绘制**：
     * 对话框顶部的 Toolbar（带 primaryColor 实色底）会从圆角边缘糊出四个直角，
     * 视觉上就像"圆角玻璃上贴了一张方纸"，圆角等于白做。
     */
    override fun getOutline(outline: android.graphics.Outline) {
        if (bounds.isEmpty) {
            outline.setEmpty()
            return
        }
        /**
         * [Outline 只有"统一圆角"，没有分角版本]
         * `Outline.setRoundRect` 只能四角同一个半径。需要"只圆下角"时：
         *   · API 30+：setPath 可以直接吃任意形状，用同一套 buildShapePath；
         *   · API 26~29：没有 setPath，退成 setRect（直角）。
         * 直角在这里是**可接受的降级**：标题栏贴着屏幕顶边、
         * 且它内部没有需要被圆角裁掉的子元素，不裁也看不出问题。
         * 反过来若强行用统一圆角，顶部会露出两个缺口，那是真的难看。
         */
        if (!roundBottomOnly && !roundTopOnly) {
            outline.setRoundRect(bounds, radius)
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val outlinePath = Path()
            buildShapePath(outlinePath, RectF(bounds), radius)
            outline.setPath(outlinePath)
        } else {
            outline.setRect(bounds)
        }
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        tintPaint.colorFilter = colorFilter
        borderPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}