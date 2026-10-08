package com.sonalika.chassisscan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Draws the yellow corner brackets over the camera preview. */
class GuideView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var stampMode = false
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F2BE00")
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 4
        strokeCap = Paint.Cap.SQUARE
    }
    private val shade = Paint().apply { color = Color.parseColor("#55000000") }
    private val path = Path()

    fun guideRect(w: Float, h: Float): RectF {
        return if (stampMode) {
            val gw = w * STAMP_W; val gh = h * STAMP_H
            RectF((w - gw) / 2, (h - gh) / 2, (w + gw) / 2, (h + gh) / 2)
        } else {
            val s = minOf(w, h) * QR_SIZE
            RectF((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val r = guideRect(w, h)
        if (stampMode) {
            canvas.drawRect(0f, 0f, w, r.top, shade)
            canvas.drawRect(0f, r.bottom, w, h, shade)
            canvas.drawRect(0f, r.top, r.left, r.bottom, shade)
            canvas.drawRect(r.right, r.top, w, r.bottom, shade)
        }
        val c = resources.displayMetrics.density * 26
        path.reset()
        path.moveTo(r.left, r.top + c); path.lineTo(r.left, r.top); path.lineTo(r.left + c, r.top)
        path.moveTo(r.right - c, r.top); path.lineTo(r.right, r.top); path.lineTo(r.right, r.top + c)
        path.moveTo(r.right, r.bottom - c); path.lineTo(r.right, r.bottom); path.lineTo(r.right - c, r.bottom)
        path.moveTo(r.left + c, r.bottom); path.lineTo(r.left, r.bottom); path.lineTo(r.left, r.bottom - c)
        canvas.drawPath(path, paint)
    }

    companion object {
        const val STAMP_W = 0.90f
        const val STAMP_H = 0.32f
        const val QR_SIZE = 0.70f
    }
}
