package com.reserveboundary

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class ExitCompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var rotationDeg = 0f

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = 0xFF9E9E9E.toInt()
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF2E7D32.toInt()
    }

    private val arrowPath = Path()

    fun setDirection(bearingToExit: Float, deviceHeading: Float) {
        rotationDeg = bearingToExit - deviceHeading
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - circlePaint.strokeWidth

        canvas.drawCircle(cx, cy, radius, circlePaint)

        canvas.save()
        canvas.rotate(rotationDeg, cx, cy)

        arrowPath.reset()
        arrowPath.moveTo(cx, cy - radius * 0.75f)
        arrowPath.lineTo(cx - radius * 0.22f, cy + radius * 0.15f)
        arrowPath.lineTo(cx, cy - radius * 0.05f)
        arrowPath.lineTo(cx + radius * 0.22f, cy + radius * 0.15f)
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowPaint)

        canvas.restore()
    }
}
