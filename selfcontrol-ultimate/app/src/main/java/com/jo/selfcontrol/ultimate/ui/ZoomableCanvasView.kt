package com.jo.selfcontrol.ultimate.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.min

class ZoomableCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var zoomedCircle: FeatureCircleView? = null
        private set

    // Saved original layout params for zoom-out restore
    private var savedLeftMargin = 0
    private var savedTopMargin = 0
    private var savedWidth = 0
    private var savedHeight = 0

    // Line drawing animation progress
    private var lineDrawProgress = 0f
    private var animationStarted = false

    // Floating/breathing dynamic animation
    private var floatAnimator: ValueAnimator? = null
    private var isFloating = false

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCCCCC")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA")
        style = Paint.Style.FILL
    }

    init {
        setBackgroundColor(Color.WHITE)
        clipChildren = false
        setWillNotDraw(false)
    }

    override fun dispatchDraw(canvas: Canvas) {
        drawConnectionLines(canvas)
        super.dispatchDraw(canvas)
    }

    private fun drawConnectionLines(canvas: Canvas) {
        if (lineDrawProgress <= 0f) return

        val circles = mutableListOf<FeatureCircleView>()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is FeatureCircleView) {
                circles.add(child)
            }
        }
        if (circles.size < 2) return

        val hub = circles[0]
        val hx = hub.x + hub.width / 2f
        val hy = hub.y + hub.height / 2f
        val hubRadius = Math.min(hub.width, hub.height) / 2f

        val satelliteCount = circles.size - 1
        for (i in 1 until circles.size) {
            val sat = circles[i]
            val sx = sat.x + sat.width / 2f
            val sy = sat.y + sat.height / 2f
            val satRadius = Math.min(sat.width, sat.height) / 2f

            // Direction vector from hub center to satellite center
            val dx = sx - hx
            val dy = sy - hy
            val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (dist < 1f) continue

            val ux = dx / dist
            val uy = dy / dist

            // Line starts at hub edge, ends at satellite edge
            val edgeStartX = hx + ux * hubRadius
            val edgeStartY = hy + uy * hubRadius
            val edgeEndX = sx - ux * satRadius
            val edgeEndY = sy - uy * satRadius

            val lineIndex = i - 1
            val lineStart = lineIndex.toFloat() / satelliteCount
            val lineEnd = (lineIndex + 1).toFloat() / satelliteCount
            val localProgress = ((lineDrawProgress - lineStart) / (lineEnd - lineStart)).coerceIn(0f, 1f)

            if (localProgress > 0f) {
                val drawEndX = edgeStartX + (edgeEndX - edgeStartX) * localProgress
                val drawEndY = edgeStartY + (edgeEndY - edgeStartY) * localProgress
                canvas.drawLine(edgeStartX, edgeStartY, drawEndX, drawEndY, linePaint)
                canvas.drawCircle(drawEndX, drawEndY, 4f, dotPaint)
                canvas.drawCircle(edgeStartX, edgeStartY, 4f, dotPaint)
            }
        }
    }

    fun startEntryAnimation() {
        if (animationStarted) return
        animationStarted = true

        val circles = mutableListOf<FeatureCircleView>()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is FeatureCircleView) {
                circles.add(child)
                child.alpha = 0f
                child.scaleX = 0f
                child.scaleY = 0f
            }
        }
        if (circles.isEmpty()) return

        // Phase 1: Hub pops in
        val hub = circles[0]
        hub.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(700)
            .setInterpolator(OvershootInterpolator(1.2f))
            .withEndAction {
                // Phase 2: Satellites pop in
                var delay = 0L
                for (i in 1 until circles.size) {
                    circles[i].animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(500)
                        .setStartDelay(delay)
                        .setInterpolator(OvershootInterpolator(1.8f))
                        .start()
                    delay += 150L
                }

                // Phase 3: Lines draw
                postDelayed({
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 800
                        interpolator = DecelerateInterpolator(1.5f)
                        addUpdateListener {
                            lineDrawProgress = it.animatedValue as Float
                            invalidate()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                startFloatingAnimation()
                            }
                        })
                        start()
                    }
                }, delay + 300L)
            }
            .start()
    }

    fun startFloatingAnimation() {
        if (isFloating || zoomedCircle != null) return
        isFloating = true

        // Ensure all circles and connection lines are visible if entry animation was interrupted or skipped
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is FeatureCircleView && child.alpha < 1f) {
                child.alpha = 1f
                child.scaleX = 1f
                child.scaleY = 1f
            }
        }
        if (lineDrawProgress < 1f) {
            lineDrawProgress = 1f
            invalidate()
        }

        val density = context.resources.displayMetrics.density
        // Subtle floating amplitude: ~4.5dp for satellites, ~2.2dp for hub
        val satAmpY = 4.5f * density
        val satAmpX = 3.2f * density
        val hubAmpY = 2.2f * density
        val hubAmpX = 1.6f * density

        val startUptime = SystemClock.uptimeMillis()

        floatAnimator?.cancel()
        floatAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                if (!isFloating || zoomedCircle != null) return@addUpdateListener
                val elapsedSec = (SystemClock.uptimeMillis() - startUptime) / 1000.0

                var circleIdx = 0
                for (i in 0 until childCount) {
                    val child = getChildAt(i)
                    if (child is FeatureCircleView) {
                        val isHub = circleIdx == 0
                        val ampY = if (isHub) hubAmpY else satAmpY
                        val ampX = if (isHub) hubAmpX else satAmpX

                        // Gentle organic frequencies (~2.2s to 2.8s periods)
                        val freqY = 0.40 + (circleIdx % 3) * 0.06
                        val freqX = 0.32 + ((circleIdx + 2) % 3) * 0.05
                        val phaseY = circleIdx * 1.15
                        val phaseX = circleIdx * 1.45 + 0.85

                        val dy = (Math.sin(elapsedSec * freqY * 2.0 * Math.PI + phaseY) * ampY).toFloat()
                        val dx = (Math.cos(elapsedSec * freqX * 2.0 * Math.PI + phaseX) * ampX).toFloat()

                        child.translationX = dx
                        child.translationY = dy
                        circleIdx++
                    }
                }
                invalidate() // Redraw connection lines to track moving circles
            }
            start()
        }
    }

    fun stopFloatingAnimation(animateToZero: Boolean = true) {
        isFloating = false
        floatAnimator?.cancel()
        floatAnimator = null

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is FeatureCircleView) {
                if (animateToZero) {
                    child.animate().translationX(0f).translationY(0f).setDuration(220).start()
                } else {
                    child.translationX = 0f
                    child.translationY = 0f
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animationStarted && zoomedCircle == null) {
            startFloatingAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopFloatingAnimation(animateToZero = false)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return super.dispatchTouchEvent(ev)
    }

    fun zoomInto(circle: FeatureCircleView) {
        if (zoomedCircle == circle) return
        zoomedCircle = circle
        stopFloatingAnimation(animateToZero = true)

        // Save original layout params
        val lp = circle.layoutParams as FrameLayout.LayoutParams
        savedLeftMargin = lp.leftMargin
        savedTopMargin = lp.topMargin
        savedWidth = lp.width
        savedHeight = lp.height

        circle.bringToFront()

        // Fade out other circles
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is FeatureCircleView && child != circle) {
                child.animate().alpha(0f).setDuration(250).start()
            }
        }

        // Fade out lines
        ValueAnimator.ofFloat(lineDrawProgress, 0f).apply {
            duration = 250
            addUpdateListener { lineDrawProgress = it.animatedValue as Float; invalidate() }
            start()
        }

        // Animate circle -> padded full screen rectangle
        val density = context.resources.displayMetrics.density
        val padX = (16 * density).toInt()
        val padTop = (48 * density).toInt() // Status bar padding
        val padBottom = (48 * density).toInt() // Nav bar padding

        val targetX = padX
        val targetY = padTop
        val targetW = width - padX * 2
        val targetH = height - padTop - padBottom

        val startX = savedLeftMargin
        val startY = savedTopMargin
        val startW = savedWidth
        val startH = savedHeight

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                val newLp = circle.layoutParams as FrameLayout.LayoutParams
                newLp.leftMargin = (startX + (targetX - startX) * t).toInt()
                newLp.topMargin = (startY + (targetY - startY) * t).toInt()
                newLp.width = (startW + (targetW - startW) * t).toInt()
                newLp.height = (startH + (targetH - startH) * t).toInt()
                circle.layoutParams = newLp
                circle.morphProgress = t
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    circle.setZoomedState(true)
                }
            })
            start()
        }
    }

    fun zoomOut() {
        val circle = zoomedCircle ?: return
        circle.setZoomedState(false)
        zoomedCircle = null

        val startX = (circle.layoutParams as FrameLayout.LayoutParams).leftMargin
        val startY = (circle.layoutParams as FrameLayout.LayoutParams).topMargin
        val startW = circle.layoutParams.width
        val startH = circle.layoutParams.height

        // Step 1: Morph rectangle back to circle at original position
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                val newLp = circle.layoutParams as FrameLayout.LayoutParams
                newLp.leftMargin = (startX + (savedLeftMargin - startX) * t).toInt()
                newLp.topMargin = (startY + (savedTopMargin - startY) * t).toInt()
                newLp.width = (startW + (savedWidth - startW) * t).toInt()
                newLp.height = (startH + (savedHeight - startH) * t).toInt()
                circle.layoutParams = newLp
                circle.morphProgress = 1f - t
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Ensure exact original params are restored
                    val lp = circle.layoutParams as FrameLayout.LayoutParams
                    lp.leftMargin = savedLeftMargin
                    lp.topMargin = savedTopMargin
                    lp.width = savedWidth
                    lp.height = savedHeight
                    circle.layoutParams = lp
                    circle.morphProgress = 0f

                    // Step 2: Fade in other circles
                    for (i in 0 until childCount) {
                        val child = getChildAt(i)
                        if (child is FeatureCircleView) {
                            child.animate().alpha(1f).setDuration(350).start()
                        }
                    }

                    // Step 3: Draw lines AFTER circles are visible and in position
                    postDelayed({
                        ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = 600
                            interpolator = DecelerateInterpolator(1.5f)
                            addUpdateListener {
                                lineDrawProgress = it.animatedValue as Float
                                invalidate()
                            }
                            addListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    startFloatingAnimation()
                                }
                            })
                            start()
                        }
                    }, 400)
                }
            })
            start()
        }
    }
}
