package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.glass.GlassDrawable
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.postEvent
import io.legado.app.utils.removePref
import io.legado.app.utils.setEdgeEffectColor

class MoreConfigDialog : BasePrefDialogFragment() {
    private val readPreferTag = "readPreferenceFragment"

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            /**
             * [原来是 setBackgroundDrawableResource(R.color.background)]
             * 窗口背景设成不透明实色，会把 BaseDialogFragment 铺的玻璃整个盖掉。
             * 改成透明，让窗口那层玻璃透出来。
             */
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 360.dpToPx())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        (activity as ReadBookActivity).bottomDialog++
        val view = LinearLayout(context)
        /**
         * [原来是 view.setBackgroundColor(bottomBackground)]
         *
         * 这是"设置面板整片米色"的第二个来源：这里手工 new 了一个 LinearLayout
         * 当容器，并给它铺了实色底，同样把玻璃盖住。
         *
         * 这个容器是**程序创建**的，不走 BaseDialogFragment.onViewCreated 的玻璃逻辑
         * （那条路径只处理布局 inflate 出来的根视图），所以必须在这里单独铺玻璃。
         *
         * [用 bar 档] 和 ReadStyleDialog 同理：背后是满屏正文，
         * panel（97%）下正文仍会穿进面板与偏好项文字叠加。
         */
        view.background = GlassDrawable(
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
        view.clipToOutline = true
        view.id = R.id.tag1
        container?.addView(view)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment = childFragmentManager.findFragmentByTag(readPreferTag)
        if (preferenceFragment == null) preferenceFragment = ReadPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(view.id, preferenceFragment, readPreferTag)
            .commit()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as ReadBookActivity).bottomDialog--
    }

    class ReadPreferenceFragment : PreferenceFragment(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val slopSquare by lazy { ViewConfiguration.get(requireContext()).scaledTouchSlop }

        @SuppressLint("RestrictedApi")
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.pref_config_read)
            upPreferenceSummary(PreferKey.pageTouchSlop, slopSquare.toString())
            upPreferenceSummary(PreferKey.pullBookmarkDistance, (slopSquare * 6).toString())
            if (!CanvasRecorderFactory.isSupport) {
                removePref(PreferKey.optimizeRender)
                preferenceScreen.removePreferenceRecursively(PreferKey.optimizeRender)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.setEdgeEffectColor(primaryColor)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager
                .sharedPreferences
                ?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager
                .sharedPreferences
                ?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            when (key) {
                PreferKey.paragraphReadMode,
                PreferKey.paragraphReadControls,
                PreferKey.paragraphReadProgress -> {
                    (activity as? ReadBookActivity)?.updateParagraphMode()
                }
                PreferKey.readBodyToLh -> activity?.recreate()
                PreferKey.hideStatusBar -> {
                    ReadBookConfig.hideStatusBar = getPrefBoolean(PreferKey.hideStatusBar)
                    postEvent(EventBus.UP_CONFIG, arrayListOf(0, 2))
                }

                PreferKey.hideNavigationBar -> {
                    ReadBookConfig.hideNavigationBar = getPrefBoolean(PreferKey.hideNavigationBar)
                    postEvent(EventBus.UP_CONFIG, arrayListOf(0, 2))
                }

                PreferKey.keepLight -> postEvent(key, true)
                PreferKey.textSelectAble -> postEvent(key, getPrefBoolean(key))
                PreferKey.screenOrientation -> {
                    (activity as? ReadBookActivity)?.setOrientation()
                }

                PreferKey.textFullJustify,
                PreferKey.textBottomJustify,
                PreferKey.hangingPunctuation,
                PreferKey.punctuationCompress,
                PreferKey.useZhLayout,
                PreferKey.adaptSpecialStyle-> {
                    postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                }

                PreferKey.showBrightnessView -> {
                    postEvent(PreferKey.showBrightnessView, "")
                }

                PreferKey.doublePageHorizontal -> {
                    ChapterProvider.upLayout()
                    ReadBook.loadContent(false)
                }

                PreferKey.showReadTitleAddition,
                PreferKey.showReadTitleChapterNameOnly,
                PreferKey.readBarStyleFollowPage -> {
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }

                PreferKey.progressBarBehavior -> {
                    postEvent(EventBus.UP_SEEK_BAR, true)
                }

                PreferKey.noAnimScrollPage -> {
                    ReadBook.callBack?.upPageAnim()
                }

                PreferKey.pullToToggleBookmark -> {
                    (activity as? ReadBookActivity)?.upBookmarkIndicator()
                }

                PreferKey.optimizeRender -> {
                    ChapterProvider.upStyle()
                    ReadBook.callBack?.upPageAnim(true)
                    ReadBook.loadContent(false)
                }

                PreferKey.paddingDisplayCutouts -> {
                    postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                }
            }
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                "customPageKey" -> PageKeyDialog(requireContext()).show()
                "clickRegionalConfig" -> {
                    (activity as? ReadBookActivity)?.showClickRegionalConfig()
                }

                "customTextMenu" -> {
                    (activity as? ReadBookActivity)?.showTextSelectMenuConfig()
                }

                "customReaderMenu" -> {
                    (activity as? ReadBookActivity)?.showReaderMenuConfig()
                }

                PreferKey.pageTouchSlop -> {
                    NumberPickerDialog(requireContext())
                        .setTitle(getString(R.string.page_touch_slop_dialog_title))
                        .setMaxValue(9999)
                        .setMinValue(0)
                        .setValue(AppConfig.pageTouchSlop)
                        .show {
                            AppConfig.pageTouchSlop = it
                            postEvent(EventBus.UP_CONFIG, arrayListOf(4))
                        }
                }

                PreferKey.pullBookmarkDistance -> {
                    NumberPickerDialog(requireContext())
                        .setTitle(getString(R.string.pull_bookmark_distance_dialog_title))
                        .setMaxValue(9999)
                        .setMinValue(0)
                        .setValue(AppConfig.pullBookmarkDistance)
                        .show {
                            AppConfig.pullBookmarkDistance = it
                        }
                }

                PreferKey.pageTouchClick -> {
                    NumberPickerDialog(requireContext())
                        .setTitle(getString(R.string.page_touch_click_dialog_title))
                        .setMaxValue(399)
                        .setMinValue(0)
                        .setValue(AppConfig.pageTouchClick)
                        .show {
                            AppConfig.pageTouchClick = it
                            postEvent(EventBus.UP_CONFIG, arrayListOf(12))
                        }
                }
            }
            return super.onPreferenceTreeClick(preference)
        }

        @Suppress("SameParameterValue")
        private fun upPreferenceSummary(preferenceKey: String, value: String?) {
            val preference = findPreference<Preference>(preferenceKey) ?: return
            when (preferenceKey) {
                PreferKey.pageTouchSlop -> preference.summary =
                    getString(R.string.page_touch_slop_summary, value)
                PreferKey.pullBookmarkDistance -> preference.summary =
                    getString(R.string.pull_bookmark_distance_summary, value)
            }
        }

    }
}
