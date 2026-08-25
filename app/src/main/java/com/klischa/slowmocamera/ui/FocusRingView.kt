package com.klischa.slowmocamera.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Анимированное кольцо / рамка фокусировки при нажатии (Tap-to-Focus).
 */
class FocusRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var targetX: Float = 0f
    private var targetY: Float = 0f
    private var ringRadius: Float = 60f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    init {
        visibility = GONE
    }

    fun showAt(x: Float, y: Float) {
        targetX = x
        targetY = y
        alpha = 1f
        scaleX = 1.4f
        scaleY = 1.4f
        visibility = VISIBLE

        animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(250)
            .withEndAction {
                animate()
                    .alpha(0f)
                    .setStartDelay(1000)
                    .setDuration(300)
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            visibility = GONE
                        }
                    })
                    .start()
            }
            .start()

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility == VISIBLE) {
            val rect = RectF(
                targetX - ringRadius,
                targetY - ringRadius,
                targetX + ringRadius,
                targetY + ringRadius
            )
            canvas.drawRoundRect(rect, 12f, 12f, paint)
        }
    }
}
