package io.relimus.zflow.ui.main

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.google.android.material.snackbar.Snackbar
import io.relimus.zflow.BuildConfig
import io.relimus.zflow.R
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.providers.RadialAppsStore
import io.relimus.zflow.providers.RadialTriggerSettings
import io.relimus.zflow.providers.WindowEngineSettings
import io.relimus.zflow.service.ForegroundService
import io.relimus.zflow.service.KeepAliveService
import io.relimus.zflow.ui.choose_apps.ChooseAppsActivity
import io.relimus.zflow.ui.choose_apps.ChooseAppsViewModel
import io.relimus.zflow.ui.permission.PermissionActivity
import io.relimus.zflow.ui.radial_area.RadialTriggerAreaActivity
import io.relimus.zflow.ui.view.IntegerSimpleMenuPreference
import io.relimus.zflow.utils.PermissionUtils

/**
 * 设置页。顶层四分类：启动与触发 / 运行规则与交互 / 通知与保活 / 高级与开发者，
 * 子页由 [FreeformSettingsActivity] 按 root_key 承载。
 */
class SettingFragment : PreferenceFragmentCompat(), Preference.OnPreferenceClickListener,
    Preference.OnPreferenceChangeListener {

    private lateinit var sp: SharedPreferences

    companion object {
        private const val KEY_TRIGGER = "pref_trigger_settings"
        private const val KEY_RULES = "pref_rules_settings"
        private const val KEY_KEEPALIVE = "pref_keepalive_settings"
        private const val KEY_ADVANCED = "pref_advanced_settings"

        private val TOP_LEVEL_KEYS =
            listOf(KEY_TRIGGER, KEY_RULES, KEY_KEEPALIVE, KEY_ADVANCED)

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
                findPreference<Preference>("reset_overlay_setting")?.onPreferenceClickListener = this
                findPreference<Preference>("trusted_callers")?.onPreferenceClickListener = this
                findPreference<Preference>("radial_gesture_apps")?.onPreferenceClickListener = this
                findPreference<Preference>("radial_trigger_area")?.onPreferenceClickListener = this
                updateRadialAreaSummary()
                updateRadialAppsSummary()
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

    private fun updateRulesSummary() {
        val autoMin = sp.getBoolean("nc_auto_minimize", true)
        findPreference<Preference>(KEY_RULES)?.summary =
            "开新窗时收起上一个：${if (autoMin) "开" else "关"} | 黑名单与横屏名单见内页"
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

    /**
     * 触发区摘要。
     *
     * 直接写「双侧 · 20%×12%」而不是把四个数字全列出来：设置页的这一行是
     * 「我配的是哪块区」的速记，具体数值进详情页看滑杆更直观。
     */
    private fun updateRadialAreaSummary() {
        val preference = findPreference<Preference>("radial_trigger_area") ?: return
        val config = RadialTriggerSettings.read(requireContext())
        val side = when {
            config.mirror -> getString(R.string.radial_trigger_side_both)
            config.centerXPercent <= 50 -> getString(R.string.radial_trigger_side_left)
            else -> getString(R.string.radial_trigger_side_right)
        }
        preference.summary = getString(
            R.string.radial_trigger_area_current,
            "$side · ${config.widthPercent}%×${config.heightPercent}%",
        )
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

            key == "radial_trigger_area" -> {
                startActivity(Intent(requireContext(), RadialTriggerAreaActivity::class.java))
                return true
            }

            key == "reset_overlay_setting" -> {
                sp.edit {
                    putInt("floating_position_portrait_y", 0)
                    putInt("floating_position_landscape_y", 0)
                }
                Snackbar.make(requireView(), getString(R.string.reset_success), Snackbar.LENGTH_SHORT).show()
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
        // 从「轮盘应用」「触发区域」页返回时配置可能已经变了，摘要要跟着刷新。
        updateRadialAppsSummary()
        updateRadialAreaSummary()
        sp.registerOnSharedPreferenceChangeListener(ncSettingsListener)
    }

    override fun onPause() {
        sp.unregisterOnSharedPreferenceChangeListener(ncSettingsListener)
        super.onPause()
    }

    /**
     * 引擎原生开关（[WindowEngineSettings]）改动后镜像到 `corner` 偏好，
     * 再广播让 SystemUI 重读快照。外观设置只对经典内核生效，不经过这里。
     */
    private val ncSettingsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || !isAdded) return@OnSharedPreferenceChangeListener
            if (key in WindowEngineSettings.ALL_KEYS) WindowEngineSettings.sync(requireContext())
        }
}
