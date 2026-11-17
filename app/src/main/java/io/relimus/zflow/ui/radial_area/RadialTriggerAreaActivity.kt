package io.relimus.zflow.ui.radial_area

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.relimus.zflow.R
import io.relimus.zflow.databinding.ActivityRadialTriggerAreaBinding
import io.relimus.zflow.providers.RadialTriggerSettings

/**
 * 触发区域编辑器。
 *
 * 四个滑杆（横/纵/宽/高）+ 镜像开关，改动立刻落盘（没有「确定」按钮）：
 * 这块区是拿来「现场比划」的，如果还要先保存再看效果，用户会来回退出重进。
 *
 * 预览有两层：
 * 1. 页内的手机轮廓（任意设备上都能用）；
 * 2. 真实屏幕浮层（有悬浮窗权限时），编辑时直接看到一个一比一的方框叠在屏幕上。
 */
class RadialTriggerAreaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRadialTriggerAreaBinding

    private var config = RadialTriggerSettings.Config()
    private var overlay: TriggerAreaOverlay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRadialTriggerAreaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.radial_trigger_area_title)

        config = RadialTriggerSettings.read(this)
        overlay = TriggerAreaOverlay(this)

        binding.seekX.max = RadialTriggerSettings.MAX_CENTER_X - RadialTriggerSettings.MIN_CENTER_X
        binding.seekY.max = RadialTriggerSettings.MAX_CENTER_Y - RadialTriggerSettings.MIN_CENTER_Y
        binding.seekW.max = RadialTriggerSettings.MAX_WIDTH - RadialTriggerSettings.MIN_WIDTH
        binding.seekH.max = RadialTriggerSettings.MAX_HEIGHT - RadialTriggerSettings.MIN_HEIGHT

        attachSeek(
            seek = binding.seekX,
            min = RadialTriggerSettings.MIN_CENTER_X,
            label = binding.valueX,
            read = { it.centerXPercent },
            write = { cfg, value -> cfg.copy(centerXPercent = value) },
        )
        attachSeek(
            seek = binding.seekY,
            min = RadialTriggerSettings.MIN_CENTER_Y,
            label = binding.valueY,
            read = { it.centerYPercent },
            write = { cfg, value -> cfg.copy(centerYPercent = value) },
        )
        attachSeek(
            seek = binding.seekW,
            min = RadialTriggerSettings.MIN_WIDTH,
            label = binding.valueW,
            read = { it.widthPercent },
            write = { cfg, value -> cfg.copy(widthPercent = value) },
        )
        attachSeek(
            seek = binding.seekH,
            min = RadialTriggerSettings.MIN_HEIGHT,
            label = binding.valueH,
            read = { it.heightPercent },
            write = { cfg, value -> cfg.copy(heightPercent = value) },
        )

        binding.switchMirror.setOnCheckedChangeListener { _, checked ->
            // render() 回写开关状态时也会走到这里，值没变就直接返回，避免自激。
            if (config.mirror == checked) return@setOnCheckedChangeListener
            apply(config.copy(mirror = checked))
        }
        binding.btnReset.setOnClickListener {
            apply(RadialTriggerSettings.Config())
        }
        binding.textOverlayHint.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        // 从系统权限页回来后悬浮窗可能刚被允许，这里重新判断一次。
        overlay?.show(config)
        updateOverlayHint()
    }

    override fun onPause() {
        super.onPause()
        overlay?.hide()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun attachSeek(
        seek: SeekBar,
        min: Int,
        label: TextView,
        read: (RadialTriggerSettings.Config) -> Int,
        write: (RadialTriggerSettings.Config, Int) -> RadialTriggerSettings.Config,
    ) {
        seek.progress = (read(config) - min).coerceAtLeast(0)
        label.text = getString(R.string.radial_trigger_area_value, read(config))
        seek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress + min
                    label.text = getString(R.string.radial_trigger_area_value, value)
                    if (!fromUser) return
                    apply(write(config, value))
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit

                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            }
        )
    }

    /** 落盘 + 刷新两层预览。没有「保存」按钮，所以这里必须每次都写。 */
    private fun apply(newConfig: RadialTriggerSettings.Config) {
        config = newConfig.sanitized()
        RadialTriggerSettings.write(this, config)
        render()
    }

    private fun render() {
        binding.preview.config = config

        if (binding.switchMirror.isChecked != config.mirror) {
            binding.switchMirror.isChecked = config.mirror
        }

        binding.seekX.progress = config.centerXPercent - RadialTriggerSettings.MIN_CENTER_X
        binding.seekY.progress = config.centerYPercent - RadialTriggerSettings.MIN_CENTER_Y
        binding.seekW.progress = config.widthPercent - RadialTriggerSettings.MIN_WIDTH
        binding.seekH.progress = config.heightPercent - RadialTriggerSettings.MIN_HEIGHT

        binding.valueX.text = getString(R.string.radial_trigger_area_value, config.centerXPercent)
        binding.valueY.text = getString(R.string.radial_trigger_area_value, config.centerYPercent)
        binding.valueW.text = getString(R.string.radial_trigger_area_value, config.widthPercent)
        binding.valueH.text = getString(R.string.radial_trigger_area_value, config.heightPercent)

        overlay?.update(config)
        updateOverlayHint()
    }

    /** 只有浮层真的挂上去才隐藏提示：有权限但 addView 失败时也得让用户知道没显示。 */
    private fun updateOverlayHint() {
        val showing = overlay?.isAttached == true
        binding.textOverlayHint.visibility = if (showing) {
            android.view.View.GONE
        } else {
            android.view.View.VISIBLE
        }
    }
}
