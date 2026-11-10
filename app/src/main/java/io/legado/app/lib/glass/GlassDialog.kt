package io.legado.app.lib.glass

import android.graphics.Color
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.dpToPx

/**
 * 弹层（对话框 / 弹出菜单）的液态玻璃接入。
 *
 * [为什么弹层不能复用 GlassPanel]
 * [GlassPanel] 的模糊靠"父容器把自己画到 Bitmap 上再回填"，前提是 Panel
 * 和背后的内容**在同一个 Window 里**。对话框是独立 Window，`parent` 只到
 * DecorView，`getLocationInWindow` 换算出来的坐标落在对话框自己身上 ——
 * 截出来的就是对话框自身，模糊完再画回去，等于什么都没糊。
 * 所以弹层必须换一条路径：**用系统的跨窗口背景模糊**。
 *
 * [跨窗口模糊的能力分界线]
 *   · API 31+：[Window.setBackgroundBlurRadius] 真实生效，系统合成器直接
 *     读窗口背后的图层做高斯模糊，效果等同 CSS backdrop-filter；
 *   · API 26~30：系统只有已废弃的 `FLAG_BLUR_BEHIND`，从 Android 8 起
 *     被静默忽略，设置了也不会有任何效果 —— 因此**不要去设**，避免
 *     让人误以为已经在模糊。
 * 低版本走纯材质降级：靠更高的 tint（72%）+ 亮边框 + 强投影撑住"玻璃感"，
 * 视觉上仍是同一套语言，只是少了背后那层柔化。
 *
 * [弹层的 tint 必须比 Dock 厚]
 * Dock 的文字在玻璃**外**，越透越好看；对话框的文字**画在玻璃上**，
 * 且背后是被 dim 压暗的复杂页面。沿用 Dock 的 27% 会让标题和背后的
 * 列表行叠在一起，直接读不清。
 */
object GlassDialog {

    /**
     * 给弹窗 Window 套上玻璃材质。
     *
     * @param tintRes  tint 颜色资源。对话框用 panel、菜单用 menu。
     *   **注意这里只写一个名字**：具体数值由资源限定符分档，
     *   `values/lg_colors.xml` 是 API 31 以下的"无模糊档"（94%），
     *   `values-v31/lg_colors.xml` 是"有模糊档"（72%）。
     *   代码层不做任何版本判断，避免两处逻辑不同步。
     * @param radiusRes 圆角。对话框用 lg（26dp），菜单用 md（20dp）
     * @param blurRadiusDp 背景模糊半径。API 31 以下无效
     */
    fun apply(
        window: Window,
        @ColorRes tintRes: Int = R.color.lg_glass_tint_panel,
        @DimenRes radiusRes: Int = R.dimen.lg_r_lg,
        blurRadiusDp: Float = 22f
    ) {
        // 墨水屏模式不参与玻璃：上游用纯白底 + 黑框，玻璃会破坏对比度
        if (AppConfig.isEInkMode) return

        val context = window.context
        val res = context.resources

        /**
         * [投影必须关掉]
         * [GlassDrawable] 的投影是画在 bounds **之外**的，而 Window 背景
         * drawable 的 bounds 就是整个窗口 —— 外扩部分会被系统直接裁掉，
         * 不但没有阴影，还会在边缘留下半圈脏边。
         * 弹层的层次交给 dim 遮罩承担，这本来就是系统对话框的既有做法。
         */
        val glass = GlassDrawable(
            context = context,
            radius = res.getDimension(radiusRes),
            tint = ContextCompat.getColor(context, tintRes),
            borderColor = ContextCompat.getColor(context, R.color.lg_glass_border),
            withShadow = false
        )

        window.setBackgroundDrawable(glass)

        /**
         * [窗口必须声明为半透明，否则玻璃背后是黑的]
         * Dialog 默认窗口格式是 OPAQUE，即使背景 drawable 带 alpha，
         * 系统也会把它合成到一张不透明 surface 上 —— 表现为"玻璃是灰的"。
         */
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        applyBackgroundBlur(window, blurRadiusDp)
    }

    /**
     * 当前系统是否支持**跨窗口**背景模糊。
     *
     * [为什么门槛是 API 31]
     * `Window.setBackgroundBlurRadius()` 是 Android 12（API 31）才加的，
     * 且它依赖窗口的 `FLAG_BLUR_BEHIND` 语义 —— 更早版本那个 flag 从
     * Android 8 起就被静默忽略了，设了也不会糊。
     * 所以这里以 SDK 版本为准，不去探测 flag（flag 永远"设置成功"但没效果）。
     */
    fun canBlurBehindWindow(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * 开启系统跨窗口背景模糊（仅 API 31+ 真实生效）。
     *
     * [为什么要先降低 dim]
     * 系统默认 dim 是 0.32，叠在已经模糊的背景上会让整屏发闷，
     * 玻璃的透光感被吃掉。有模糊时压到 0.14，让背后的轮廓还能看出来；
     * 没有模糊时保持默认，靠 dim 拉开层次。
     */
    private fun applyBackgroundBlur(window: Window, blurRadiusDp: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            /**
             * [dpToPx 是无参扩展，按屏幕密度换算]
             * 它取的是 DisplayMetrics.density，不做任何中央缩放，
             * 所以这里传 dp 值直接换算成物理像素即可。
             */
            val px = blurRadiusDp.dpToPx().toInt().coerceAtLeast(1)
            window.setBackgroundBlurRadius(px)

            val attr = window.attributes
            attr.dimAmount = 0.14f
            window.attributes = attr
        }.onFailure {
            // 部分 ROM 会在不支持模糊时抛异常，降级为纯材质即可，不能影响弹窗本身
        }
    }

    /** 无窗口背景的纯色兜底（墨水屏 / 极端降级用，保证文字一定可读） */
    fun solidFallbackColor(context: android.content.Context): Int {
        return if (AppConfig.isEInkMode) {
            Color.WHITE
        } else {
            ContextCompat.getColor(context, R.color.lg_fallback_solid)
        }
    }
}