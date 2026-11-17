package io.relimus.zflow.ui.radial_area

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import io.relimus.zflow.providers.RadialTriggerSettings

/**
 * 触发区预览。
 *
 * 两种用途共用同一份绘制代码：
 * - [frame] = true：设置页里的「手机轮廓」预览，按真实屏幕比例缩放，方便判断相对位置；
 * - [frame] = false：叠在真实屏幕上的浮层（尺寸就是屏幕尺寸），一比一显示区域。
 *
 * 之所以让两者共用：任何一次绘制上的偏差都会让「预览里看着在这、真实触发却在别处」，
 * 这种 bug 只有一份映射代码才可能彻底避免。
 */
class TriggerAreaPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(56, 90, 160, 255)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.argb(230, 90, 160, 255)
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(60, 128, 128, 128)
    }

    private val frameFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(10, 128, 128, 128)
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(48, 128, 128, 128)
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 6f * density), 0f)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        color = Color.argb(235, 60, 120, 210)
        textAlign = Paint.Align.CENTER
    }

    private val areaRect = RectF()
    private val frameRect = RectF()

    /** 是否绘制手机轮廓。浮层模式必须关掉，否则会画到屏幕外。 */
    var frame: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var config: RadialTriggerSettings.Config = RadialTriggerSettings.Config()
        set(value) {
            field = value
            invalidate()
        }

    /** 浮层模式下用来取「真实屏幕尺寸」的兜底值；默认取 Resource 的显示指标。 */
    var screenSize: Pair<Float, Float>? = null
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val screenW: Float
        val screenH: Float
        val offsetX: Float
        val offsetY: Float
        val scale: Float

        if (frame) {
            val metrics = resources.displayMetrics
            screenW = metrics.widthPixels.toFloat().coerceAtLeast(1f)
            screenH = metrics.heightPixels.toFloat().coerceAtLeast(1f)
            val padding = 8f * density
            val availableW = width - padding * 2f
            val availableH = height - padding * 2f
            scale = minOf(availableW / screenW, availableH / screenH)
            val drawW = screenW * scale
            val drawH = screenH * scale
            offsetX = padding + (availableW - drawW) / 2f
            offsetY = padding + (availableH - drawH) / 2f

            frameRect.set(offsetX, offsetY, offsetX + drawW, offsetY + drawH)
            val radius = 14f * density
            canvas.drawRoundRect(frameRect, radius, radius, frameFillPaint)
            canvas.drawRoundRect(frameRect, radius, radius, framePaint)
            canvas.drawLine(
                frameRect.centerX(),
                frameRect.top,
                frameRect.centerX(),
                frameRect.bottom,
                axisPaint,
            )
        } else {
            val fallback = resources.displayMetrics
            val sizes = screenSize
            screenW = sizes?.first ?: fallback.widthPixels.toFloat()
            screenH = sizes?.second ?: fallback.heightPixels.toFloat()
            scale = 1f
            offsetX = 0f
            offsetY = 0f
        }

        for (area in config.areas(screenW, screenH)) {
            areaRect.set(
                offsetX + area.left * scale,
                offsetY + area.top * scale,
                offsetX + area.right * scale,
                offsetY + area.bottom * scale,
            )
            if (areaRect.isEmpty) continue
            val radius = minOf(6f * density, areaRect.height() / 2f)
            canvas.drawRoundRect(areaRect, radius, radius, fillPaint)
            canvas.drawRoundRect(areaRect, radius, radius, strokePaint)

            // 区域太小时不写字，避免文字溢出到区块外。
            if (areaRect.width() > 64f * density && areaRect.height() > 24f * density) {
                val baseline = areaRect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
                canvas.drawText(
                    context.getString(io.relimus.zflow.R.string.radial_trigger_area_label),
                    areaRect.centerX(),
                    baseline,
                    labelPaint,
                )
            }
        }
    }
}
