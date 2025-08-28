package com.example.spiketimer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    @Volatile private var boxes: List<RectF> = emptyList()
    @Volatile private var scores: List<Float> = emptyList()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.GREEN
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88000000.toInt()
    }

    fun setDetections(dets: List<com.example.spiketimer.yolo.TFLiteYoloDetector.Detection>) {
        boxes = dets.map { it.box }
        scores = dets.map { it.score }
        postInvalidateOnAnimation()
    }

    fun clear() {
        boxes = emptyList()
        scores = emptyList()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = min(boxes.size, scores.size)
        for (i in 0 until n) {
            val r = boxes[i]
            canvas.drawRect(r, boxPaint)

            val label = String.format(Locale.US, "spike %.2f", scores[i])
            val tw = textPaint.measureText(label)
            val th = textPaint.textSize + 12f

            val x = r.left.coerceAtLeast(0f)
            val y = r.top.coerceAtLeast(th)

            canvas.drawRect(x, y - th, x + tw + 16f, y, bgPaint)
            canvas.drawText(label, x + 8f, y - 6f, textPaint)
        }
    }
}