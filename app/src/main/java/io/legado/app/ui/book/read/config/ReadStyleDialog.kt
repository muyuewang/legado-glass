package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.get
import com.github.liuyueyi.quick.transfer.constants.TransType
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogReadBookStyleBinding
import io.legado.app.databinding.ItemReadStyleBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.glass.GlassDrawable
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getIndexById
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.onLongClick

class ReadStyleDialog : BaseDialogFragment(R.layout.dialog_read_book_style),
    FontSelectDialog.CallBack {

    private val binding by viewBinding(DialogReadBookStyleBinding::bind)
    private val callBack get() = activity as? ReadBookActivity
    private lateinit var styleAdapter: StyleAdapter
    private var updatingPageAnim = false

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            /**
             * [原来是 setBackgroundDrawableResource(R.color.background)]
             *
             * 这一行把窗口背景设成**不透明实色**，直接盖掉了
             * BaseDialogFragment.onStart 里铺的玻璃窗口背景。
             * 阅读页设置面板"整片米色 + 不透"的第一个原因就是它。
             *
             * 改成透明，让窗口那层玻璃透出来；下方正文本来就该隐约可见。
             */
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        (activity as ReadBookActivity).bottomDialog++
        initView()
        initData()
        initViewEvent()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
        (activity as ReadBookActivity).bottomDialog--
    }

    private fun initView() = binding.run {
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = requireContext().getPrimaryTextColor(isLight)
        /**
         * [原来这里是 rootView.setBackgroundColor(bg)]
         *
         * 第二个盖住玻璃的地方：BaseDialogFragment 已经在 onViewCreated 里
         * 给根视图铺了 GlassDrawable，这里又把它覆盖成 bottomBackground 实色。
         * 两处都改掉，玻璃才真正露出来。
         *
         * [用 bar 档而不是 panel 档]
         * 这个面板是**贴着屏幕底部、背后就是满屏正文**的浮层，
         * 与阅读页顶部/底部条栏是同一种处境 —— 正文黑字压在近黑底上，
         * 透光会在面板里留下字的灰影。实测 panel（97%）下
         * "在国际空间站外、冰冷与死亡并存"这行仍能穿进面板，
         * 与"字号/字距/行距"控件叠在一起。
         *
         * 通用基类 BaseDialogFragment 不能跟着改：它还服务书籍详情、
         * 分组管理等对话框，那些背后是大片留白或列表，panel 档才有通透感。
         * 所以只在阅读页这两个面板里显式指定 bar 档。
         *
         * 只圆上两角：面板贴着屏幕底边，下沿会被导航栏吃掉。
         */
        rootView.background = GlassDrawable(
            context = requireContext(),
            radius = resources.getDimension(R.dimen.lg_r_lg),
            tint = androidx.core.content.ContextCompat.getColor(
                requireContext(),
                R.color.lg_glass_tint_bar
            ),
            borderColor = androidx.core.content.ContextCompat.getColor(
                requireContext(),
                R.color.lg_glass_border
            ),
            withShadow = false,
            roundTopOnly = true
        )
        rootView.clipToOutline = true
        tvPageAnim.setTextColor(textColor)
        tvBgTs.setTextColor(textColor)
        tvShareLayout.setTextColor(textColor)
        dsbTextSize.valueFormat = {
            (it + 5).toString()
        }
        dsbTextLetterSpacing.valueFormat = {
            ((it - 50) / 100f).toString()
        }
        dsbLineSize.valueFormat = { lineSpacingDisplayValue(it) }
        dsbParagraphSpacing.valueFormat = { (it / 10f).toString() }
        styleAdapter = StyleAdapter()
        rvStyle.adapter = styleAdapter
        styleAdapter.addFooterView {
            ItemReadStyleBinding.inflate(layoutInflater, it, false).apply {
                ivStyle.setPadding(6.dpToPx(), 6.dpToPx(), 6.dpToPx(), 6.dpToPx())
                ivStyle.setText(null)
                ivStyle.setColorFilter(textColor)
                ivStyle.borderColor = textColor
                ivStyle.setImageResource(R.drawable.ic_add)
                root.setOnClickListener {
                    ReadBookConfig.configList.add(ReadBookConfig.Config())
                    showBgTextConfig(ReadBookConfig.configList.lastIndex)
                }
            }
        }
    }

    private fun initData() {
        binding.cbShareLayout.isChecked = ReadBookConfig.shareLayout
        upView()
        styleAdapter.setItems(ReadBookConfig.configList)
    }

    private fun initViewEvent() = binding.run {
        chineseConverter.onChanged {
            ChineseUtils.unLoad(*TransType.entries.toTypedArray())
            postEvent(EventBus.UP_CONFIG, arrayListOf(5))
        }
        textFontWeightConverter.onChanged {
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 9, 6))
        }
        tvTextFont.setOnClickListener {
            showDialogFragment<FontSelectDialog>()
        }
        tvTextIndent.setOnClickListener {
            context?.selector(
                title = getString(R.string.text_indent),
                items = resources.getStringArray(R.array.indent).toList()
            ) { _, index ->
                ReadBookConfig.paragraphIndent = "　".repeat(index)
                postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
            }
        }
        tvPadding.setOnClickListener {
            dismissAllowingStateLoss()
            callBack?.showPaddingConfig()
        }
        tvTip.setOnClickListener {
            TipConfigDialog().show(childFragmentManager, "tipConfigDialog")
        }
        rgPageAnim.setOnCheckedChangeListener { _, checkedId ->
            if (updatingPageAnim) return@setOnCheckedChangeListener
            ReadBook.book?.setPageAnim(-1)
            ReadBookConfig.pageAnim = binding.rgPageAnim.getIndexById(checkedId)
            callBack?.upPageAnim()
            ReadBook.loadContent(false)
        }
        cbShareLayout.onCheckedChangeListener = { _, isChecked ->
            val oldPageAnim = ReadBook.pageAnim()
            ReadBookConfig.shareLayout = isChecked
            if (ReadBook.pageAnim() != oldPageAnim) callBack?.upPageAnim()
            upView()
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
        }
        dsbTextSize.onChanged = {
            ReadBookConfig.textSize = it + 5
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbTextLetterSpacing.onChanged = {
            ReadBookConfig.letterSpacing = (it - 50) / 100f
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbLineSize.onChanged = {
            ReadBookConfig.lineSpacingExtra = lineSpacingFromProgress(it)
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbParagraphSpacing.onChanged = {
            ReadBookConfig.paragraphSpacing = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
    }

    private fun changeBgTextConfig(index: Int) {
        val oldIndex = ReadBookConfig.styleSelect
        if (index != oldIndex) {
            val oldPageAnim = ReadBook.pageAnim()
            ReadBookConfig.styleSelect = index
            if (ReadBook.pageAnim() != oldPageAnim) callBack?.upPageAnim()
            upView()
            styleAdapter.notifyItemChanged(oldIndex)
            styleAdapter.notifyItemChanged(index)
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            if (AppConfig.readBarStyleFollowPage) {
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            }
        }
    }

    private fun showBgTextConfig(index: Int): Boolean {
        dismissAllowingStateLoss()
        changeBgTextConfig(index)
        callBack?.showBgTextConfig()
        return true
    }

    private fun upView() = binding.run {
        textFontWeightConverter.upUi(ReadBookConfig.textBold)
        // Reflect the selected preset without replaying radio-button change callbacks.
        // Those callbacks can apply new padding while the old scroll page is still bound.
        updatingPageAnim = true
        try {
            ReadBook.pageAnim().let {
                if (it >= 0 && it < rgPageAnim.childCount) {
                    rgPageAnim.check(rgPageAnim[it].id)
                }
            }
        } finally {
            updatingPageAnim = false
        }
        ReadBookConfig.let {
            dsbTextSize.progress = it.textSize - 5
            dsbTextLetterSpacing.progress = (it.letterSpacing * 100).toInt() + 50
            dsbLineSize.progress = lineSpacingToProgress(it.lineSpacingExtra)
            dsbParagraphSpacing.progress = it.paragraphSpacing
        }
    }

    override val curFontPath: String
        get() = ReadBookConfig.textFont

    override fun selectFont(path: String) {
        if (path != ReadBookConfig.textFont || path.isEmpty()) {
            ReadBookConfig.textFont = path
            postEvent(EventBus.UP_CONFIG, arrayListOf(2, 5))
        }
    }

    inner class StyleAdapter :
        RecyclerAdapter<ReadBookConfig.Config, ItemReadStyleBinding>(requireContext()) {

        override fun getViewBinding(parent: ViewGroup): ItemReadStyleBinding {
            return ItemReadStyleBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemReadStyleBinding,
            item: ReadBookConfig.Config,
            payloads: MutableList<Any>
        ) {
            binding.apply {
                ivStyle.setText(item.name.ifBlank { "文字" })
                ivStyle.setTextColor(item.curTextColor())
                ivStyle.setImageDrawable(item.curBgDrawable(100, 150))
                if (ReadBookConfig.styleSelect == holder.layoutPosition) {
                    ivStyle.borderColor = accentColor
                    ivStyle.setTextBold(true)
                } else {
                    ivStyle.borderColor = item.curTextColor()
                    ivStyle.setTextBold(false)
                }
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemReadStyleBinding) {
            binding.apply {
                ivStyle.setOnClickListener {
                    if (ivStyle.isInView) {
                        changeBgTextConfig(holder.layoutPosition)
                    }
                }
                ivStyle.onLongClick(ivStyle.isInView) {
                    if (ivStyle.isInView) {
                        showBgTextConfig(holder.layoutPosition)
                    }
                }
            }
        }

    }
}

private const val LINE_SPACING_CONFIG_MIN = -10
private const val LINE_SPACING_CONFIG_MAX = 40
private const val LINE_SPACING_PROGRESS_OFFSET = 10

internal fun lineSpacingToProgress(value: Int): Int {
    return (value + LINE_SPACING_PROGRESS_OFFSET)
        .coerceIn(0, LINE_SPACING_CONFIG_MAX - LINE_SPACING_CONFIG_MIN)
}

internal fun lineSpacingFromProgress(progress: Int): Int {
    return (progress - LINE_SPACING_PROGRESS_OFFSET)
        .coerceIn(LINE_SPACING_CONFIG_MIN, LINE_SPACING_CONFIG_MAX)
}

internal fun lineSpacingDisplayValue(progress: Int): String {
    return ((lineSpacingFromProgress(progress) - 10) / 10f).toString()
}
