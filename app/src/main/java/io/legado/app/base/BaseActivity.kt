package io.legado.app.base

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.Theme
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyBackgroundTint
import io.legado.app.utils.applyOpenTint
import io.legado.app.utils.applyTint
import io.legado.app.utils.disableAutoFill
import io.legado.app.utils.fullScreen
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.installMd3OverflowMenu
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.windowSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class BaseActivity<VB : ViewBinding>(
    val fullScreen: Boolean = true,
    private val theme: Theme = Theme.Auto,
    private val toolBarTheme: Theme = Theme.Auto,
    private val transparent: Boolean = false,
    private val imageBg: Boolean = true,
    private val showOpenMenuIcon: Boolean = true
) : AppCompatActivity() {

    protected abstract val binding: VB

    val isInMultiWindow: Boolean
        @SuppressLint("ObsoleteSdkInt")
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                isInMultiWindowMode
            } else {
                false
            }
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppContextWrapper.wrap(newBase))
    }

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? {
        if (AppConst.menuViewNames.contains(name) && parent?.parent is FrameLayout) {
            (parent.parent as View).setBackgroundColor(backgroundColor)
        }
        return super.onCreateView(parent, name, context, attrs)?.also { view ->
            if (view is Toolbar) view.installMd3OverflowMenu()
        }
    }

    @SuppressLint("ObsoleteSdkInt")
    override fun onCreate(savedInstanceState: Bundle?) {
        window.decorView.disableAutoFill()
        initTheme()
        super.onCreate(savedInstanceState)
        if (!shouldCreateContentView()) return
        setupSystemBar()
        setupPredictiveBack()
        setContentView(binding.root)
        upBackgroundImage()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            findViewById<TitleBar>(R.id.title_bar)
                ?.onMultiWindowModeChanged(isInMultiWindowMode, fullScreen)
        }
        observeLiveBus()
        onActivityCreated(savedInstanceState)
    }

    /**
     * 注册返回回调接管返回操作,系统不再播放预测性返回动画
     */
    private fun setupPredictiveBack() {
        if (!AppConfig.disablePredictiveBack
            || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            OnBackInvokedCallback { onBackPressedDispatcher.onBackPressed() }
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        findViewById<TitleBar>(R.id.title_bar)
            ?.onMultiWindowModeChanged(isInMultiWindowMode, fullScreen)
        setupSystemBar()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        findViewById<TitleBar>(R.id.title_bar)
            ?.onMultiWindowModeChanged(isInMultiWindow, fullScreen)
        setupSystemBar()
    }

    abstract fun onActivityCreated(savedInstanceState: Bundle?)

    protected open fun shouldCreateContentView(): Boolean = true

    protected open fun shouldShowWindowBackground(): Boolean = true

    final override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val bool = onCompatCreateOptionsMenu(menu)
        val titleBar: TitleBar? = findViewById<TitleBar>(R.id.title_bar)
            ?: findViewById(R.id.titleBar)
        menu.applyTint(
            this,
            toolBarTheme,
            transparentBar = titleBar?.usesTransparentForeground == true
        )
        titleBar?.applyForegroundColor()
        titleBar?.toolbar?.installActivityOverflowMenu()
        return bool
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.applyOpenTint(this, showOpenMenuIcon)
        return super.onMenuOpened(featureId, menu)
    }

    open fun onCompatCreateOptionsMenu(menu: Menu) = super.onCreateOptionsMenu(menu)

    private fun Toolbar.installActivityOverflowMenu() {
        installMd3OverflowMenu(
            showIcons = showOpenMenuIcon,
            onPrepareMenu = { toolbarMenu -> onPrepareOptionsMenu(toolbarMenu) },
            onOpenCustomMenu = { toolbarMenu ->
                onMenuOpened(Window.FEATURE_OPTIONS_PANEL, toolbarMenu)
            },
            onShowCustomMenu = { anchor, toolbarMenu ->
                onShowActivityOverflowMenu(anchor, toolbarMenu)
            }
        )
    }

    /**
     * Gives an activity a chance to render its own overflow menu.
     *
     * Returning true means the shared popup has already been shown.
     */
    open fun onShowActivityOverflowMenu(anchor: View, menu: Menu): Boolean = false

    final override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            supportFinishAfterTransition()
            return true
        }
        return onCompatOptionsItemSelected(item)
    }

    open fun onCompatOptionsItemSelected(item: MenuItem) = super.onOptionsItemSelected(item)

    open fun initTheme() {
        when (theme) {
            Theme.Transparent -> setTheme(R.style.AppTheme_Transparent)
            Theme.Dark -> {
                setTheme(R.style.AppTheme_Dark)
                applyWindowBackground()
            }

            Theme.Light -> {
                setTheme(R.style.AppTheme_Light)
                applyWindowBackground()
            }

            else -> {
                if (ColorUtils.isColorLight(primaryColor)) {
                    setTheme(R.style.AppTheme_Light)
                } else {
                    setTheme(R.style.AppTheme_Dark)
                }
                applyWindowBackground()
            }
        }
    }

    private fun applyWindowBackground() {
        if (!shouldShowWindowBackground()) {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            return
        }
        /**
         * [全站页面的"纵深"统一在这里铺设]
         *
         * 所有主页面（书架/发现/RSS/我的）的根容器**都没有自己的背景**，
         * 它们直接透出这一层 DecorView 背景。所以这里是唯一能让全站
         * 同时获得纵深的入口 —— 改一处，50 个 Activity 一起生效。
         *
         * [为什么不能继续用纯色]
         * 玻璃的观感 90% 来自"背后有东西可透"。纯色底上叠半透白，
         * 结果只是一块浅一点的纯色，玻璃等于没做。上一版主界面之所以
         * 显得突兀，就是因为只有 Dock 是玻璃、页面底是死色。
         * 换成 lg_bg_page 那套"线性暖渐变 + 双光斑"后，
         * 玻璃底下有了明暗过渡，透出来才有材质感。
         *
         * [什么情况下不用渐变]
         * 用户自己设了背景图时，`upBackgroundImage()` 会在稍后把
         * DecorView 背景整体替换成那张图 —— 这里先铺渐变也不会冲突，
         * 但为了不留一帧闪烁，还是判断一下直接跳过。
         * 水墨屏模式同理：它的对比度要求不能有渐变。
         */
        val hasCustomBgImage = runCatching {
            ThemeConfig.getBgImage(this, resources.displayMetrics) != null
        }.getOrDefault(false)

        if (hasCustomBgImage || AppConfig.isEInkMode) {
            window.decorView.applyBackgroundTint(backgroundColor)
        } else {
            /**
             * [必须用 setBackground 而不是 setBackgroundColor]
             * applyBackgroundTint 的语义是"给现有背景染色"，纯色模式下
             * 它最终也只是一块色。渐变是 drawable，必须整体替换。
             * lg_bg_page 在 values/ 与 values-night/ 各有一份，
             * 系统按当前明暗自动选，日/夜切换天然同步。
             */
            window.decorView.setBackgroundResource(R.drawable.lg_bg_page)
        }
    }

    open fun upBackgroundImage() {
        if (!imageBg) return
        val metrics = windowManager.windowSize
        lifecycleScope.launch(Dispatchers.IO) {
            val drawable = try {
                ThemeConfig.getBgImage(this@BaseActivity, metrics)
            } catch (_: OutOfMemoryError) {
                toastOnUi("背景图片太大,内存溢出")
                null
            } catch (e: Exception) {
                AppLog.put("加载背景出错\n${e.localizedMessage}", e)
                null
            } ?: return@launch
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    window.decorView.background = drawable
                }
            }
        }
    }

    open fun setupSystemBar() {
        if (fullScreen && !isInMultiWindow) {
            fullScreen()
        }
        val isTransparentStatusBar = AppConfig.isTransparentStatusBar
        val statusBarColor = ThemeStore.statusBarColor(this, isTransparentStatusBar)
        setStatusBarColorAuto(statusBarColor, isTransparentStatusBar, fullScreen)
        if (toolBarTheme == Theme.Dark) {
            setLightStatusBar(false)
        } else if (toolBarTheme == Theme.Light) {
            setLightStatusBar(true)
        }
        upNavigationBarColor()
    }

    open fun upNavigationBarColor() {
        if (AppConfig.immNavigationBar) {
            setNavigationBarColorAuto(ThemeStore.navigationBarColor(this))
        } else {
            val nbColor = ColorUtils.darkenColor(ThemeStore.navigationBarColor(this))
            setNavigationBarColorAuto(nbColor)
        }
    }

    open fun observeLiveBus() {
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.dispatchTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            false
        }
    }

    override fun finish() {
        currentFocus?.hideSoftInput()
        super.finish()
    }
}
