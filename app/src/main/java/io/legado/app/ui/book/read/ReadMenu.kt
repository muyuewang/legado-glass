package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.databinding.ViewReadMenuBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.getSourceType
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.glass.GlassDrawable
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.buttonDisabledColor
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.model.SourceCallBack
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.ConstraintModify
import io.legado.app.utils.activity
import io.legado.app.utils.applyTint
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.loadAnimation
import io.legado.app.utils.modifyBegin
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.startActivity
import io.legado.app.utils.visible
import splitties.views.onClick
import splitties.views.onLongClick
import androidx.core.graphics.toColorInt
import io.legado.app.constant.BookType
import io.legado.app.utils.buildMainHandler

/**
 * 阅读界面菜单
 */
class ReadMenu @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var canShowMenu: Boolean = false
    private val callBack: CallBack get() = activity as CallBack
    private val binding = ViewReadMenuBinding.inflate(LayoutInflater.from(context), this, true)
    private val chapterNameTextSize = binding.tvChapterName.textSize
    private var confirmSkipToChapter: Boolean = false
    private var isMenuOutAnimating = false
    private val menuTopIn: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_in)
    }
    private val menuTopOut: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_out)
    }
    private val menuBottomIn: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_bottom_in)
    }
    private val menuBottomOut: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_bottom_out)
    }
    private val immersiveMenu: Boolean
        get() = AppConfig.readBarStyleFollowPage && ReadBookConfig.durConfig.curBgType() == 0
    private var bgColor: Int = if (immersiveMenu) {
        kotlin.runCatching {
            ReadBookConfig.durConfig.curBgStr().toColorInt()
        }.getOrDefault(context.bottomBackground)
    } else {
        context.bottomBackground
    }
    private var textColor: Int = if (immersiveMenu) {
        ReadBookConfig.durConfig.curTextColor()
    } else {
        context.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
    }

    private var bottomBackgroundList: ColorStateList = Selector.colorBuild()
        .setDefaultColor(bgColor)
        .setPressedColor(ColorUtils.darkenColor(bgColor))
        .create()
    private var onMenuOutEnd: (() -> Unit)? = null
    private val showBrightnessView
        get() = context.getPrefBoolean(
            PreferKey.showBrightnessView,
            true
        )
    private val menuInListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation) {
            binding.tvSourceAction.text =
                ReadBook.bookSource?.bookSourceName ?: context.getString(R.string.book_source)
            binding.tvSourceAction.isGone = ReadBook.isLocalBook
            ReadBook.bookSource?.let {
                if (it.customButton) {
                    binding.tvCustomBtn.visibility = VISIBLE
                }
            }
            callBack.upSystemUiVisibility()
            binding.llBrightness.visible(showBrightnessView)
        }

        @SuppressLint("RtlHardcoded")
        override fun onAnimationEnd(animation: Animation) {
            binding.vwMenuBg.setOnClickListener { runMenuOut() }
            callBack.upSystemUiVisibility()
            if (!LocalConfig.readMenuHelpVersionIsLast) {
                callBack.showHelp()
            }
        }

        override fun onAnimationRepeat(animation: Animation) = Unit
    }
    private val menuOutListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation) {
            isMenuOutAnimating = true
            binding.vwMenuBg.setOnClickListener(null)
        }

        override fun onAnimationEnd(animation: Animation) {
            this@ReadMenu.invisible()
            binding.titleBar.invisible()
            binding.bottomMenu.invisible()
            canShowMenu = false
            isMenuOutAnimating = false
            onMenuOutEnd?.invoke()
            callBack.upSystemUiVisibility()
        }

        override fun onAnimationRepeat(animation: Animation) = Unit
    }

    init {
        initView()
        upBrightnessState()
        bindEvent()
    }

    private fun initView(reset: Boolean = false) = binding.run {
        if (AppConfig.isNightTheme) {
            fabNightTheme.setImageResource(R.drawable.ic_daytime)
        } else {
            fabNightTheme.setImageResource(R.drawable.ic_brightness)
        }
        initAnimation()
        tvCustomBtn.setColorFilter(context.accentColor)
        if (immersiveMenu) {
            val lightTextColor = ColorUtils.withAlpha(ColorUtils.lightenColor(textColor), 0.75f)
            titleBar.setTextColor(textColor)
            /**
             * [原来是 setBackgroundColor(bgColor)]
             *
             * 这一行是阅读页顶部一直是"实色白条"的**真正原因**。
             * TitleBar 自己在 onAttachedToWindow 里已经把背景铺成玻璃了，
             * 但 ReadMenu.initView() 紧接着把它覆盖成 bgColor 实色 ——
             * 所以无论怎么改 TitleBar，阅读页顶部都不会变。
             *
             * 改成调用 TitleBar 暴露的玻璃入口，两处逻辑统一到同一个材质来源。
             * 阅读正文背后是纯色/图片底的正文，不是列表内容，但标题栏浮在正文上，
             * 用 opaque 档玻璃（94%/v31 72%）既保证章节名可读，又保留材质语言。
             */
            titleBar.applyGlassBackgroundNow()
            titleBar.setColorFilter(textColor)
            tvChapterName.setTextColor(lightTextColor)
            tvChapterUrl.setTextColor(lightTextColor)
        } else if (reset) {
            val bgColor = context.primaryColor
            val textColor = context.primaryTextColor
            titleBar.setTextColor(textColor)
            titleBar.setColorFilter(textColor)
            tvChapterName.setTextColor(textColor)
            tvChapterUrl.setTextColor(textColor)
        }
        /**
         * [铺玻璃必须放在两个分支**外面**]
         *
         * 这几行的位置踩过一个很隐蔽的坑，记下来：
         *
         * 上一版把 `applyGlassBackgroundNow()` 分别写在 immersiveMenu 分支和
         * reset 分支里，本意是"每次覆盖背景后补一次玻璃"。但这两个分支
         * 并不是二选一必然命中的：
         *   · immersiveMenu = readBarStyleFollowPage && 背景类型==纯色；
         *   · reset 只有切换主题/背景时才是 true（首次 init 是 false）。
         *
         * 本机（跟随系统/非纯色底）恰好是两个都不命中的组合 ——
         * 于是 `initView()` 从头到尾没碰过标题栏，标题栏用的是
         * TitleBar.onAttachedToWindow 里铺的 **panel 档**（97%）。
         *
         * 症状极具迷惑性：改成 99% 的 bar 档、
         * 重装、重启、截图，顶部看起来毫无变化，像素底色仍是 41（=42×0.97）。
         * 最后靠"把 bar 档临时刷成纯红"才定位到 —— 顶部一点红色都没有，
         * 证明它压根没走这个档位，而不是档位不够实。
         *
         * [为什么要无条件调用]
         * 阅读页顶栏永远压在正文上，无论沉浸式与否都需要 bar 档的可读性。
         * 提到分支外后，任何组合都能保证玻璃被铺上一次。
         */
        titleBar.applyGlassBackgroundNow()
        val brightnessBackground = GradientDrawable()
        brightnessBackground.cornerRadius = 5F.dpToPx()
        brightnessBackground.setColor(ColorUtils.adjustAlpha(bgColor, 0.5f))
        llBrightness.background = brightnessBackground
        if (AppConfig.isEInkMode) {
            titleBar.setBackgroundResource(R.drawable.bg_eink_border_bottom)
            llBottomBg.setBackgroundResource(R.drawable.bg_eink_border_top)
        } else {
            /**
             * [底部工具栏换成玻璃]
             *
             * 原来是 setBackgroundColor(bgColor) 实色米条，与整站的玻璃语言断开。
             * 这块区域上沿直接压着正文，做成玻璃后正文会从下面透上来，
             * 边界由顶部的亮边交代 —— 和 Dock 是同一套形态，只是方向相反。
             */
            llBottomBg.background = buildBottomGlass()
        }
        /**
         * [四个悬浮按钮也要玻璃化]
         *
         * 原来用 bottomBackgroundList（bgColor 实色 + 按下变深）做 backgroundTint。
         * FAB 是圆形，GlassDrawable 的圆角半径给到足够大就是正圆，
         * 于是按钮本身也变成一枚小玻璃片，浮在正文之上。
         * 按下态不再改色（玻璃片变色会显脏），交给 FAB 自带的 ripple 反馈。
         *
         * [每个按钮必须各建一个实例]
         * 曾想用 `fabGlass.constantState?.newDrawable()` 共享一份再派生，
         * 但 GlassDrawable 没有实现 constantState（自定义 Drawable 默认返回 null），
         * 那样取到的是 null，背景直接消失。这里改成逐个构建，代价可以忽略。
         */
        listOf(fabSearch, fabAutoPage, fabReplaceRule, fabNightTheme).forEach {
            it.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            it.background = buildFabGlass()
        }
        fabSearch.setColorFilter(textColor)
        fabAutoPage.setColorFilter(textColor)
        fabReplaceRule.setColorFilter(textColor)
        fabNightTheme.setColorFilter(textColor)
        val chapterTextColor = Selector.colorBuild()
            .setDefaultColor(textColor)
            .setDisabledColor(ColorUtils.withAlpha(textColor, 0.4f))
            .create()
        tvPre.setTextColor(chapterTextColor)
        tvNext.setTextColor(chapterTextColor)
        ivCatalog.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvCatalog.setTextColor(textColor)
        ivReadAloud.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvReadAloud.setTextColor(textColor)
        ivFont.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvFont.setTextColor(textColor)
        ivSetting.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvSetting.setTextColor(textColor)
        ivMemo.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvMemo.setTextColor(textColor)
        vwBrightnessPosAdjust.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        seekBrightness.applyTint(context.accentColor)
        llBrightness.setOnClickListener(null)
        seekBrightness.post {
            seekBrightness.progress = AppConfig.readBrightness
        }
        if (AppConfig.showReadTitleBarAddition) {
            titleBarAddition.visible()
        } else {
            titleBarAddition.gone()
        }
        updateTitleAdditionLayout()
        upBrightnessVwPos()
        /**
         * 确保视图不被导航栏遮挡
         */
        applyNavigationBarPadding()
    }

    fun reset() {
        upColorConfig()
        initView(true)
    }

    fun refreshMenuColorFilter() {
        if (immersiveMenu) {
            binding.titleBar.setColorFilter(textColor)
        }
    }

    private fun upColorConfig() {
        bgColor = if (immersiveMenu) {
            kotlin.runCatching {
                ReadBookConfig.durConfig.curBgStr().toColorInt()
            }.getOrDefault(context.bottomBackground)
        } else {
            context.bottomBackground
        }
        textColor = if (immersiveMenu) {
            ReadBookConfig.durConfig.curTextColor()
        } else {
            context.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
        }
        bottomBackgroundList = Selector.colorBuild()
            .setDefaultColor(bgColor)
            .setPressedColor(ColorUtils.darkenColor(bgColor))
            .create()
    }

    fun upBrightnessState() {
        if (brightnessAuto()) {
            binding.ivBrightnessAuto.setColorFilter(context.accentColor)
            binding.seekBrightness.isEnabled = false
        } else {
            binding.ivBrightnessAuto.setColorFilter(context.buttonDisabledColor)
            binding.seekBrightness.isEnabled = true
        }
        setScreenBrightness(AppConfig.readBrightness.toFloat())
    }

    /**
     * 系统亮度监听，在高阳光亮度时启用
     */
    private var contentObserver: ContentObserver? = null
    /**
     * 设置屏幕亮度
     */
    fun setScreenBrightness(value: Float) {
        activity?.run {
            fun setBrightness(value: Float) {
                val params = window.attributes
                params.screenBrightness = value
                window.attributes = params
            }
            val autoBrightness = BRIGHTNESS_OVERRIDE_NONE
            if (brightnessAuto() || value == autoBrightness) {
                setBrightness(autoBrightness)
                return
            }
            val brightness = if (value < 1f) 0.004f else value / 255f
            var isSunMax = false
            if (brightness == 1f) {
                val sysBrightness = getCurrentBrightness(context)
                if (sysBrightness == 255) {
                    isSunMax = true
                }
            }
            if (isSunMax) {
                contentObserver = object : ContentObserver(buildMainHandler()) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        super.onChange(selfChange, uri)
                        if (contentObserver == null) return
                        if (uri == Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)) {
                            val sysBrightness = getCurrentBrightness(context)
                            if (sysBrightness < 200) {
                                setBrightness(brightness)
                                contentObserver?.let {
                                    context.contentResolver.unregisterContentObserver(it)
                                }
                                contentObserver = null
                            } else if (sysBrightness < 255) {
                                setBrightness(brightness)
                            } else {
                                setBrightness(autoBrightness)
                            }
                        }
                    }
                }
                val brightnessUri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
                context.contentResolver.registerContentObserver(
                    brightnessUri,
                    false,
                    contentObserver!!
                )
                setBrightness(autoBrightness)
            } else {
                setBrightness(brightness)
            }
        }
    }

    /**
     * 获取系统亮度值
     */
    private fun getCurrentBrightness(context: Context): Int {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
        } catch (_: Settings.SettingNotFoundException) {
            -1
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        contentObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            contentObserver = null
        }
    }

    fun runMenuIn(anim: Boolean = !AppConfig.isEInkMode) {
        val showMemo = context.getPrefBoolean(PreferKey.showBookMemo, false)
        binding.llMemo.isVisible = showMemo
        binding.memoSpacer.isVisible = showMemo
        callBack.onMenuShow()
        this.visible()
        binding.titleBar.visible()
        binding.bottomMenu.visible()
        if (anim) {
            binding.titleBar.startAnimation(menuTopIn)
            binding.bottomMenu.startAnimation(menuBottomIn)
        } else {
            menuInListener.onAnimationStart(menuBottomIn)
            menuInListener.onAnimationEnd(menuBottomIn)
        }
    }

    fun runMenuOut(anim: Boolean = !AppConfig.isEInkMode, onMenuOutEnd: (() -> Unit)? = null) {
        if (isMenuOutAnimating) {
            return
        }
        callBack.onMenuHide()
        this.onMenuOutEnd = onMenuOutEnd
        if (this.isVisible) {
            if (anim) {
                binding.titleBar.startAnimation(menuTopOut)
                binding.bottomMenu.startAnimation(menuBottomOut)
            } else {
                menuOutListener.onAnimationStart(menuBottomOut)
                menuOutListener.onAnimationEnd(menuBottomOut)
            }
        }
    }

    private fun brightnessAuto(): Boolean {
        return context.getPrefBoolean("brightnessAuto", true) || !showBrightnessView
    }

    /**
     * 底部工具栏的玻璃材质。只圆上两角——它是贴在屏幕底边的浮层，
     * 下两角会被系统导航栏吃掉，圆了也看不见，反而在横屏时露出缺口。
     *
     * [必须用 bar 档，不能用 soft]
     * soft（30%）是给标题栏那种"背后是大片留白"的场景用的。
     * 这里背后是**满屏正文**：实测用 soft 时，正文"不过人类从来没有放弃过探索"
     * 直接穿上来，和栏内的"目录/朗读/界面/设置"糊成重影。
     * 改用 bar 档（97%）后正文被压住，只留极淡的底色过渡。
     */
    private fun buildBottomGlass(): GlassDrawable = GlassDrawable(
        context = context,
        radius = context.resources.getDimension(R.dimen.lg_r_lg),
        tint = androidx.core.content.ContextCompat.getColor(context, R.color.lg_glass_tint_bar),
        borderColor = androidx.core.content.ContextCompat.getColor(context, R.color.lg_glass_border),
        withShadow = false,
        roundTopOnly = true
    )

    /**
     * 悬浮按钮的玻璃材质。
     *
     * FAB 是正圆，这里把圆角半径给到 FAB 自身尺寸的一半以上（取 999dp 上限），
     * GlassDrawable 的 addRoundRect 会自动按边长夹取，画出来就是正圆。
     */
    private fun buildFabGlass(): GlassDrawable = GlassDrawable(
        context = context,
        radius = 999f.dpToPx(),
        tint = androidx.core.content.ContextCompat.getColor(context, R.color.lg_glass_tint_menu),
        borderColor = androidx.core.content.ContextCompat.getColor(context, R.color.lg_glass_border),
        withShadow = false
    )

    private fun bindEvent() = binding.run {
        vwMenuBg.setOnClickListener { runMenuOut() }
        titleBar.toolbar.setOnClickListener {
            callBack.openBookInfoActivity()
        }
        val chapterViewClickListener = OnClickListener {
            if (ReadBook.isLocalBook) {
                return@OnClickListener
            }
            if (AppConfig.readUrlInBrowser) {
                context.openUrl(tvChapterUrl.text.toString().substringBefore(",{"))
            } else {
                Coroutine.async {
                    context.startActivity<WebViewActivity> {
                        val url = tvChapterUrl.text.toString()
                        val bookSource = ReadBook.bookSource
                        putExtra("title", tvChapterName.text)
                        putExtra("url", url)
                        putExtra("sourceOrigin", bookSource?.bookSourceUrl)
                        putExtra("sourceName", bookSource?.bookSourceName)
                        putExtra("sourceType", bookSource?.getSourceType())
                    }
                }
            }
        }
        val chapterViewLongClickListener = OnLongClickListener {
            if (ReadBook.isLocalBook) {
                return@OnLongClickListener true
            }
            context.alert(R.string.open_fun) {
                setMessage(R.string.use_browser_open)
                okButton {
                    AppConfig.readUrlInBrowser = true
                }
                noButton {
                    AppConfig.readUrlInBrowser = false
                }
            }
            true
        }
        tvChapterName.setOnClickListener(chapterViewClickListener)
        tvChapterName.setOnLongClickListener(chapterViewLongClickListener)
        tvChapterUrl.setOnClickListener(chapterViewClickListener)
        tvChapterUrl.setOnLongClickListener(chapterViewLongClickListener)
        tvCustomBtn.setOnClickListener {
            val book = ReadBook.book ?: return@setOnClickListener
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
            activity?.let { activity ->
                SourceCallBack.callBackBtn(
                    activity,
                    SourceCallBack.CLICK_CUSTOM_BUTTON,
                    ReadBook.bookSource,
                    book,
                    chapter,
                    BookType.text
                )
            }
        }
        tvCustomBtn.setOnLongClickListener {
            val book = ReadBook.book ?: return@setOnLongClickListener true
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
            activity?.let { activity ->
                SourceCallBack.callBackBtn(
                    activity,
                    SourceCallBack.LONG_CLICK_CUSTOM_BUTTON,
                    ReadBook.bookSource,
                    book,
                    chapter,
                    BookType.text
                )
            }
            true
        }
        //书源操作
        tvSourceAction.onClick {
            val hasLogin = ReadBook.bookSource?.hasLogin() == true
            val canPay = hasLogin
                    && ReadBook.curTextChapter?.isVip == true
                    && ReadBook.curTextChapter?.isPay != true
            popupActionMenu(context) {
                item(context.getString(R.string.login), "login", hasLogin)
                item(context.getString(R.string.chapter_pay), "chapterPay", canPay)
                item(context.getString(R.string.edit_book_source), "editSource")
                item(context.getString(R.string.disable_book_source), "disableSource")
            }.show(tvSourceAction) { action ->
                when (action) {
                    "login" -> callBack.showLogin()
                    "chapterPay" -> callBack.payAction()
                    "editSource" -> callBack.openSourceEditActivity()
                    "disableSource" -> callBack.disableSource()
                }
            }
        }
        //亮度跟随
        ivBrightnessAuto.setOnClickListener {
            context.putPrefBoolean("brightnessAuto", !brightnessAuto())
            upBrightnessState()
        }
        //亮度调节
        seekBrightness.setOnSeekBarChangeListener(object : SeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    setScreenBrightness(progress.toFloat())
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                AppConfig.readBrightness = seekBar.progress
            }

        })
        vwBrightnessPosAdjust.setOnClickListener {
            AppConfig.brightnessVwPos = !AppConfig.brightnessVwPos
            upBrightnessVwPos()
        }
        //阅读进度
        seekReadPage.setOnSeekBarChangeListener(object : SeekBarChangeListener {

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                binding.vwMenuBg.setOnClickListener(null)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                binding.vwMenuBg.setOnClickListener { runMenuOut() }
                when (AppConfig.progressBarBehavior) {
                    "page" -> ReadBook.skipToPage(seekBar.progress)
                    "chapter" -> {
                        if (confirmSkipToChapter) {
                            callBack.skipToChapter(seekBar.progress)
                        } else {
                            context.alert("章节跳转确认", "确定要跳转章节吗？") {
                                yesButton {
                                    confirmSkipToChapter = true
                                    callBack.skipToChapter(seekBar.progress)
                                }
                                noButton {
                                    upSeekBar()
                                }
                                onCancelled {
                                    upSeekBar()
                                }
                            }
                        }
                    }
                }
            }

        })

        //搜索
        fabSearch.setOnClickListener {
            runMenuOut {
                callBack.openSearchActivity(null)
            }
        }

        //自动翻页
        fabAutoPage.setOnClickListener {
            runMenuOut {
                callBack.autoPage()
            }
        }

        //替换
        fabReplaceRule.setOnClickListener { callBack.openReplaceRule() }

        //夜间模式
        fabNightTheme.setOnClickListener {
            AppConfig.isNightTheme = !AppConfig.isNightTheme
            ThemeConfig.applyDayNight(context)
        }

        //上一章
        tvPre.setOnClickListener { ReadBook.moveToPrevChapter(upContent = true, toLast = false) }

        //下一章
        tvNext.setOnClickListener { ReadBook.moveToNextChapter(true) }

        //目录
        llCatalog.setOnClickListener {
            runMenuOut {
                callBack.openChapterList()
            }
        }

        //朗读
        llReadAloud.setOnClickListener {
            runMenuOut {
                if (BaseReadAloudService.isRun) {
                    callBack.showReadAloudDialog()
                } else {
                    callBack.onClickReadAloud()
                }
            }
        }
        llReadAloud.onLongClick {
            runMenuOut {
                callBack.showReadAloudDialog()
            }
        }
        //界面
        llFont.setOnClickListener {
            runMenuOut {
                callBack.showReadStyle()
            }
        }

        //设置
        llSetting.setOnClickListener {
            runMenuOut {
                callBack.showMoreSetting()
            }
        }
        llMemo.setOnClickListener {
            runMenuOut {
                callBack.showBookMemo()
            }
        }
    }

    private fun initAnimation() {
        menuTopIn.setAnimationListener(menuInListener)
        menuTopOut.setAnimationListener(menuOutListener)
    }

    fun upBookView() {
        binding.titleBar.title = ReadBook.book?.name
        ReadBook.curTextChapter?.let {
            binding.tvChapterName.text = it.title
            binding.tvChapterName.visible()
            if (!ReadBook.isLocalBook) {
                binding.tvChapterUrl.text = it.chapter.getAbsoluteURL()
            } else {
                binding.tvChapterUrl.text = null
                binding.tvChapterUrl.gone()
            }
            updateTitleAdditionLayout()
            upSeekBar()
            binding.tvPre.isEnabled = ReadBook.durChapterIndex != 0
            binding.tvNext.isEnabled = ReadBook.durChapterIndex != ReadBook.simulatedChapterSize - 1
        } ?: let {
            binding.tvChapterName.gone()
            binding.tvChapterUrl.gone()
        }
    }

    private fun updateTitleAdditionLayout() = binding.run {
        val chapterNameOnly = AppConfig.showReadTitleChapterNameOnly
        val scaledDensity = resources.displayMetrics.scaledDensity
        val hasChapterUrl = !tvChapterUrl.text.isNullOrBlank()
        tvChapterName.gravity = Gravity.CENTER_VERTICAL
        tvChapterUrl.gravity = Gravity.CENTER_VERTICAL
        tvChapterName.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            chapterNameTextSize + if (chapterNameOnly) 2f * scaledDensity else 0f
        )
        tvChapterUrl.alpha = if (chapterNameOnly && hasChapterUrl) 0f else 1f
        if (hasChapterUrl) {
            tvChapterUrl.visible()
        } else {
            tvChapterUrl.gone()
        }
        ConstraintSet().apply {
            clone(titleBarAddition)
            val bottomTarget = if (tvChapterUrl.isGone) {
                R.id.tv_chapter_name
            } else {
                R.id.tv_chapter_url
            }
            connect(R.id.tv_custom_btn, ConstraintSet.BOTTOM, bottomTarget, ConstraintSet.BOTTOM)
            connect(R.id.tv_source_action, ConstraintSet.BOTTOM, bottomTarget, ConstraintSet.BOTTOM)
            applyTo(titleBarAddition)
        }
        tvChapterName.translationY = 0f
        if (chapterNameOnly && tvChapterName.isVisible) {
            titleBarAddition.doOnLayout {
                tvChapterName.translationY =
                    (titleBarAddition.height - tvChapterName.height) / 2f - tvChapterName.top
            }
        }
    }

    fun upSeekBar() {
        binding.seekReadPage.apply {
            when (AppConfig.progressBarBehavior) {
                "page" -> {
                    ReadBook.curTextChapter?.let {
                        max = it.pageSize.minus(1)
                        progress = ReadBook.durPageIndex
                    }
                }

                "chapter" -> {
                    max = ReadBook.simulatedChapterSize - 1
                    progress = ReadBook.durChapterIndex
                }
            }
        }
    }

    fun setSeekPage(seek: Int) {
        binding.seekReadPage.progress = seek
    }

    fun setAutoPage(autoPage: Boolean) = binding.run {
        if (autoPage) {
            fabAutoPage.setImageResource(R.drawable.ic_auto_page_stop)
            fabAutoPage.contentDescription = context.getString(R.string.auto_next_page_stop)
        } else {
            fabAutoPage.setImageResource(R.drawable.ic_auto_page)
            fabAutoPage.contentDescription = context.getString(R.string.auto_next_page)
        }
        fabAutoPage.setColorFilter(textColor)
    }

    private fun upBrightnessVwPos() {
        if (AppConfig.brightnessVwPos) {
            binding.root.modifyBegin()
                .clear(R.id.ll_brightness, ConstraintModify.Anchor.LEFT)
                .rightToRightOf(R.id.ll_brightness, R.id.vw_menu_root)
                .commit()
        } else {
            binding.root.modifyBegin()
                .clear(R.id.ll_brightness, ConstraintModify.Anchor.RIGHT)
                .leftToLeftOf(R.id.ll_brightness, R.id.vw_menu_root)
                .commit()
        }
    }

    interface CallBack {
        fun autoPage()
        fun openReplaceRule()
        fun openChapterList()
        fun openSearchActivity(searchWord: String?)
        fun openSourceEditActivity()
        fun openBookInfoActivity()
        fun showReadStyle()
        fun showMoreSetting()
        fun showBookMemo()
        fun showReadAloudDialog()
        fun upSystemUiVisibility()
        fun onClickReadAloud()
        fun showHelp()
        fun showLogin()
        fun payAction()
        fun disableSource()
        fun skipToChapter(index: Int)
        fun onMenuShow()
        fun onMenuHide()
    }

}
