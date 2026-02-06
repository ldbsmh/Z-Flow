package com.sunshine.freeform.ui.main

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.sunshine.freeform.R
import com.sunshine.freeform.app.MiFreeform
import com.sunshine.freeform.databinding.FragmentHomeBinding
import com.sunshine.freeform.hook.utils.HookTest
import com.sunshine.freeform.service.KeepAliveService
import com.sunshine.freeform.utils.PermissionUtils

class HomeFragment : Fragment(), View.OnClickListener {

    private var _binding: FragmentHomeBinding? = null

    private val binding get() = _binding!!

    private lateinit var accessibilityRFAR: ActivityResultLauncher<Intent>
    private lateinit var sp: SharedPreferences

    companion object {
        private const val TAG = "HomeFragment"
        private const val COMMON_QUESTION_ZH = "https://github.com/sunshine0523/Mi-FreeForm/blob/master/qa_zh-Hans.md"
        private const val OPEN_API_ZH = "https://github.com/sunshine0523/Mi-FreeForm/blob/master/open_api_zh-Hans.md"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sp = requireContext().getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        checkFreeformManagerStatus()
        checkXposedPermission()
        checkAccessibilityPermission()
        accessibilityRFAR = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkAccessibilityPermission()
        }

        binding.materialCardViewXposedInfo.setOnClickListener(this)
        binding.materialCardViewShizukuInfo.setOnClickListener(this)
        binding.materialCardViewAccessibilityInfo.setOnClickListener(this)
        binding.buttonQuestion.setOnClickListener(this)
        binding.buttonOpenApi.setOnClickListener(this)
    }

    override fun onResume() {
        super.onResume()

        checkAccessibilityPermission()
    }

    private fun checkAccessibilityPermission() {
        val result = PermissionUtils.isAccessibilitySettingsOn(requireContext())

        when (sp.getInt("service_type", KeepAliveService.SERVICE_TYPE)) {
            KeepAliveService.SERVICE_TYPE -> {
                if (!result) {
                    binding.materialCardViewAccessibilityInfo.visibility = View.VISIBLE
                    binding.infoAccessibilityBg.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.warn_color))
                    binding.imageViewAccessibilityService.setImageDrawable(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_error_white))
                    binding.textViewAccessibilityServiceInfo.text = getString(R.string.accessibility_no_start)
                } else {
                    binding.materialCardViewAccessibilityInfo.visibility = View.GONE
                }
            }
            else -> {
                binding.materialCardViewAccessibilityInfo.visibility = View.GONE
            }
        }
    }

    private fun checkFreeformManagerStatus(): Boolean {
        val result = MiFreeform.me.isRunning.value ?: false
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
                MiFreeform.me.recheckConnection()
                if (checkFreeformManagerStatus()) {
                    Snackbar.make(binding.root, getString(R.string.xposed_service_connected), Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(binding.root, getString(R.string.try_to_init_xposed_service), Snackbar.LENGTH_SHORT).show()
                }

            }

            R.id.materialCardView_accessibility_info -> {
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle(getString(R.string.warn))
                    setMessage(getString(R.string.home_accessibility_warn_message))
                    setPositiveButton(getString(R.string.go_to_start_accessibility)) { _, _ ->
                        accessibilityRFAR.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    setNegativeButton(getString(R.string.go_to_change_service_type)) { _, _ ->
                        (requireActivity() as MainActivity).changeToSetting()
                    }
                    create().show()
                }
            }

            R.id.button_question -> {
                val uri = COMMON_QUESTION_ZH.toUri()
                val intent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(intent)
            }

            R.id.button_open_api -> {
                val uri = OPEN_API_ZH.toUri()
                val intent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(intent)
            }
        }
    }
}
