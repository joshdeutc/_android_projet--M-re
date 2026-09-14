package com.jo.selfcontrol.ultimate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

class FeatureCircleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // Thin outline stroke
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A1A")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    // White fill (for zoomed rectangle state)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    // Title: always dark
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A1A")
        textSize = 42f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    // Subtitle: light grey, minimal
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA")
        textSize = 22f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    private var featureTitle = ""
    private var summaryText = ""
    var summaryTextColor: Int? = null
        set(value) {
            field = value
            invalidate()
        }
    private var contentLayout: LinearLayout? = null
    private var scrollView: ScrollView? = null
    var onClickListener: (() -> Unit)? = null

    /**
     * 0f = circle outline (summary mode)
     * 1f = white rectangle with border (detail mode)
     */
    var morphProgress = 0f
        set(value) {
            field = value
            invalidate()
        }

    init {
        setWillNotDraw(false)

        scrollView = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isNestedScrollingEnabled = true
            visibility = android.view.View.GONE
        }

        contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(60, 140, 60, 80)
        }

        scrollView?.addView(contentLayout)
        addView(scrollView)
    }

    fun setZoomedState(isZoomed: Boolean) {
        scrollView?.visibility = if (isZoomed) android.view.View.VISIBLE else android.view.View.GONE
        invalidate()
    }

    fun setFeatureTitle(title: String) {
        this.featureTitle = title
        invalidate()
    }

    fun setSummaryText(text: String, color: Int? = null) {
        if (this.summaryText != text || this.summaryTextColor != color) {
            this.summaryText = text
            this.summaryTextColor = color
            invalidate()
        }
    }

    fun addContent(view: android.view.View) {
        contentLayout?.addView(view)
    }

    fun clearContent() {
        contentLayout?.removeAllViews()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 4f

        val rect = RectF(pad, pad, w - pad, h - pad)

        // Corner radius: circle -> rounded rect
        val circleRadius = Math.min(w, h) / 2f - pad
        val rectRadius = 48f
        val cornerRadius = circleRadius + (rectRadius - circleRadius) * morphProgress

        // Always draw white fill (transparent when circle, opaque when rectangle)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)

        // Outline always visible
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, outlinePaint)

        val isZoomedIn = scrollView?.visibility == android.view.View.VISIBLE

        if (isZoomedIn) {
            // Zoomed: title at top, dark text on white background
            titlePaint.textSize = 48f
            titlePaint.color = Color.parseColor("#1A1A1A")
            canvas.drawText(featureTitle, w / 2f, 90f, titlePaint)
        } else {
            // Summary: title + subtitle centered
            val isHub = w > context.resources.displayMetrics.widthPixels * 0.3f
            val density = context.resources.displayMetrics.density

            titlePaint.textSize = if (isHub) 16f * density else 13f * density
            titlePaint.color = Color.parseColor("#1A1A1A")

            val titleY = if (summaryText.isNotEmpty()) h / 2f - 7f * density else h / 2f + 5f * density
            canvas.drawText(featureTitle, w / 2f, titleY, titlePaint)

            if (summaryText.isNotEmpty() && morphProgress < 0.3f) {
                val alphaProgress = (1f - morphProgress / 0.3f).coerceIn(0f, 1f)
                val baseColor = summaryTextColor ?: Color.parseColor("#666666")
                subtitlePaint.color = baseColor
                subtitlePaint.alpha = (alphaProgress * 230).toInt()

                var subSize = if (isHub) 12f * density else 9.5f * density
                subtitlePaint.textSize = subSize
                val maxTextW = (Math.min(w, h) * 0.82f) - pad * 2
                val measuredW = subtitlePaint.measureText(summaryText)
                if (measuredW > maxTextW && measuredW > 0) {
                    subSize = (subSize * (maxTextW / measuredW)).coerceAtLeast(7f * density)
                    subtitlePaint.textSize = subSize
                }
                canvas.drawText(summaryText, w / 2f, h / 2f + (if (isHub) 14f * density else 11f * density), subtitlePaint)
            }
        }

        super.onDraw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            onClickListener?.invoke()
            return true
        }
        return true
    }
}
