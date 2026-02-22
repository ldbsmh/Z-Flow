package io.relimus.zflow.xposed.ui.window

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.sqrt

class SpringAnimator(
    private val stiffness: Float = 200f,
    private val dampingRatio: Float = 0.75f,
    private val onUpdate: (Float) -> Unit,
    private val onEnd: () -> Unit = {}
) {
    companion object {
        private const val REST_VELOCITY = 1f
        private const val REST_DISPLACEMENT = 0.5f
        private const val MAX_DURATION_NS = 3_000_000_000L
    }

    private var value = 0f
    private var velocity = 0f
    private var target = 0f
    var isRunning = false
        private set
    private var startNanos = 0L
    private var prevNanos = 0L

    private val damping = 2f * dampingRatio * sqrt(stiffness)
    private val choreographer: Choreographer = Choreographer.getInstance()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) return

            if (prevNanos == 0L) {
                prevNanos = frameTimeNanos
                startNanos = frameTimeNanos
                choreographer.postFrameCallback(this)
                return
            }

            if (frameTimeNanos - startNanos > MAX_DURATION_NS) {
                settle()
                return
            }

            val dt = (frameTimeNanos - prevNanos) / 1_000_000_000f
            prevNanos = frameTimeNanos
            if (dt <= 0f || dt > 0.1f) {
                choreographer.postFrameCallback(this)
                return
            }

            val x = value - target
            val a = -stiffness * x - damping * velocity
            velocity += a * dt
            value += velocity * dt
            onUpdate(value)

            if (abs(velocity) < REST_VELOCITY && abs(value - target) < REST_DISPLACEMENT) {
                settle()
                return
            }

            choreographer.postFrameCallback(this)
        }
    }

    fun start(startValue: Float, endValue: Float, startVelocity: Float = 0f) {
        cancel()
        value = startValue
        target = endValue
        velocity = startVelocity
        isRunning = true
        prevNanos = 0L
        startNanos = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    fun cancel() {
        isRunning = false
        prevNanos = 0L
        startNanos = 0L
        choreographer.removeFrameCallback(frameCallback)
    }

    private fun settle() {
        choreographer.removeFrameCallback(frameCallback)
        isRunning = false
        prevNanos = 0L
        startNanos = 0L
        value = target
        onUpdate(value)
        onEnd()
    }
}
