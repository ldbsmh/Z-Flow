package io.relimus.zflow.ui.main

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import io.relimus.zflow.BuildConfig
import io.relimus.zflow.R
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.providers.RadialAppsStore
import io.relimus.zflow.service.ForegroundService
import io.relimus.zflow.service.KeepAliveService
import io.relimus.zflow.ui.choose_apps.ChooseAppsActivity
import io.relimus.zflow.ui.choose_apps.ChooseAppsViewModel
import io.relimus.zflow.ui.permission.PermissionActivity
import io.relimus.zflow.ui.view.IntegerSimpleMenuPreference
import io.relimus.zflow.utils.PermissionUtils

/**
 * 设置页。顶层四分类：打开方式 / 小窗外观与行为 / 运行与高级 / 关于，
 * 子页由 [FreeformSettingsActivity] 按 root_key 承载。
 */
class SettingFragment : PreferenceFragmentCompat(), Preference.OnPreferenceClickListener,
    Preference.OnPreferenceChangeListener {

    private lateinit var sp: SharedPreferences

    companion object {
        private const val KEY_TRIGGER = "pref_trigger_settings"
        private const val KEY_APPEARANCE = "pref_appearance_settings"
        private const val KEY_RULES = "pref_rules_settings"
        private const val KEY_KEEPALIVE = "pref_keepalive_settings"
        private const val KEY_ADVANCED = "pref_advanced_settings"

        private val TOP_LEVEL_KEYS =
            listOf(KEY_TRIGGER, KEY_APPEARANCE, KEY_RULES, KEY_KEEPALIVE, KEY_ADVANCED)

        private const val URL_OPEN_API = "https://github.com/ldbsmh/Z-Flow/blob/import/open_api_zh-Hans.md"
        private const val URL_SOURCE = "https://github.com/ldbsmh/Z-Flow"

        fun newInstance(rootKey: String): SettingFragment {
            return SettingFragment().apply {
                arguments = Bundle().apply {
                    putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, rootKey)
                }
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = ZFlow.APP_SETTINGS_NAME
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        setPreferencesFromResource(R.xml.settings, rootKey)

        sp = requireActivity().getSharedPreferences(ZFlow.APP_SETTINGS_NAME, Context.MODE_PRIVATE)

        when (rootKey) {
            KEY_TRIGGER -> {
                findPreference<SwitchPreference>("show_floating")!!.onPreferenceChangeListener = this
                findPreference<Preference>("quick_floating_app")!!.onPreferenceClickListener = this
                findPreference<Preference>("trusted_callers")?.onPreferenceClickListener = this
                findPreference<Preference>("radial_gesture_apps")?.onPreferenceClickListener = this
                updateRadialAppsSummary()
            }

            KEY_APPEARANCE -> {
                findPreference<SeekBarPreference>("freeform_scale")?.apply {
                    onPreferenceClickListener = this@SettingFragment
                }
                findPreference<Preference>("reset_overlay_setting")?.onPreferenceClickListener = this
            }

            KEY_RULES -> {
                findPreference<Preference>("freeform_blacklist_apps")?.onPreferenceClickListener = this
                findPreference<Preference>("landscape_apps")?.onPreferenceClickListener = this
            }

            KEY_KEEPALIVE -> {
                findPreference<IntegerSimpleMenuPreference>("service_type")!!.onPreferenceChangeListener = this
                findPreference<SwitchPreference>("notify_freeform")!!.onPreferenceChangeListener = this
                findPreference<Preference>("notification_freeform_apps")!!.onPreferenceClickListener = this
            }

            KEY_ADVANCED -> {
                findPreference<Preference>("about_open_api")?.onPreferenceClickListener = this
                findPreference<Preference>("about_source")?.onPreferenceClickListener = this
                findPreference<Preference>("about_version")?.summary =
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            }

            else -> {
                updateAllSummaries()
                TOP_LEVEL_KEYS.forEach { key ->
                    findPreference<Preference>(key)?.onPreferenceClickListener = this
                }
            }
        }
    }

    private fun updateAllSummaries() {
        updateTriggerSummary()
        updateAppearanceSummary()
        updateRulesSummary()
        updateKeepAliveSummary()
        updateAdvancedSummary()
    }

    private fun updateTriggerSummary() {
        val sidebar = sp.getBoolean("show_floating", false)
        val pos = if (sp.getInt("floating_position_x", 1) == 0) "左" else "右"
        val desktop = sp.getBoolean("popup_freeform_enable", true)
        findPreference<Preference>(KEY_TRIGGER)?.summary =
            "侧边栏：${if (sidebar) "开" else "关"}·$pos | 桌面长按：${if (desktop) "开" else "关"}"
    }

    private fun updateAppearanceSummary() {
        val size = sp.getInt("freeform_size", 75)
        val scale = sp.getInt("freeform_scale", 50)
        findPreference<Preference>(KEY_APPEARANCE)?.summary =
            "大小：$size% | 缩放：$scale"
    }

    private fun updateRulesSummary() {
        val maxWin = sp.getInt("max_freeform_windows", 2)
        findPreference<Preference>(KEY_RULES)?.summary =
            "小窗上限：$maxWin 个 | 黑名单与横屏名单见内页"
    }

    private fun updateKeepAliveSummary() {
        val serviceType = sp.getInt("service_type", KeepAliveService.SERVICE_TYPE)
        val typeStr = if (serviceType == KeepAliveService.SERVICE_TYPE) "无障碍" else "前台服务"
        val notify = sp.getBoolean("notify_freeform", true)
        findPreference<Preference>(KEY_KEEPALIVE)?.summary =
            "保活：$typeStr | 通知按钮：${if (notify) "开" else "关"}"
    }

    private fun updateAdvancedSummary() {
        findPreference<Preference>(KEY_ADVANCED)?.summary =
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }

    /** 轮盘名单的摘要：空名单要说清「会退回最近任务」，否则用户不知道为什么轮盘里是自己没用过的应用。 */
    private fun updateRadialAppsSummary() {
        val preference = findPreference<Preference>("radial_gesture_apps") ?: return
        val count = RadialAppsStore.read(requireContext()).size
        preference.summary = if (count == 0) {
            getString(R.string.radial_apps_summary_empty)
        } else {
            getString(R.string.radial_apps_summary_count, count)
        }
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        val key = preference.key
        when {
            key in TOP_LEVEL_KEYS -> {
                startActivity(
                    Intent(requireContext(), FreeformSettingsActivity::class.java)
                        .putExtra("root_key", key)
                )
                return true
            }

            key == "quick_floating_app" -> {
                startActivity(
                    Intent(requireContext(), ChooseAppsActivity::class.java)
                        .putExtra("type", ChooseAppsViewModel.TYPE_FLOATING)
                )
            }

            key == "notification_freeform_apps" -> {
                startActivity(
                    Intent(requireContext(), ChooseAppsActivity::class.java)
                        .putExtra("type", ChooseAppsViewModel.TYPE_NOTIFICATION)
                )
            }

            key == "freeform_blacklist_apps" -> {
                startActivity(
                    Intent(requireContext(), ChooseAppsActivity::class.java)
                        .putExtra("type", ChooseAppsViewModel.TYPE_BLACKLIST)
                )
            }

            key == "landscape_apps" -> {
                startActivity(
                    Intent(requireContext(), ChooseAppsActivity::class.java)
                        .putExtra("type", ChooseAppsViewModel.TYPE_LANDSCAPE)
                )
            }

            key == "trusted_callers" -> {
                startActivity(
                    Intent(requireContext(), ChooseAppsActivity::class.java)
                        .putExtra("type", ChooseAppsViewModel.TYPE_TRUSTED)
                )
            }

            key == "radial_gesture_apps" -> {
                startActivity(
                    Intent(requireContext(), ChooseAppsActivity::class.java)
                        .putExtra("type", ChooseAppsViewModel.TYPE_RADIAL)
                )
                return true
            }

            key == "reset_overlay_setting" -> {
                sp.edit {
                    putInt("floating_position_portrait_y", 0)
                    putInt("floating_position_landscape_y", 0)
                }
                Snackbar.make(requireView(), getString(R.string.reset_success), Snackbar.LENGTH_SHORT).show()
            }

            key == "freeform_scale" -> {
                showDpiDialog(preference as SeekBarPreference)
            }

            key == "about_open_api" -> {
                startActivity(Intent(Intent.ACTION_VIEW, URL_OPEN_API.toUri()))
            }

            key == "about_source" -> {
                startActivity(Intent(Intent.ACTION_VIEW, URL_SOURCE.toUri()))
            }
        }
        return true
    }

    private fun showDpiDialog(pref: SeekBarPreference) {
        val editView = layoutInflater.inflate(R.layout.view_edit, null, false)
        editView.findViewById<TextInputLayout>(R.id.til_freeform_dpi)
        val editText = editView.findViewById<AppCompatEditText>(R.id.edit_freeform_dpi)
        MaterialAlertDialogBuilder(requireContext()).apply {
            setTitle(getString(R.string.freeform_dpi_dialog_title))
            setView(editView)
            setPositiveButton(getString(R.string.done)) { _, _ ->
                editText.text?.toString()?.toIntOrNull()?.takeIf { it in 50..500 }?.let { pref.value = it }
            }
            setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            setNeutralButton(getString(R.string.to_default)) { _, _ ->
                pref.value = 0
            }
            create().show()
        }
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        when (preference.key) {
            "service_type" -> {
                when (newValue as Int) {
                    KeepAliveService.SERVICE_TYPE -> {
                        if (PermissionUtils.isAccessibilitySettingsOn(requireContext())) {
                            requireContext().stopService(Intent(requireContext(), ForegroundService::class.java))
                            if (!KeepAliveService.isServiceRunning) {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.accessibility_authorized_not_running),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            Toast.makeText(requireContext(), "请开启无障碍服务", Toast.LENGTH_SHORT).show()
                            return false
                        }
                    }

                    ForegroundService.SERVICE_TYPE -> {
                        if (!PermissionUtils.isAccessibilitySettingsOn(requireContext())) {
                            requireContext().startForegroundService(Intent(requireContext(), ForegroundService::class.java))
                        } else {
                            Toast.makeText(requireContext(), "请先关闭无障碍服务", Toast.LENGTH_SHORT).show()
                            return false
                        }
                    }
                }
            }

            "show_floating" -> {
                if (newValue as Boolean) {
                    when (sp.getInt("service_type", KeepAliveService.SERVICE_TYPE)) {
                        KeepAliveService.SERVICE_TYPE -> {
                            if (!PermissionUtils.isAccessibilitySettingsOn(requireContext())) {
                                Toast.makeText(requireContext(), "需要无障碍服务", Toast.LENGTH_SHORT).show()
                                startActivity(Intent(requireContext(), PermissionActivity::class.java))
                                requireActivity().finish()
                                return false
                            }
                            if (!KeepAliveService.isServiceRunning) {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.accessibility_authorized_not_running),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        ForegroundService.SERVICE_TYPE -> {
                            requireContext().startForegroundService(Intent(requireContext(), ForegroundService::class.java))
                        }
                    }
                }
            }

            "notify_freeform" -> {
                // 通知接管由 SystemUI 侧 hook 实现，无需通知使用权
                PermissionUtils.checkPostNotificationPermission(requireActivity())
            }
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        if (arguments == null || arguments?.getString(ARG_PREFERENCE_ROOT) == null) {
            updateAllSummaries()
        }
        // 从「轮盘应用」页返回时名单可能已经变了，摘要要跟着刷新。
        updateRadialAppsSummary()
    }
}
