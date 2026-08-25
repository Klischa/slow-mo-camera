package com.klischa.slowmocamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Оверлей визуализации умного захвата движения (Smart Motion Tracking HUD).
 * Отображает рамки обнаруженных движущихся объектов и индикатор чувствительности.
 */
class MotionTrackingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var isTrackingActive: Boolean = false
        set(value) {
            field = value
            visibility = if (value) VISIBLE else GONE
            invalidate()
        }

    var motionPercentage: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 100f)
            invalidate()
        }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3300E676")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
    }

    init {
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isTrackingActive) return

        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Рисуем сетку сканирования движения
        val cols = 3
        val rows = 4
        for (i in 1 until cols) {
            canvas.drawLine(w * i / cols, 0f, w * i / cols, h, gridPaint)
        }
        for (j in 1 until rows) {
            canvas.drawLine(0f, h * j / rows, w, h * j / rows, gridPaint)
        }

        // 2. Если обнаружено движение — рисуем рамку захвата цели в центре
        if (motionPercentage > 10f) {
            val targetBox = RectF(w * 0.25f, h * 0.35f, w * 0.75f, h * 0.65f)
            canvas.drawRoundRect(targetBox, 16f, 16f, targetPaint)
            canvas.drawText("🎯 ДВИЖЕНИЕ: ${motionPercentage.toInt()}%", targetBox.left + 16f, targetBox.top - 12f, textPaint)
        }
    }
}
