package io.legado.app.base

import android.content.DialogInterface
import android.content.DialogInterface.OnDismissListener
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.annotation.LayoutRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.glass.GlassDialog
import io.legado.app.lib.glass.GlassDrawable
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.utils.disableAutoFill
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setBackgroundKeepPadding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext


abstract class BaseDialogFragment(
    @LayoutRes layoutID: Int,
    private val adaptationSoftKeyboard: Boolean = false
) : DialogFragment(layoutID) {

    private var onDismissListener: OnDismissListener? = null
    private var showRequested = false

    fun setOnDismissListener(onDismissListener: OnDismissListener?) {
        this.onDismissListener = onDismissListener
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.decorView?.disableAutoFill()
        /**
         * [DialogFragment 的玻璃化入口就在这里，不在 applyTint()]
         *
         * [为什么之前改了 applyTint() 却没生效]
         * `Context.alert()` 那条线走的是 androidx AlertDialog，会经过 applyTint()；
         * 而本项目绝大多数弹层是继承 BaseDialogFragment 的 DialogFragment，
         * 它们由 FragmentManager 自己创建 Window，**完全不经过 AlertDialog**。
         * 截图里那个"直角、不透、米色实心"的分组管理弹窗就是这一类。
         * 所以必须在基类这里补上，73 个 dialog 布局才能一次性生效。
         */
        if (!AppConfig.isEInkMode) {
            dialog?.window?.let { GlassDialog.apply(it) }
        }
        if (adaptationSoftKeyboard) {
            dialog?.window?.setBackgroundDrawableResource(R.color.transparent)
        } else if (AppConfig.isEInkMode) {
            dialog?.window?.let {
                it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                val attr = it.attributes
                attr.dimAmount = 0.0f
                attr.windowAnimations = 0
                it.attributes = attr
                it.decorView.setBackgroundKeepPadding(R.color.transparent)
            }
            // 修改gravity的时机一般在子类的onStart方法中, 因此需要在onStart之后执行.
            lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    when (dialog?.window?.attributes?.gravity) {
                        Gravity.TOP -> view?.setBackgroundResource(R.drawable.bg_eink_border_bottom)
                        Gravity.BOTTOM -> view?.setBackgroundResource(R.drawable.bg_eink_border_top)
                        else -> {
                            val padding = 2.dpToPx();
                            view?.setPadding(padding, padding, padding, padding)
                            view?.setBackgroundResource(R.drawable.bg_eink_border_dialog)
                        }
                    }
                }
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            //不加这个android 5.0对话框顶部会有空白
            setStyle(STYLE_NO_TITLE, 0)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (adaptationSoftKeyboard) {
            view.findViewById<View>(R.id.vw_bg)?.setOnClickListener(null)
            view.setOnClickListener { dismiss() }
        } else if (!AppConfig.isEInkMode) {
            /**
             * [这里原来是一句 setBackgroundColor，正是玻璃被盖住的直接原因]
             *
             * 原代码：`view.setBackgroundColor(ThemeStore.backgroundColor())`
             * 它给根视图铺了一层**完全不透明**的米色底。窗口背景即使换成玻璃，
             * 也会被这层实色完全遮住 —— 截图里弹窗"直角 + 不透 + 米色实心"，
             * 就是这么来的（窗口是玻璃，但前面糊了一张实心纸）。
             *
             * [改成把玻璃铺在根视图上，并开 clipToOutline]
             * clipToOutline 让子 View 一起被裁成圆角，否则顶部 Toolbar
             * 那层 primaryColor 实色底会从圆角边缘露出四个直角，
             * 变成"圆角玻璃上贴了张方纸"。
             * 是否裁剪由 glass.drawable 的 getOutline() 提供轮廓。
             */
            /**
             * [tint 不在这里判断版本]
             * 这一层和窗口背景是**两张**玻璃，必须同时切档，否则会出现
             * "窗口那层已经实了、视图这层还透"的夹生状态 ——
             * 实测表现是弹窗边缘一圈透、中间反而不透，比不改还难看。
             * 所以两处都只写同一个资源名 `lg_glass_tint_panel`，
             * 由 values/ 与 values-v31/ 两份资源自动选档，天然同步。
             */
            val glass = GlassDrawable(
                context = view.context,
                radius = view.resources.getDimension(R.dimen.lg_r_lg),
                tint = ContextCompat.getColor(view.context, R.color.lg_glass_tint_panel),
                borderColor = ContextCompat.getColor(view.context, R.color.lg_glass_border),
                withShadow = false
            )
            view.background = glass
            view.clipToOutline = true
        }
        onFragmentCreated(view, savedInstanceState)
        /**
         * [Toolbar 必须在这里统一清掉实色底]
         * 各 DialogFragment 子类普遍写着 `toolBar.setBackgroundColor(primaryColor)`，
         * 那是一整块实心深褐。玻璃底座再漂亮，被这块实色糊住就等于没有 ——
         * 截图里那个"米色玻璃 + 棕色方头"的割裂感正是这么来的。
         *
         * 放在 onFragmentCreated **之后**执行，是因为子类正是那里面设的颜色；
         * 放在之前会被子类覆盖回去。
         */
        if (!AppConfig.isEInkMode) {
            applyGlassChrome(view)
            /**
             * [必须在 onFragmentCreated 之后、且再 post 一帧]
             * 列表项的背景是在 adapter 的 convert() 里设的，而 convert 由
             * RecyclerView 在**下一帧布局**时才执行。这里同步调用只会
             * 扫到空列表，等于是白跑一趟。
             */
            view.post { clearPageColorBackgrounds(view) }
        }
        observeLiveBus()
    }

    /**
     * 递归把弹层内的 Toolbar 改成"透明 + 深色前景"，让它真正浮在玻璃上。
     *
     * [为什么连文字和图标也要一起改]
     * 原来 Toolbar 是深褐底、白字白图标。底色变透明后，白字直接落在米白玻璃上，
     * 对比度几乎为零 —— 等于把标题弄丢了。所以前景必须同步翻成深色。
     */
    private fun applyGlassChrome(root: View) {
        if (root is androidx.appcompat.widget.Toolbar) {
            root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            // 透明后若还留着 elevation，会投出一层灰影，看起来像没对齐
            root.elevation = 0f

            val titleColor = ContextCompat.getColor(root.context, R.color.lg_text)
            val subColor = ContextCompat.getColor(root.context, R.color.lg_text_2)
            root.setTitleTextColor(titleColor)
            root.setSubtitleTextColor(subColor)

            root.navigationIcon?.let { DrawableCompat.setTint(it, titleColor) }
            root.overflowIcon?.let { DrawableCompat.setTint(it, titleColor) }

            /**
             * [menu 是异步 inflate 的]
             * Toolbar 的菜单项由 inflateMenu 在后续帧才填进去，
             * 这里直接遍历会是空集合。post 到消息队列末尾才能拿到真实条目。
             *
             * [这里不能用 forEach]
             * android.view.Menu 只实现了 Iterable，没有元素类型信息，
             * 直接 forEach 会让 Kotlin 无法推断 item 类型而编译失败。
             * 改用 size + getItemAt 的显式下标循环，不依赖任何扩展函数。
             */
            root.post {
                runCatching {
                    val menu = root.menu
                    for (i in 0 until menu.size()) {
                        val icon = menu.getItem(i).icon
                        if (icon != null) {
                            DrawableCompat.setTint(icon, titleColor)
                        }
                    }
                }
            }
        }
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                applyGlassChrome(root.getChildAt(i))
            }
        }
    }

    /**
     * 清掉弹层内部那些"等于页面底色"的不透明实色背景。
     *
     * [这层实色才是玻璃失效的最后一环]
     * 窗口透明了、根视图换成玻璃了，但列表项自己在 adapter 里写着
     * `root.setBackgroundColor(context.backgroundColor)` —— 一块完全不透明的米色，
     * 铺满整片列表区。结果就是：标题栏透、正文区死，看起来像"玻璃上贴了张纸"。
     *
     * [为什么按颜色值判断，而不是按 View 名字判断]
     * 这类写法散落在几十个 adapter 和 layout 里（`@color/background`、
     * `context.backgroundColor`），逐个改既漏又容易破坏选中态。
     * 页面底色是个唯一值，凡是**恰好涂成这个值**的背景，在弹层里一定
     * 只是想"跟页面一样"，让它透明正好还原设计意图。
     * 强调色、分割线、ripple 这些颜色都对不上，不会被误伤。
     */
    private fun clearPageColorBackgrounds(root: View) {
        val pageColor = ThemeStore.backgroundColor(root.context)

        fun tryClear(v: View) {
            val bg = v.background
            if (bg is android.graphics.drawable.ColorDrawable && bg.color == pageColor) {
                // 置空而不是设透明色：透明 ColorDrawable 仍会参与绘制，且会挡掉父层过渡
                v.background = null
            }
        }

        fun walk(v: View) {
            /**
             * [RecyclerView 的 item 还没被创建，必须先挂监听]
             * onViewCreated 时列表是空的，直接遍历拿不到任何 item 视图。
             * item 是滚动过程中才被复用的，所以必须在"挂载"时机做清理。
             */
            if (v is androidx.recyclerview.widget.RecyclerView) {
                v.addOnChildAttachStateChangeListener(
                    object : androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
                        override fun onChildViewAttachedToWindow(childView: View) {
                            tryClear(childView)
                            // item 内部也可能再套一层同色容器
                            if (childView is android.view.ViewGroup) {
                                for (i in 0 until childView.childCount) {
                                    tryClear(childView.getChildAt(i))
                                }
                            }
                        }

                        override fun onChildViewDetachedFromWindow(childView: View) = Unit
                    })
                // 已经绑定好的那批先处理一遍
                for (i in 0 until v.childCount) {
                    tryClear(v.getChildAt(i))
                }
            }
            tryClear(v)
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) {
                    walk(v.getChildAt(i))
                }
            }
        }

        walk(root)
    }

    abstract fun onFragmentCreated(view: View, savedInstanceState: Bundle?)

    override fun show(manager: FragmentManager, tag: String?) {
        if (showRequested || isAdded) return
        kotlin.runCatching {
            // Guard queued requests without removing a fragment and clearing its tag.
            showRequested = true
            super.show(manager, tag)
        }.onFailure {
            showRequested = false
            AppLog.put("显示对话框失败 tag:$tag", it)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        val host = activity
        showRequested = false
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(dialog)
        // Destroying the dialog view for recreation also calls onDismiss.
        if (arguments?.getBoolean("finishOnDismiss") == true && host?.isChangingConfigurations == false) {
            host.finish()
        }
    }

    fun <T> execute(
        scope: CoroutineScope = lifecycleScope,
        context: CoroutineContext = Dispatchers.IO,
        block: suspend CoroutineScope.() -> T
    ) = Coroutine.async(scope, context) { block() }

    open fun observeLiveBus() {
    }

    fun findParentTextInputLayout(view: View): TextInputLayout? {
        var parent = view.parent
        while (parent != null && parent !is TextInputLayout) {
            parent = parent.parent
        }
        return parent
    }
}
