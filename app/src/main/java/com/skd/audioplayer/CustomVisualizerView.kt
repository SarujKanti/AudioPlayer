package com.skd.audioplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.audiofx.Visualizer
import android.util.AttributeSet

class CustomVisualizerView : View {
    private var bytes: ByteArray? = null
    private var visualizer: Visualizer? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    // Purple-to-violet gradient palette matching the accent color
    private val barColors = intArrayOf(
        Color.parseColor("#4C46CC"),
        Color.parseColor("#5A54D4"),
        Color.parseColor("#6C63FF"),
        Color.parseColor("#7B74FF"),
        Color.parseColor("#8B83FF"),
        Color.parseColor("#9C95FF"),
        Color.parseColor("#8B83FF"),
        Color.parseColor("#7B74FF"),
        Color.parseColor("#6C63FF"),
        Color.parseColor("#5A54D4")
    )

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    fun releaseVisualizer() {
        visualizer?.release()
        visualizer = null
    }

    fun setPlayer(audioSessionId: Int) {
        visualizer?.release()
        visualizer = null
        try {
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: Visualizer, waveform: ByteArray, samplingRate: Int
                    ) {
                        bytes = waveform
                        postInvalidate()
                    }
                    override fun onFftDataCapture(
                        v: Visualizer, fft: ByteArray, samplingRate: Int
                    ) {}
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
                enabled = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = bytes ?: return
        if (data.isEmpty()) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        val numBars = 48
        val step = maxOf(1, data.size / numBars)
        val slotWidth = viewWidth / numBars
        val barWidth = slotWidth * 0.6f
        val barOffset = (slotWidth - barWidth) / 2f
        val cornerRadius = barWidth / 2f
        val minBarHeight = viewHeight * 0.06f

        for (i in 0 until numBars) {
            val sampleIdx = (i * step).coerceAtMost(data.size - 1)
            val amplitude = (data[sampleIdx] + 128).toFloat() / 256f
            val barHeight = (amplitude * viewHeight * 0.92f).coerceAtLeast(minBarHeight)

            val left = i * slotWidth + barOffset
            val top = viewHeight - barHeight
            val right = left + barWidth

            paint.color = barColors[i % barColors.size]
            rect.set(left, top, right, viewHeight)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        }

        postInvalidateDelayed(33L) // ~30 fps
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        visualizer?.release()
        visualizer = null
    }
}
