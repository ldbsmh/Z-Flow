package io.relimus.zflow.ui.main

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.edit
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import io.relimus.zflow.R
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.service.ForegroundService
import io.relimus.zflow.service.KeepAliveService
import io.relimus.zflow.ui.choose_apps.ChooseAppsActivity
import io.relimus.zflow.ui.permission.PermissionActivity
import io.relimus.zflow.ui.view.IntegerSimpleMenuPreference
import io.relimus.zflow.utils.PermissionUtils

class SettingFragment : PreferenceFragmentCompat(), Preference.OnPreferenceClickListener,
    Preference.OnPreferenceChangeListener {

    private lateinit var sp: SharedPreferences
    private lateinit var accessibilityRFAR: ActivityResultLauncher<Intent>

    companion object {
        private const val KEY_BASE = "pref_base_settings"
        private const val KEY_SIDEBAR = "pref_sidebar_settings"
        private const val KEY_FREEFORM = "pref_freeform_sub"
        private const val KEY_NOTIFICATION = "pref_notification_settings"
        private const val KEY_OTHER = "pref_other_settings"

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
            KEY_BASE -> {
                findPreference<IntegerSimpleMenuPreference>("service_type")!!.onPreferenceChangeListener = this
            }

            KEY_SIDEBAR -> {
                findPreference<SwitchPreference>("show_floating")!!.onPreferenceChangeListener = this
                findPreference<Preference>("quick_floating_app")!!.onPreferenceClickListener = this
            }

            KEY_FREEFORM -> {
                findPreference<SeekBarPreference>("freeform_scale")?.apply {
                    onPreferenceClickListener = this@SettingFragment
                }
                findPreference<Preference>("freeform_blacklist_apps")?.onPreferenceClickListener = this
                findPreference<Preference>("landscape_apps")?.onPreferenceClickListener = this
            }

            KEY_NOTIFICATION -> {
                findPreference<SwitchPreference>("notify_freeform")!!.onPreferenceChangeListener = this
                findPreference<Preference>("notification_freeform_apps")!!.onPreferenceClickListener = this
            }

            KEY_OTHER -> {
                findPreference<Preference>("reset_overlay_setting")?.onPreferenceClickListener = this
            }

            else -> {
                accessibilityRFAR = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    updateAllSummaries()
                }

                updateAllSummaries()

                listOf(KEY_BASE, KEY_SIDEBAR, KEY_FREEFORM, KEY_NOTIFICATION, KEY_OTHER).forEach { key ->
                    findPreference<Preference>(key)?.onPreferenceClickListener = this
                }
            }
        }
    }

    private fun updateAllSummaries() {
        updateBaseSummary()
        updateSidebarSummary()
        updateFreeformSummary()
        updateNotificationSummary()
        updateOtherSummary()
    }

    private fun updateBaseSummary() {
        val hideRecent = sp.getBoolean("hide_from_recent", false)
        val serviceType = sp.getInt("service_type", KeepAliveService.SERVICE_TYPE)
        val typeStr = if (serviceType == KeepAliveService.SERVICE_TYPE) "无障碍" else "前台服务"
        findPreference<Preference>(KEY_BASE)?.summary = "隐藏：${if (hideRecent) "是" else "否"} | 保活：$typeStr"
    }

    private fun updateSidebarSummary() {
        val show = sp.getBoolean("show_floating", false)
        val pos = sp.getInt("floating_position_x", 1)
        val posStr = if (pos == 0) "左侧" else "右侧"
        findPreference<Preference>(KEY_SIDEBAR)?.summary = "开关：${if (show) "开启" else "关闭"} | 位置：$posStr"
    }

    private fun updateFreeformSummary() {
        val dpi = sp.getInt("freeform_scale", 50)
        val size = sp.getInt("freeform_size", 75)
        val maxWin = sp.getInt("max_freeform_windows", 2)
        findPreference<Preference>(KEY_FREEFORM)?.summary =
            "长按小窗：${if (sp.getBoolean("popup_freeform_enable", true)) "开" else "关"} | " +
            "最多 $maxWin 个 | DPI: $dpi | 大小: ${size}%"
    }

    private fun updateNotificationSummary() {
        val notify = sp.getBoolean("notify_freeform", true)
        findPreference<Preference>(KEY_NOTIFICATION)?.summary = "接管通知：${if (notify) "开启" else "关闭"}"
    }

    private fun updateOtherSummary() {
        findPreference<Preference>(KEY_OTHER)?.summary = "重置悬浮窗位置等"
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        val key = preference.key
        when {
            key == KEY_BASE || key == KEY_SIDEBAR || key == KEY_FREEFORM ||
                key == KEY_NOTIFICATION || key == KEY_OTHER -> {
                startActivity(
                    Intent(requireContext(), FreeformSettingsActivity::class.java)
                        .putExtra("root_key", key)
                )
                return true
            }

            key == "quick_floating_app" -> {
                startActivity(
                    Intent(requireContext(), ChooseAppsActivity::class.java)
                        .putExtra("type", ChooseAppsActivity.TYPE_FLOATING)
                )
            }

            key == "notification_freeform_apps" -> {
                startActivity(
                    Intent(requireContext(), ChooseAppsActivity::class.java)
                        .putExtra("type", ChooseAppsActivity.TYPE_NOTIFICATION)
                )
            }

            key == "freeform_blacklist_apps" -> {
                startActivity(
                    Intent(requireContext(), ChooseAppsActivity::class.java)
                        .putExtra("type", ChooseAppsActivity.TYPE_BLACKLIST)
                )
            }

            key == "landscape_apps" -> {
                startActivity(
                    Intent(requireContext(), ChooseAppsActivity::class.java)
                        .putExtra("type", ChooseAppsActivity.TYPE_LANDSCAPE)
                )
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
                if (!editText.text.isNullOrBlank()) {
                    val newValue = editText.text!!.toString().toInt()
                    if (newValue in 50..500) {
                        pref.value = newValue
                    }
                }
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
                PermissionUtils.checkPostNotificationPermission(requireActivity())
                if (newValue as Boolean && !PermissionUtils.checkNotificationListenerPermission(requireContext())) {
                    Toast.makeText(requireContext(), "需要通知权限", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(requireContext(), PermissionActivity::class.java))
                    return false
                }
            }
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        if (arguments == null || arguments?.getString(ARG_PREFERENCE_ROOT) == null) {
            updateAllSummaries()
        }
    }
}