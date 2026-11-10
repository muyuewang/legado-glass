package io.legado.app.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Menu
import android.view.View
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.alpha
import androidx.core.view.forEach
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import androidx.core.content.ContextCompat
import io.legado.app.lib.glass.GlassDrawable
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.getToolbarTextColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.activity
import io.legado.app.utils.applyTint
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import splitties.views.bottomPadding
import splitties.views.topPadding

@Suppress("unused", "MemberVisibilityCanBePrivate")
class TitleBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppBarLayout(context, attrs) {

    val toolbar: Toolbar
    val menu: Menu
        get() = toolbar.menu

    var title: CharSequence?
        get() = toolbar.title
        set(title) {
            if (toolbar.title != title) {
                toolbar.title = title
            }
        }

    var subtitle: CharSequence?
        get() = toolbar.subtitle
        set(subtitle) {
            if (toolbar.subtitle != subtitle) {
                toolbar.subtitle = subtitle
            }
        }

    private val displayHomeAsUp: Boolean
    private val navigationDescription: CharSequence
    private val navigationIconTint: ColorStateList?
    private val navigationIconTintMode: Int
    private val fitStatusBar: Boolean
    private val fitNavigationBar: Boolean
    private val attachToActivity: Boolean
    private val opaque: Boolean
    private val automaticForeground: Boolean
    private val titleTextColorFromAttrs: Boolean
    private val subtitleTextColorFromAttrs: Boolean

    /**
     * 外部是否已显式要求过 bar 档（叠在满屏正文上的条栏专用，99%）。
     *
     * [为什么必须记这个状态，而不能每次按 opaque 推]
     * 本类有三个会重铺玻璃的时机，且**顺序不固定**：
     *   ① init 阶段（主题未就绪）；
     *   ② onAttachedToWindow（重新按最终主题判一次）；
     *   ③ 外部调用 [applyGlassBackgroundNow]（ReadMenu / SearchMenu 覆盖背景后）。
     *
     * ②在③之前还是之后，取决于 Fragment 生命周期与菜单初始化谁先跑完。
     * 上一版只在③里传 barMode=true，结果②晚一步执行时又把它改回 panel 档 ——
     * 实机表现为"改成 99% 了，标题栏还是能透出正文"，
     * 像素实测透光率约 6%（panel 94% 的量级），与 99% 应有的 1% 差了一个数量级。
     *
     * 记成字段后，②重算时会读到③设过的值，两者不再互相打架。
     */
    private var wantBarTint = false

    init {
        val a = context.obtainStyledAttributes(
            attrs, R.styleable.TitleBar,
            R.attr.titleBarStyle, 0
        )
        navigationIconTint = a.getColorStateList(R.styleable.TitleBar_navigationIconTint)
        navigationIconTintMode = a.getInt(R.styleable.TitleBar_navigationIconTintMode, 9)
        attachToActivity = a.getBoolean(R.styleable.TitleBar_attachToActivity, true)
        displayHomeAsUp = a.getBoolean(R.styleable.TitleBar_displayHomeAsUp, true)
        fitStatusBar = a.getBoolean(R.styleable.TitleBar_fitStatusBar, true)
        fitNavigationBar = a.getBoolean(R.styleable.TitleBar_fitNavigationBar, false)
        opaque = a.getBoolean(R.styleable.TitleBar_opaque, false)
        val themeMode = a.getInt(R.styleable.TitleBar_themeMode, 0)
        automaticForeground = themeMode == 0 && !opaque

        val navigationIcon = a.getDrawable(R.styleable.TitleBar_navigationIcon)
        navigationDescription =
            a.getText(R.styleable.TitleBar_navigationContentDescription)
                ?: context.getText(R.string.back)
        val titleText = a.getString(R.styleable.TitleBar_title)
        val subtitleText = a.getString(R.styleable.TitleBar_subtitle)
        titleTextColorFromAttrs = a.hasValue(R.styleable.TitleBar_titleTextColor)
        subtitleTextColorFromAttrs = a.hasValue(R.styleable.TitleBar_subtitleTextColor)

        when (themeMode) {
            1 -> inflate(context, R.layout.view_title_bar_dark, this)
            else -> inflate(context, R.layout.view_title_bar, this)
        }
        toolbar = findViewById(R.id.toolbar)

        toolbar.apply {
            navigationIcon?.let {
                this.navigationIcon = it
                this.navigationContentDescription = navigationDescription
            }

            if (a.hasValue(R.styleable.TitleBar_titleTextAppearance)) {
                this.setTitleTextAppearance(
                    context,
                    a.getResourceId(R.styleable.TitleBar_titleTextAppearance, 0)
                )
            }

            if (titleTextColorFromAttrs) {
                this.setTitleTextColor(a.getColor(R.styleable.TitleBar_titleTextColor, -0x1))
            }

            if (a.hasValue(R.styleable.TitleBar_subtitleTextAppearance)) {
                this.setSubtitleTextAppearance(
                    context,
                    a.getResourceId(R.styleable.TitleBar_subtitleTextAppearance, 0)
                )
            }

            if (subtitleTextColorFromAttrs) {
                this.setSubtitleTextColor(a.getColor(R.styleable.TitleBar_subtitleTextColor, -0x1))
            }


            if (a.hasValue(R.styleable.TitleBar_contentInsetLeft)
                || a.hasValue(R.styleable.TitleBar_contentInsetRight)
            ) {
                this.setContentInsetsAbsolute(
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetLeft, 0),
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetRight, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetStart)
                || a.hasValue(R.styleable.TitleBar_contentInsetEnd)
            ) {
                this.setContentInsetsRelative(
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetStart, 0),
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetEnd, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetStartWithNavigation)) {
                this.contentInsetStartWithNavigation = a.getDimensionPixelOffset(
                    R.styleable.TitleBar_contentInsetStartWithNavigation, 0
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetEndWithActions)) {
                this.contentInsetEndWithActions = a.getDimensionPixelOffset(
                    R.styleable.TitleBar_contentInsetEndWithActions, 0
                )
            }

            if (!titleText.isNullOrBlank()) {
                this.title = titleText
            }

            if (!subtitleText.isNullOrBlank()) {
                this.subtitle = subtitleText
            }

            if (a.hasValue(R.styleable.TitleBar_contentLayout)) {
                inflate(context, a.getResourceId(R.styleable.TitleBar_contentLayout, 0), this)
            }
        }

        if (!isInEditMode) {
//            if (fitStatusBar) {
//                setPadding(paddingLeft, context.statusBarHeight, paddingRight, paddingBottom)
//            }
//
//            if (fitNavigationBar) {
//                setPadding(paddingLeft, paddingTop, paddingRight, context.navigationBarHeight)
//            }

            if (fitStatusBar || fitNavigationBar) {
                setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
                    val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                    if (fitStatusBar) {
                        topPadding = insets.top
                    }
                    if (fitNavigationBar) {
                        bottomPadding = insets.bottom
                    }
                    windowInsets
                }
            }

            if (AppConfig.isEInkMode) {
                setBackgroundResource(R.drawable.bg_eink_border_bottom)
            } else if (!opaque && context.transparentNavBar) {
                /**
                 * [全站顶部只改这一处]
                 *
                 * 书架、发现、RSS、我的——所有主页面的布局里写的都是同一个
                 * `io.legado.app.ui.widget.TitleBar`，所以这里是顶部区域
                 * **唯一**的公共入口。改它一处，全站标题栏一起变成玻璃，
                 * 不需要去动 30 多个 fragment 布局。
                 *
                 * [原来是 setBackgroundColor(TRANSPARENT)]
                 * 纯透明在暖米渐变底上等于"没有边界"，标题文字和下面的
                 * 列表直接连成一片，缺少浮层该有的分隔感。
                 * 换成玻璃材质后：半透明 tint 让底下的渐变色透上来，
                 * 下沿一道亮边勾出边界，顶部内高光暗示厚度——
                 * 与 Dock、弹窗是同一套材质语言。
                 */
                applyGlassBackground()
            } else if (opaque) {
                /**
                 * [opaque 分支原来是 setBackgroundColor(primaryColor)]
                 *
                 * opaque=true 的意图是"内容会滚到标题栏后方，标题必须始终可读"，
                 * 所以不能像主页面那样用 14% 的 soft 档——那样正文会直接穿过标题栏。
                 *
                 * 但用 primaryColor 实色有两个问题：
                 *   ① primaryColor 是从旧主题体系继承来的棕褐色，
                 *      与全站的暖米渐变 + 青色强调完全不在一个色系，
                 *      RSS 阅读页打开后顶部就是一道突兀的棕色横条；
                 *   ② 它把页面纵深切断了，玻璃语言在这个页面整段消失。
                 *
                 * 折中方案：走玻璃，但**必须用 bar 档而不是 panel 档**。
                 *
                 * [这里踩过一个坑，值得记下来]
                 * 上一版写的是 `opaqueMode` → `lg_glass_tint_panel`（94%），
                 * 当时以为"94% 已经够实了"。实机截图却显示阅读页顶部
                 * 仍然能和正文"第一章 星空中的青铜巨棺"叠成重影 ——
                 * 因为 opaque 页面的标题栏下面压的是**满屏密集黑字**，
                 * 94% 剩余 6% 的透光在近黑正文上留下 14 个色阶的灰影，
                 * 比列表页（背后大片留白）恶劣得多。
                 *
                 * bar 档是专门为这种"叠在正文上"的条栏开的（99%），
                 * 与阅读页底部工具栏共用同一个数值来源，两处观感才一致。
                 * 圆角保持四角全圆——这类页面标题栏不与屏幕顶边贴合。
                 */
                applyGlassBackground(opaqueMode = true)
            } else {
                setBackgroundColor(context.primaryColor)
                elevation = context.elevation
            }

            stateListAnimator = null
        }
        a.recycle()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        /**
         * [附加到窗口后必须重算一次玻璃]
         *
         * 原因和前面那次修复"白色文字"是同一个：init 阶段主题尚未就绪。
         * `context.transparentNavBar` 在构造时可能读到 false，于是走了
         * primaryColor 分支；等主题初始化完成，背景已经是实色了。
         * 这里在真正挂载后按"最终状态"再判一次：
         * 该玻璃的补玻璃，该实色的保持实色。
         */
        if (!AppConfig.isEInkMode) {
            if (opaque) {
                // opaque 页面也要重算：init 阶段主题没就绪，之前会落到 primaryColor 实色分支
                // [必须带上 wantBarTint] 否则外部已指定的 bar 档会在这里被改回 panel 档
                applyGlassBackground(opaqueMode = true, barMode = wantBarTint)
            } else if (context.transparentNavBar) {
                applyGlassBackground()
            }
        }
        attachToActivity()
        /**
         * [触发条件必须和 usesTransparentForeground 一致]
         *
         * 原来是 `if (automaticForeground)`，而 automaticForeground 在 opaque 页面
         * 恒为 false（它等于 themeMode==0 && !opaque）。opaque 页面改用玻璃背景后，
         * 白色标题会直接糊在浅色玻璃上——这个 if 就是最后一道闸门，必须放开。
         */
        if (usesTransparentForeground) {
            post { applyForegroundColor() }
        }
    }

    /**
     * 给标题栏铺玻璃材质。
     *
     * [为什么标题栏不做真实模糊]
     * 技术上可以做（GlassPanel 那套截屏+模糊），但标题栏是**每个页面常驻**的：
     * 书架、发现、RSS、我的，切 tab 就重建一次。截屏 + 高斯模糊单次 8~15ms，
     * 放在常驻组件上，每次页面切换都会有一帧明显停顿。
     * 而标题栏背后通常只是一片渐变底色（列表内容在它下方，不在它背后），
     * 糊与不糊的视觉差异极小 —— 用纯材质（tint + 边框 + 内高光）已经够了。
     * 这是"玻璃只给浮层、且该省的成本要省"的取舍。
     *
     * [为什么用 soft 档 tint]
     * 标题栏上方压着状态栏时间、下方紧接着页面内容，两侧都是信息。
     * 用 Dock 的 strong 档会让顶部显出一块厚实的白条，把页面切得太碎；
     * soft 档（14%）只做轻微提亮，让渐变透上来，边界交给下沿亮边去交待。
     */
    private fun applyGlassBackground(opaqueMode: Boolean = false, barMode: Boolean = false) {
        /**
         * [三档 tint，按"背后压着什么"分，不按页面类型分]
         *
         *   · soft  —— 主页面标题栏：背后是页面渐变底，没有内容滚到它后面；
         *   · panel —— opaque 页面（代码编辑器 / RSS 阅读 / 网页视图）：
         *              背后可能有内容，但多为稀疏的文本块或白色网页底；
         *   · bar   —— 叠在**满屏密集正文**上的条栏（阅读页顶栏、底栏）：
         *              最恶劣的透光场景，必须接近完全不透明。
         *
         * [为什么 barMode 只由调用方显式指定，不在本类里自动判断]
         * opaque=true 的页面有六七个，其中只有阅读页那条是真的压着满屏黑字；
         * 其余几个（编辑器、网页视图）用 bar 档会显得突兀地厚。
         * 让"谁知道场景，谁传参数"，比在本类里猜布局结构可靠。
         */
        val tintRes = when {
            barMode -> R.color.lg_glass_tint_bar
            opaqueMode -> R.color.lg_glass_tint_panel
            else -> R.color.lg_glass_tint_soft
        }
        val glass = GlassDrawable(
            context = context,
            radius = resources.getDimension(R.dimen.lg_r_md),
            tint = ContextCompat.getColor(context, tintRes),
            borderColor = ContextCompat.getColor(context, R.color.lg_glass_border),
            // 投影关掉：AppBarLayout 自身还有 stateListAnimator/elevation 的机制，
            // 两层阴影叠起来会在标题栏下方形成一道发灰的脏边
            withShadow = false,
            // 主页面贴着屏幕顶边，只圆下两角；opaque 页面（编辑器、RSS 阅读、
            // 网页视图）标题栏下方还有内容，四角全圆才不会在下沿露出直角
            roundBottomOnly = !opaqueMode
        )
        setBackground(glass)
        /**
         * [elevation 必须归零]
         * AppBarLayout 默认带 elevation，会给背景再叠一层系统的灰影。
         * 玻璃的层次由自己的 tint 和边框提供，不需要这层 —— 留着就是脏。
         */
        elevation = 0f
    }

    /**
     * 供外部（阅读菜单 ReadMenu / 搜索菜单 SearchMenu）重新铺玻璃。
     *
     * [为什么必须有这个入口]
     * TitleBar 在 onAttachedToWindow 里已经把背景铺成玻璃了，但 ReadMenu.initView()
     * 随后调用 `titleBar.setBackgroundColor(bgColor)` 把它**覆盖**成实色 ——
     * 这正是阅读页顶部一直是白条的真正原因（不是漏改，是被改回去了）。
     * 外部既然要改背景，就得走同一个材质来源，否则两处逻辑永远在打架。
     */
    fun applyGlassBackgroundNow() {
        if (AppConfig.isEInkMode) return
        /**
         * [只有 opaque 页面才升级到 bar 档]
         *
         * 主页面标题栏（书架/发现/RSS/我的）背后是页面渐变底，
         * 没有内容滚到它后面，用 soft 档才有"玻璃浮在渐变上"的观感；
         * 这里如果无条件设 wantBarTint=true，主页面标题栏会变成一块
         * 几乎不透光的白板，把全站的分隔感做没了。
         */
        wantBarTint = opaque
        applyGlassBackground(opaqueMode = opaque, barMode = wantBarTint)
    }

    /**
     * 是否应使用"透明背景上的前景色"。
     *
     * [为什么不再看 background?.alpha]
     * 原来写的是 `background?.alpha == 0`，依赖背景 drawable 的瞬时状态。
     * 但 TitleBar 是在 init 里按当时的 transparentNavBar 决定背景的，
     * 而主题初始化晚于 View 构造 —— 那一刻读到 false 就会把背景设成实色，
     * 之后即使主题就绪，background.alpha 也永远不为 0，
     * 这个方法就**永久跳过**，标题、tab、菜单图标全都停在主题默认的白色上。
     * 实测就是这个现象：状态栏图标已变黑，顶部文字却仍是白的。
     *
     * 改为直接看配置源（与设置背景用的是同一个判据），两者必定一致。
     *
     * [opaque 页面也必须参与]
     * 原来这里排除了 opaque，理由是"背景是 primaryColor 实色，前景由主题定好了"。
     * 现在 opaque 页面也换成了玻璃材质，浅色玻璃上再画白色标题就是白底白字。
     * 判据里补上 opaque，让它们同样按页面底色重新算一次前景色。
     */
    val usesTransparentForeground: Boolean
        get() = (automaticForeground || opaque) && context.transparentNavBar && !AppConfig.isEInkMode

    fun applyForegroundColor() {
        if (!usesTransparentForeground) return
        val color = context.getToolbarTextColor(true)
        if (!titleTextColorFromAttrs) {
            setTitleTextColor(color)
        }
        if (!subtitleTextColorFromAttrs) {
            setSubTitleTextColor(color)
        }
        val colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        toolbar.navigationIcon?.colorFilter = colorFilter
        toolbar.overflowIcon?.colorFilter = colorFilter
        toolbar.findViewById<SearchView>(R.id.search_view)?.applyTint(color)

        /**
         * [菜单项图标也必须单独着色]
         *
         * 原来只处理了 navigationIcon / overflowIcon 两个"工具栏自带"的图标，
         * 漏掉了 menu 里的动作项 —— 比如书架的搜索图标（showAsAction="always"）。
         * 这些图标由 Toolbar 按主题色绘制，透明背景下保持白色，在米色底上看不见。
         * 下面统一给每个动作项的 icon 套同一层 colorFilter。
         */
        toolbar.menu.forEach { item ->
            item.icon?.mutate()?.colorFilter = colorFilter
        }
        val tabUnselectedColor = context.getCompatColor(
            if (ColorUtils.isColorLight(context.backgroundColor)) {
                R.color.md_light_secondary
            } else {
                R.color.md_dark_secondary
            }
        )
        /**
         * [tab_layout 不在 toolbar 里，必须从 TitleBar 本体上找]
         *
         * tab 是通过 `app:contentLayout="@layout/view_tab_layout_min"` 挂到
         * TitleBar 自身的（见 TitleBar.init 的 inflate(contentLayout, this)），
         * 它是 toolbar 的**兄弟节点**，不是子节点。
         * 原来写成 toolbar.findViewById 永远返回 null ——
         * tab 文字保持主题默认的白色，在米色背景上几乎看不见。
         */
        findViewById<TabLayout>(R.id.tab_layout)
            ?.setTabTextColors(tabUnselectedColor, color)
        toolbar.menu.forEach { item ->
            (item.actionView as? SearchView)?.applyTint(color)
        }
    }

    fun setNavigationOnClickListener(clickListener: ((View) -> Unit)) {
        toolbar.setNavigationOnClickListener(clickListener)
    }

    fun setTitle(titleId: Int) {
        toolbar.setTitle(titleId)
    }

    fun setSubTitle(subtitleId: Int) {
        toolbar.setSubtitle(subtitleId)
    }

    fun setTitleTextColor(@ColorInt color: Int) {
        toolbar.setTitleTextColor(color)
    }

    fun setTitleTextAppearance(@StyleRes resId: Int) {
        toolbar.setTitleTextAppearance(context, resId)
    }

    fun setSubTitleTextColor(@ColorInt color: Int) {
        toolbar.setSubtitleTextColor(color)
    }

    fun setSubTitleTextAppearance(@StyleRes resId: Int) {
        toolbar.setSubtitleTextAppearance(context, resId)
    }

    fun setTextColor(@ColorInt color: Int) {
        setTitleTextColor(color)
        setSubTitleTextColor(color)
    }

    fun setColorFilter(@ColorInt color: Int) {
        val colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        toolbar.children.firstOrNull { it is ImageView }?.background?.colorFilter = colorFilter
        toolbar.navigationIcon?.colorFilter = colorFilter
        toolbar.overflowIcon?.colorFilter = colorFilter
        toolbar.menu.children.forEach {
            it.icon?.colorFilter = colorFilter
        }
    }

    override fun setBackgroundColor(color: Int) {
        if (color.alpha < 255) {
            //这里不能改为0f,改为0f在横屏模式下文字和图标颜色会变
            elevation = 0.1f
        }
        super.setBackgroundColor(color)
    }

    override fun setBackground(background: Drawable?) {
        if (background is ColorDrawable) {
            if (background.alpha < 255) {
                //这里不能改为0f,改为0f在横屏模式下文字和图标颜色会变
                elevation = 0.1f
            }
        }
        super.setBackground(background)
    }

    fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, fullScreen: Boolean) {
//        if (fitStatusBar) {
//            val topPadding = if (!isInMultiWindowMode && fullScreen) context.statusBarHeight else 0
//            setPadding(paddingLeft, topPadding, paddingRight, paddingBottom)
//        }
    }

    private fun attachToActivity() {
        if (attachToActivity) {
            activity?.let {
                it.setSupportActionBar(toolbar)
                it.supportActionBar?.apply {
                    setDisplayHomeAsUpEnabled(displayHomeAsUp)
                    if (displayHomeAsUp) {
                        setHomeActionContentDescription(navigationDescription)
                    }
                }
            }
        }
    }

}
