package io.relimus.zflow.ui.main

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.relimus.zflow.R
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.databinding.FragmentHomeBinding
import io.relimus.zflow.ui.permission.PermissionActivity
import io.relimus.zflow.xposed.hook.utils.HookTest
import io.relimus.zflow.service.KeepAliveService
import io.relimus.zflow.utils.PermissionUtils
import io.relimus.zflow.utils.cast

class HomeFragment : Fragment(), View.OnClickListener {

    private var _binding: FragmentHomeBinding? = null

    private val binding get() = _binding!!

    private lateinit var accessibilityRFAR: ActivityResultLauncher<Intent>
    private lateinit var sp: SharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sp = requireContext().getSharedPreferences(ZFlow.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        // recheckConnection 的真实结果要 500ms 后才写入 LiveData；持续观察，
        // 状态卡在连接建立后自动变绿，而不是每次点击都读到旧值
        ZFlow.me.isRunning.observe(viewLifecycleOwner) {
            checkFreeformManagerStatus()
        }
        checkFreeformManagerStatus()
        checkXposedPermission()
        checkAccessibilityPermission()
        renderDeviceInfo()
        accessibilityRFAR = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkAccessibilityPermission()
        }

        binding.materialCardViewXposedInfo.setOnClickListener(this)
        binding.materialCardViewShizukuInfo.setOnClickListener(this)
        binding.materialCardViewAccessibilityInfo.setOnClickListener(this)
        binding.buttonPermission.setOnClickListener(this)
    }

    override fun onResume() {
        super.onResume()

        checkAccessibilityPermission()
    }

    private fun checkAccessibilityPermission() {
        val enabled = PermissionUtils.isAccessibilitySettingsOn(requireContext())
        val running = KeepAliveService.isServiceRunning
    
        when (sp.getInt("service_type", KeepAliveService.SERVICE_TYPE)) {
            KeepAliveService.SERVICE_TYPE -> {
                when {
                    enabled && running -> {
                        binding.materialCardViewAccessibilityInfo.visibility = View.GONE
                    }
    
                    enabled -> {
                        binding.materialCardViewAccessibilityInfo.visibility = View.VISIBLE
                        binding.infoAccessibilityBg.setBackgroundColor(
                            ContextCompat.getColor(requireContext(), R.color.warn_color)
                        )
                        binding.imageViewAccessibilityService.setImageDrawable(
                            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_error_white)
                        )
                        binding.textViewAccessibilityServiceInfo.text =
                            getString(R.string.accessibility_authorized_not_running)
                    }
    
                    else -> {
                        binding.materialCardViewAccessibilityInfo.visibility = View.VISIBLE
                        binding.infoAccessibilityBg.setBackgroundColor(
                            ContextCompat.getColor(requireContext(), R.color.warn_color)
                        )
                        binding.imageViewAccessibilityService.setImageDrawable(
                            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_error_white)
                        )
                        binding.textViewAccessibilityServiceInfo.text =
                            getString(R.string.accessibility_no_start)
                    }
                }
            }
    
            else -> {
                binding.materialCardViewAccessibilityInfo.visibility = View.GONE
            }
        }
    }
    
    private fun checkFreeformManagerStatus(): Boolean {
        val result = ZFlow.me.isRunning.value ?: false
        if (result) {
            binding.infoShizukuBg.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.success_color))
            binding.imageViewShizukuService.setImageDrawable(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_done))
            binding.textViewShizukuServiceInfo.text = getString(R.string.xposed_service_connected)
        } else {
            binding.infoShizukuBg.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.warn_color))
            binding.imageViewShizukuService.setImageDrawable(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_error_white))
            binding.textViewShizukuServiceInfo.text = getString(R.string.xposed_service_not_connected)
        }
        return result
    }

    private fun checkXposedPermission() {
        val isActive = HookTest.checkXposed()
        if (isActive) {
            binding.infoXposedBg.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.success_color))
            binding.imageViewXposedService.setImageDrawable(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_done))
            binding.textViewXposedServiceInfo.text = getString(R.string.xposed_start_short)
            binding.textViewXposedServiceInfo.requestFocus()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onClick(v: View) {
        when(v.id) {
            R.id.materialCardView_xposed_info -> {
                if (HookTest.checkXposed()) {
                    Snackbar.make(binding.root, getString(R.string.xposed_start), Snackbar.LENGTH_SHORT).show()
                } else {
                    MaterialAlertDialogBuilder(requireContext()).apply {
                        setTitle(getString(R.string.warn))
                        setMessage(getString(R.string.try_to_init_xposed))
                        create().show()
                    }
                }
            }
            R.id.materialCardView_shizuku_info -> {
                // 结果延迟到达，由 onViewCreated 里的 isRunning 观察者刷新状态卡
                ZFlow.me.recheckConnection()
            }

            R.id.materialCardView_accessibility_info -> {
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle(getString(R.string.warn))
                    setMessage(getString(R.string.home_accessibility_warn_message))
                    setPositiveButton(getString(R.string.go_to_start_accessibility)) { _, _ ->
                        accessibilityRFAR.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    setNegativeButton(getString(R.string.go_to_change_service_type)) { _, _ ->
                        (requireActivity().cast<MainActivity>()).changeToSetting()
                    }
                    create().show()
                }
            }

            R.id.button_permission -> {
                startActivity(Intent(requireContext(), PermissionActivity::class.java))
            }
        }
    }

    /** 填充设备信息卡：厂商 / 型号 / Android 版本 / 开发代号 / API 级别 */
    private fun renderDeviceInfo() {
        val codename = Build.VERSION.CODENAME.takeIf { it != "REL" }
            // 正式版 CODENAME 固定为 REL，退回按 API 级别映射已知代号
            ?: mapOf(
                33 to "Tiramisu",
                34 to "UpsideDownCake",
                35 to "VanillaIceCream",
                36 to "Baklava",
            )[Build.VERSION.SDK_INT] ?: "REL"
        binding.deviceRowManufacturer.text =
            getString(R.string.device_label_manufacturer, Build.MANUFACTURER)
        binding.deviceRowModel.text =
            getString(R.string.device_label_model, Build.MODEL)
        binding.deviceRowAndroid.text =
            getString(R.string.device_label_android, Build.VERSION.RELEASE)
        binding.deviceRowCodename.text =
            getString(R.string.device_label_codename, codename)
        binding.deviceRowApi.text =
            getString(R.string.device_label_api, Build.VERSION.SDK_INT)
    }
}
