package com.klischa.slowmocamera.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Size
import android.view.Surface
import android.view.TextureView

/**
 * TextureView с автоматической подгонкой пропорций и матричной компенсацией ориентации устройства.
 */
class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {

    private var ratioWidth = 0
    private var ratioHeight = 0

    fun setAspectRatio(width: Int, height: Int) {
        require(width >= 0 && height >= 0) { "Размеры не могут быть отрицательными" }
        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(width, height)
        } else {
            if (width < height * ratioWidth / ratioHeight) {
                setMeasuredDimension(width, width * ratioHeight / ratioWidth)
            } else {
                setMeasuredDimension(height * ratioWidth / ratioHeight, height)
            }
        }
    }

    /**
     * Конфигурирует матрицу трансформации для компенсации поворота экрана и ориентации сенсора (включая фронтальную камеру).
     */
    fun configureTransform(
        viewWidth: Int,
        viewHeight: Int,
        previewSize: Size?,
        displayRotation: Int,
        sensorOrientation: Int,
        isFrontCamera: Boolean
    ) {
        if (previewSize == null || viewWidth == 0 || viewHeight == 0) return

        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == displayRotation || Surface.ROTATION_270 == displayRotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (displayRotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == displayRotation) {
            matrix.postRotate(180f, centerX, centerY)
        }

        if (isFrontCamera) {
            // Зеркальное отображение для фронтальной камеры
            matrix.postScale(-1f, 1f, centerX, centerY)
        }

        setTransform(matrix)
    }
}
