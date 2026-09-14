package com.offlinevoicerelay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 9
    private val barPaints = List(barCount) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private var isAnimating = false
    private var targetAmplitude = 0f
    private val currentHeights = FloatArray(barCount) { 0.15f }

    init {
        val green = 0xFF10B981.toInt()
        val cyan = 0xFF0284C7.toInt()

        barPaints.forEachIndexed { i, paint ->
            // Gradient hue shift from green to cyan across bars
            val ratio = i.toFloat() / (barCount - 1)
            paint.color = interpolateColor(green, cyan, ratio)
            paint.style = Paint.Style.FILL
            paint.strokeCap = Paint.Cap.ROUND
        }
    }

    fun setAmplitude(amp: Float, active: Boolean) {
        targetAmplitude = amp.coerceIn(0f, 1f)
        isAnimating = active
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val spacing = 8f
        val totalSpacing = spacing * (barCount + 1)
        val barWidth = (w - totalSpacing) / barCount
        val minHeight = h * 0.12f
        val maxHeight = h * 0.85f

        var needsRedraw = false

        for (i in 0 until barCount) {
            val centerMultiplier = 1.0f - Math.abs(i - barCount / 2) * 0.15f
            val noise = if (isAnimating) Random.nextFloat() * 0.25f else 0f
            val targetH = if (isAnimating) {
                (minHeight + (maxHeight - minHeight) * (targetAmplitude + noise) * centerMultiplier).coerceIn(minHeight, maxHeight)
            } else {
                minHeight
            }

            // Smooth interpolation (lerp)
            currentHeights[i] += (targetH - currentHeights[i]) * 0.3f

            val barH = currentHeights[i]
            val left = spacing + i * (barWidth + spacing)
            val top = (h - barH) / 2f
            val right = left + barWidth
            val bottom = top + barH

            val radius = barWidth / 2f
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, barPaints[i])

            if (Math.abs(targetH - currentHeights[i]) > 0.5f) {
                needsRedraw = true
            }
        }

        if (isAnimating || needsRedraw) {
            postInvalidateDelayed(30)
        }
    }

    private fun interpolateColor(color1: Int, color2: Int, ratio: Float): Int {
        val r1 = (color1 shr 16) and 0xFF
        val g1 = (color1 shr 8) and 0xFF
        val b1 = color1 and 0xFF

        val r2 = (color2 shr 16) and 0xFF
        val g2 = (color2 shr 8) and 0xFF
        val b2 = color2 and 0xFF

        val r = (r1 + (r2 - r1) * ratio).toInt()
        val g = (g1 + (g2 - g1) * ratio).toInt()
        val b = (b1 + (b2 - b1) * ratio).toInt()

        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
