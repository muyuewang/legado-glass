@file:Suppress("unused")

package io.legado.app.lib.theme

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.glass.GlassDrawable
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
@ColorInt
fun Context.getPrimaryTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_primary_text)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_primary_text)
    }
}

@ColorInt
fun Context.getSecondaryTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_secondary)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_primary_text)
    }
}

@ColorInt
fun Context.getPrimaryDisabledTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_disabled)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_disabled)
    }
}

@ColorInt
fun Context.getSecondaryDisabledTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(
            this,
            androidx.appcompat.R.color.secondary_text_disabled_material_light
        )
    } else {
        ContextCompat.getColor(
            this,
            androidx.appcompat.R.color.secondary_text_disabled_material_dark
        )
    }
}

val Context.primaryColor: Int
    get() = ThemeStore.primaryColor(this)

val Context.primaryColorDark: Int
    get() = ThemeStore.primaryColorDark(this)

val Context.accentColor: Int
    get() = ThemeStore.accentColor(this)

val Context.backgroundColor: Int
    get() = ThemeStore.backgroundColor(this)

val Context.bottomBackground: Int
    get() = ThemeStore.bottomBackground(this)

val Context.primaryTextColor: Int
    get() = getPrimaryTextColor(isDarkTheme)

@ColorInt
fun Context.getToolbarTextColor(transparentBar: Boolean): Int {
    val barColor = toolbarBackgroundColor(transparentBar, primaryColor, backgroundColor)
    return getPrimaryTextColor(ColorUtils.isColorLight(barColor))
}

@ColorInt
internal fun toolbarBackgroundColor(
    transparentBar: Boolean,
    @ColorInt primaryColor: Int,
    @ColorInt backgroundColor: Int
): Int = if (transparentBar) backgroundColor else primaryColor

val Context.transparentNavBar: Boolean
    get() = ThemeStore.transparentNavBar(this)

val Context.secondaryTextColor: Int
    get() = getSecondaryTextColor(isDarkTheme)

val Context.primaryDisabledTextColor: Int
    get() = getPrimaryDisabledTextColor(isDarkTheme)

val Context.secondaryDisabledTextColor: Int
    get() = getSecondaryDisabledTextColor(isDarkTheme)

val Fragment.primaryColor: Int
    get() = ThemeStore.primaryColor(requireContext())

val Fragment.primaryColorDark: Int
    get() = ThemeStore.primaryColorDark(requireContext())

val Fragment.accentColor: Int
    get() = ThemeStore.accentColor(requireContext())

val Fragment.backgroundColor: Int
    get() = ThemeStore.backgroundColor(requireContext())

val Fragment.bottomBackground: Int
    get() = ThemeStore.bottomBackground(requireContext())

val Fragment.primaryTextColor: Int
    get() = requireContext().getPrimaryTextColor(isDarkTheme)

val Fragment.secondaryTextColor: Int
    get() = requireContext().getSecondaryTextColor(isDarkTheme)

val Fragment.primaryDisabledTextColor: Int
    get() = requireContext().getPrimaryDisabledTextColor(isDarkTheme)

val Fragment.secondaryDisabledTextColor: Int
    get() = requireContext().getSecondaryDisabledTextColor(isDarkTheme)

val Context.buttonDisabledColor: Int
    get() = if (isDarkTheme) {
        ContextCompat.getColor(this, R.color.md_dark_disabled)
    } else {
        ContextCompat.getColor(this, R.color.md_light_disabled)
    }

val Context.isDarkTheme: Boolean
    get() = ColorUtils.isColorLight(ThemeStore.primaryColor(this))

val Fragment.isDarkTheme: Boolean
    get() = requireContext().isDarkTheme

val Context.elevation: Float
    @SuppressLint("PrivateResource")
    get() {
        return if (AppConfig.elevation < 0) {
            ThemeUtils.resolveFloat(
                this,
                android.R.attr.elevation,
                resources.getDimension(com.google.android.material.R.dimen.design_appbar_elevation)
            )
        } else {
            AppConfig.elevation.toFloat().dpToPx()
        }
    }

val Context.filletBackground: GradientDrawable
    get() {
        val background = GradientDrawable()
        background.cornerRadius = 3f.dpToPx()
        background.setColor(backgroundColor)
        return background
    }

/**
 * 弹出菜单 / 下拉列表的玻璃底板。
 *
 * [为什么返回 GlassDrawable 而不是 GradientDrawable]
 * 调用方 `PopupWindow.applyMd3PopupStyle()` 和 `AutoCompleteTextView` 都是
 * 直接 `setBackground/setDropDownBackgroundDrawable`，只要求 Drawable 类型，
 * 换成玻璃材质对这两处完全透明 —— 不需要改任何调用点。
 *
 * [菜单的透光率取 82%，比对话框的 72% 更实]
 * 菜单项字号小、行数密，背后若是复杂列表，透太多会导致文字串行。
 * 圆角用 20dp 而非对话框的 26dp：菜单是小构件，大圆角会显得松垮。
 */
val Context.popupBackground: Drawable
    get() = if (AppConfig.isEInkMode) {
        GradientDrawable().apply {
            cornerRadius = 12f.dpToPx()
            setColor(bottomBackground)
        }
    } else {
        GlassDrawable(
            context = this,
            radius = resources.getDimension(R.dimen.lg_r_md),
            tint = ContextCompat.getColor(this, R.color.lg_glass_tint_menu),
            borderColor = ContextCompat.getColor(this, R.color.lg_glass_border),
            // 菜单挂在 PopupWindow 上，投影由 PopupWindow.elevation 负责，
            // 自己再画一层会和系统投影叠成双重黑影
            withShadow = false
        )
    }
