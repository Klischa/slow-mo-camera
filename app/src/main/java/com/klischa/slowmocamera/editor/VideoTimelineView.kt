package com.klischa.slowmocamera.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Интерактивный таймлайн для визуальной обрезки (Trim) и перемотки видео.
 */
class VideoTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var videoDurationMs: Long = 10_000L
        set(value) {
            field = Math.max(1000L, value)
            trimEndMs = field
            invalidate()
        }

    var trimStartMs: Long = 0L
        private set

    var trimEndMs: Long = 10_000L
        private set

    var currentPositionMs: Long = 0L
        set(value) {
            field = value.coerceIn(0L, videoDurationMs)
            invalidate()
        }

    var onTrimChangedListener: ((startMs: Long, endMs: Long) -> Unit)? = null
    var onSeekListener: ((positionMs: Long) -> Unit)? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
    }

    private val selectedRangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
    }

    private val thumbWidth = 36f
    private var activeDrag: DragTarget = DragTarget.NONE

    private enum class DragTarget { NONE, START_THUMB, END_THUMB, PROGRESS }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Фон полосы
        val barRect = RectF(0f, 10f, w, h - 10f)
        canvas.drawRoundRect(barRect, 8f, 8f, bgPaint)

        // Координаты маркеров
        val startX = (trimStartMs.toFloat() / videoDurationMs.toFloat()) * w
        val endX = (trimEndMs.toFloat() / videoDurationMs.toFloat()) * w
        val progressX = (currentPositionMs.toFloat() / videoDurationMs.toFloat()) * w

        // 2. Выделенная область обрезки
        val selectedRect = RectF(startX, 10f, endX, h - 10f)
        canvas.drawRoundRect(selectedRect, 8f, 8f, selectedRangePaint)

        // 3. Ползунки начала и конца
        canvas.drawRoundRect(RectF(startX - thumbWidth / 2, 0f, startX + thumbWidth / 2, h), 6f, 6f, thumbPaint)
        canvas.drawRoundRect(RectF(endX - thumbWidth / 2, 0f, endX + thumbWidth / 2, h), 6f, 6f, thumbPaint)

        // 4. Курсор текущей позиции
        canvas.drawLine(progressX, 0f, progressX, h, progressPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val w = width.toFloat()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val startX = (trimStartMs.toFloat() / videoDurationMs.toFloat()) * w
                val endX = (trimEndMs.toFloat() / videoDurationMs.toFloat()) * w

                activeDrag = when {
                    Math.abs(x - startX) <= thumbWidth * 1.5f -> DragTarget.START_THUMB
                    Math.abs(x - endX) <= thumbWidth * 1.5f -> DragTarget.END_THUMB
                    else -> {
                        val seekMs = ((x / w) * videoDurationMs).toLong().coerceIn(0L, videoDurationMs)
                        currentPositionMs = seekMs
                        onSeekListener?.invoke(seekMs)
                        DragTarget.PROGRESS
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val ratio = (x / w).coerceIn(0f, 1f)
                val targetMs = (ratio * videoDurationMs).toLong()

                when (activeDrag) {
                    DragTarget.START_THUMB -> {
                        trimStartMs = targetMs.coerceIn(0L, trimEndMs - 500L)
                        onTrimChangedListener?.invoke(trimStartMs, trimEndMs)
                    }
                    DragTarget.END_THUMB -> {
                        trimEndMs = targetMs.coerceIn(trimStartMs + 500L, videoDurationMs)
                        onTrimChangedListener?.invoke(trimStartMs, trimEndMs)
                    }
                    DragTarget.PROGRESS -> {
                        currentPositionMs = targetMs
                        onSeekListener?.invoke(targetMs)
                    }
                    DragTarget.NONE -> {}
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeDrag = DragTarget.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
