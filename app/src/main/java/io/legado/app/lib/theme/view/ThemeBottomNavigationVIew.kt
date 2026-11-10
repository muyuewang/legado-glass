package io.legado.app.lib.theme.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.legado.app.R
import io.legado.app.databinding.ViewNavigationBadgeBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.getSecondaryTextColor
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.ui.widget.text.BadgeView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx

class ThemeBottomNavigationVIew(context: Context, attrs: AttributeSet) :
    BottomNavigationView(context, attrs) {

    private val themeIconTint: ColorStateList

    /** menu id -> 默认矢量图标; applySkin(null) 时据此还原 */
    private val defaultIcons = mapOf(
        R.id.menu_bookshelf to R.drawable.ic_bottom_books,
        R.id.menu_discovery to R.drawable.ic_bottom_explore,
        R.id.menu_rss to R.drawable.ic_bottom_rss_feed,
        R.id.menu_my_config to R.drawable.ic_bottom_person,
    )

    /**
     * [玻璃模式：底栏自己不再画背景]
     *
     * 原来的 init 无条件 setBackgroundColor(bgColor)，会把 XML 里写的
     * `android:background="@color/transparent"` 覆盖掉 —— 结果玻璃面板内部
     * 还包着一层不透明底，模糊层被彻底盖住，看起来就只是一块普通灰板。
     *
     * 判定方式选"看父容器"而不是加一个 XML 属性，是为了让所有引用本类的地方
     * （主界面 Dock、皮肤页预览）自动生效，不必逐处传参。
     */
    private val inGlassPanel: Boolean
        get() = parent is io.legado.app.lib.glass.GlassPanel

    init {
        val transparentNavBar = context.transparentNavBar

        /**
         * [这里只能按"不在玻璃里"先初始化一次]
         * XML 的 inflate 顺序是：先 new 出本 View（此时 parent 必然为 null），
         * 再 addView 挂进父容器。所以 init 阶段读 parent 永远拿不到 GlassPanel。
         * 真实判定放在 onAttachedToWindow 里重做。
         */
        applyGlassBackground()

        val bgColor = if (transparentNavBar) context.backgroundColor
        else context.bottomBackground
        val textIsDark = ColorUtils.isColorLight(bgColor)
        val textColor = context.getSecondaryTextColor(textIsDark)
        val colorStateList = Selector.colorBuild()
            .setDefaultColor(textColor)
            .setSelectedColor(ThemeStore.accentColor(context))
            .create()
        themeIconTint = colorStateList
        itemIconTintList = colorStateList
        itemTextColor = colorStateList
        if (AppConfig.isEInkMode || transparentNavBar) {
            isItemHorizontalTranslationEnabled = false
            itemBackground = Color.TRANSPARENT.toDrawable()
        }

        ViewCompat.setOnApplyWindowInsetsListener(this, null)
    }

    /**
     * 按"是否身处玻璃面板"决定底栏背景。
     *
     * 在玻璃里 → 彻底透明 + 清掉 elevation。
     *   · 不透明底会把 GlassPanel 的模糊层整块盖死；
     *   · elevation 会再投一层 Material 阴影，和 GlassDrawable 的投影叠成双重黑影，
     *     圆角处尤其脏。
     * 不在玻璃里 → 维持上游原行为，不能因为改主界面而影响其他页面。
     */
    private fun applyGlassBackground() {
        if (inGlassPanel) {
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
        } else {
            val bgColor = if (context.transparentNavBar) context.backgroundColor
            else context.bottomBackground
            setBackgroundColor(bgColor)
            elevation = context.elevation
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 此时 parent 已经就位，重新判一次（init 阶段 parent 必为 null）
        applyGlassBackground()
    }

    /**
     * 应用/取消底栏图集。
     * @param iconMap menu id -> StateListDrawable; 传 null 表示恢复默认主题图标
     * @param iconSizePx 有皮肤时的图标尺寸(像素)
     */
    fun applySkin(iconMap: Map<Int, StateListDrawable>?, iconSizePx: Int) {
        if (iconMap == null) {
            itemIconTintList = themeIconTint
            itemIconSize = 24.dpToPx()
            defaultIcons.forEach { (id, res) ->
                menu.findItem(id)?.icon = defaultIcon(res, tinted = false)
            }
        } else {
            itemIconTintList = null
            itemIconSize = iconSizePx
            defaultIcons.keys.forEach { id ->
                menu.findItem(id)?.icon =
                    iconMap[id] ?: defaultIcon(defaultIcons.getValue(id), tinted = true)
            }
        }
    }

    private fun defaultIcon(resId: Int, tinted: Boolean) =
        ContextCompat.getDrawable(context, resId)?.mutate()?.apply {
            if (tinted) DrawableCompat.setTintList(this, themeIconTint)
        }

    fun addBadgeView(index: Int): BadgeView {
        //获取底部菜单view
        val menuView = getChildAt(0) as ViewGroup
        //获取第index个itemView
        val itemView = menuView.getChildAt(index) as ViewGroup
        val badgeBinding = ViewNavigationBadgeBinding.inflate(LayoutInflater.from(context))
        itemView.addView(badgeBinding.root)
        return badgeBinding.viewBadge
    }

}
