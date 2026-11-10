package io.legado.app.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.WindowMetrics
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
import androidx.fragment.app.DialogFragment
import io.legado.app.R
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.dialog.TextDialog

inline fun <reified T : DialogFragment> AppCompatActivity.showDialogFragment(
    arguments: Bundle.() -> Unit = {}
) {
    @Suppress("DEPRECATION")
    val dialog = T::class.java.newInstance()
    val bundle = Bundle()
    bundle.apply(arguments)
    dialog.arguments = bundle
    dialog.show(supportFragmentManager, T::class.simpleName)
}

inline fun <reified T : DialogFragment> AppCompatActivity.dismissDialogFragment() {
    supportFragmentManager.fragments.forEach {
        if (it is T) {
            it.dismissAllowingStateLoss()
        }
    }
}

fun AppCompatActivity.showDialogFragment(dialogFragment: DialogFragment) {
    dialogFragment.show(supportFragmentManager, dialogFragment::class.simpleName)
}

val WindowManager.windowSize: DisplayMetrics
    get() {
        val displayMetrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics: WindowMetrics = currentWindowMetrics
            val insets = windowMetrics.windowInsets
                .getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
            val windowWidth = windowMetrics.bounds.width()
            val windowHeight = windowMetrics.bounds.height()
            var insetsWidth = insets.left + insets.right
            var insetsHeight = insets.top + insets.bottom
            if (windowWidth > windowHeight) {
                val tmp = insetsWidth
                insetsWidth = insetsHeight
                insetsHeight = tmp
            }
            displayMetrics.widthPixels = windowWidth - insetsWidth
            displayMetrics.heightPixels = windowHeight - insetsHeight
        } else {
            @Suppress("DEPRECATION")
            defaultDisplay.getMetrics(displayMetrics)
        }
        return displayMetrics
    }

@Suppress("DEPRECATION")
fun Activity.fullScreen() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.setDecorFitsSystemWindows(true)
    }
    window.decorView.systemUiVisibility =
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    window.clearFlags(
        WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
    )
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
}

/**
 * 设置状态栏颜色
 */
@Suppress("DEPRECATION")
fun Activity.setStatusBarColorAuto(
    @ColorInt color: Int,
    isTransparent: Boolean,
    fullScreen: Boolean
) {
    /**
     * [透明状态栏下的亮暗判据不能再用 color]
     *
     * color 在透明模式下传进来的是主色（深褐 #7x4x3x），
     * isColorLight(深褐) = false → 配上**白色**图标。
     * 但透明状态栏背后露出来的是米色页面底 —— 白图标在米底上几乎看不见。
     *
     * 正确判据是"图标实际压在什么颜色上"：
     *   · 透明 → 页面背景色 backgroundColor
     *   · 不透明 → 状态栏自身的颜色 color
     * 这才是决定图标该用黑还是白的真实依据。
     */
    val isLightBar = ColorUtils.isColorLight(
        if (isTransparent) backgroundColor else color
    )
    if (fullScreen) {
        if (isTransparent) {
            window.statusBarColor = Color.TRANSPARENT
        } else {
            window.statusBarColor = getCompatColor(R.color.status_bar_bag)
        }
    } else {
        window.statusBarColor = color
    }
    setLightStatusBar(isLightBar)
}

@SuppressLint("ObsoleteSdkInt")
fun Activity.setLightStatusBar(isLightBar: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.insetsController?.let {
            if (isLightBar) {
                it.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                it.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            }
        }
    }
    @Suppress("DEPRECATION")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val decorView = window.decorView
        val systemUiVisibility = decorView.systemUiVisibility
        if (isLightBar) {
            decorView.systemUiVisibility =
                systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            decorView.systemUiVisibility =
                systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }
}

/**
 * 设置导航栏颜色
 */
@Suppress("DEPRECATION")
fun Activity.setNavigationBarColorAuto(@ColorInt color: Int) {
    val isLightBor = ColorUtils.isColorLight(color)
    window.navigationBarColor = color
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.insetsController?.let {
            if (isLightBor) {
                it.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } else {
                it.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            }
        }
    }
    @Suppress("DEPRECATION")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val decorView = window.decorView
        var systemUiVisibility = decorView.systemUiVisibility
        systemUiVisibility = if (isLightBor) {
            systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        decorView.systemUiVisibility = systemUiVisibility
    }
}

fun Activity.keepScreenOn(on: Boolean) {
    val isScreenOn =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
    if (on == isScreenOn) return
    if (on) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

fun Activity.toggleSystemBar(show: Boolean) {
    WindowCompat.getInsetsController(window, window.decorView).run {
        if (show) {
            show(WindowInsetsCompat.Type.systemBars())
        } else {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

/////以下方法需要在View完全被绘制出来之后调用，否则判断不了,在比如 onWindowFocusChanged（）方法中可以得到正确的结果/////

/**
 * 返回NavigationBar
 */
val Activity.navigationBar: View?
    get() {
        val viewGroup = (window.decorView as? ViewGroup) ?: return null
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            val childId = child.id
            if (childId != View.NO_ID
                && resources.getResourceEntryName(childId) == "navigationBarBackground"
            ) {
                return child
            }
        }
        return null
    }

/**
 * 返回NavigationBar是否存在
 */
val Activity.isNavigationBarExist: Boolean
    get() = navigationBar != null

/**
 * 返回NavigationBar高度
 */
val Activity.navigationBarHeight: Int
    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    get() {
        if (isNavigationBarExist) {
            val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            return resources.getDimensionPixelSize(resourceId)
        }
        return 0
    }

/**
 * 返回navigationBar位置
 */
val Activity.navigationBarGravity: Int
    get() {
        val gravity = (navigationBar?.layoutParams as? FrameLayout.LayoutParams)?.gravity
        return gravity ?: Gravity.BOTTOM
    }

/**
 * 显示目录help下的帮助文档
 */
fun AppCompatActivity.showHelp(fileName: String) {
    val mdText = String(assets.open("web/help/md/${fileName}.md").readBytes())
    showDialogFragment(
        TextDialog(getString(R.string.help), mdText, TextDialog.Mode.MD, showToc = true)
    )
}
