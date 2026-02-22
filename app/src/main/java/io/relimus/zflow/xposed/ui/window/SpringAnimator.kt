package io.relimus.zflow.xposed.ui.window

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.sqrt

class SpringAnimator(
    private val stiffness: Float = 200f,
    private val dampingRatio: Float = 0.75f,
    private val onUpdate: (Float, Float) -> Unit,
    private val onEnd: () -> Unit = {}
) {
    companion object {
        private const val REST_VELOCITY = 1f
        private const val REST_DISPLACEMENT = 0.5f
        private const val MAX_DURATION_NS = 3_000_000_000L
    }

    private var xValue = 0f
    private var yValue = 0f
    private var xVelocity = 0f
    private var yVelocity = 0f
    private var xTarget = 0f
    private var yTarget = 0f
    private var xSettled = false
    private var ySettled = false

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

            if (!xSettled) {
                val ax = -stiffness * (xValue - xTarget) - damping * xVelocity
                xVelocity += ax * dt
                xValue += xVelocity * dt
                if (abs(xVelocity) < REST_VELOCITY && abs(xValue - xTarget) < REST_DISPLACEMENT) {
                    xValue = xTarget; xVelocity = 0f; xSettled = true
                }
            }

            if (!ySettled) {
                val ay = -stiffness * (yValue - yTarget) - damping * yVelocity
                yVelocity += ay * dt
                yValue += yVelocity * dt
                if (abs(yVelocity) < REST_VELOCITY && abs(yValue - yTarget) < REST_DISPLACEMENT) {
                    yValue = yTarget; yVelocity = 0f; ySettled = true
                }
            }

            if (xSettled && ySettled) {
                settle()
                return
            }

            onUpdate(xValue, yValue)
            choreographer.postFrameCallback(this)
        }
    }

    fun start(
        startX: Float, endX: Float, velocityX: Float,
        startY: Float, endY: Float, velocityY: Float
    ) {
        cancel()
        xValue = startX; xTarget = endX; xVelocity = velocityX; xSettled = false
        yValue = startY; yTarget = endY; yVelocity = velocityY; ySettled = false
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
        xValue = xTarget
        yValue = yTarget
        onUpdate(xValue, yValue)
        onEnd()
    }
}
