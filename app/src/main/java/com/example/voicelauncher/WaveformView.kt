package com.example.voicelauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.sin

class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class WaveLine(
        val color: Int,
        val cyclesAcrossView: Float,
        val phase: Float,
        val speed: Float,
        val ampScale: Float
    ) {
        var paint: Paint? = null
    }

    private val lines = listOf(
        WaveLine(Color.parseColor("#C1440E"), 1.8f, 0.0f, 0.0016f, 0.55f),
        WaveLine(Color.parseColor("#E8871E"), 2.3f, 1.4f, 0.0022f, 0.85f),
        WaveLine(Color.parseColor("#F5B942"), 2.8f, 2.7f, 0.0028f, 1.0f)
    )

    private val paths = Array(3) { Path() }

    private var targetAmp = 0.08f
    private var currentAmp = 0.08f

    private val attack = 0.4f
    private val release = 0.08f

    private var time = 0L

    init {
        
        val strokeW = 3f * context.resources.displayMetrics.density
        for (line in lines) {
            line.paint = Paint().apply {
                color = line.color
                strokeWidth = strokeW
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            time += 16
            
            if (targetAmp > currentAmp) {
                currentAmp += (targetAmp - currentAmp) * attack
            } else {
                currentAmp += (targetAmp - currentAmp) * release
            }
            
            if (currentAmp < 0.08f) {
                currentAmp = 0.08f
            }

            // Decay targetAmp back to idle baseline multiplicatively
            // so response scales proportionally and speech reactivity isn't crushed
            targetAmp *= 0.94f
            if (targetAmp < 0.08f) {
                targetAmp = 0.08f
            }

            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun updateAmplitude(amplitude: Int) {
        var norm = (amplitude.toFloat() / 32767f) * 10.0f
        if (norm > 1.0f) norm = 1.0f
        if (norm < 0.08f) norm = 0.08f

        targetAmp = norm
    }

    fun reset() {
        targetAmp = 0.08f
        currentAmp = 0.08f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val midY = h / 2f
        val displayMaxAmplitude = h / 2f
        
        val segments = 40
        val step = w / segments

        for (i in lines.indices) {
            val line = lines[i]
            val path = paths[i]
            path.reset()

            val freq = (line.cyclesAcrossView * 2.0 * Math.PI / w).toFloat()

            for (j in 0..segments) {
                val x = j * step
                val y = midY + sin(x * freq + line.phase + time * line.speed).toFloat() * (currentAmp * displayMaxAmplitude * line.ampScale)
                
                if (j == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }
            canvas.drawPath(path, line.paint!!)
        }
    }
}
