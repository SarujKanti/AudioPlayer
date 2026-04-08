package com.skd.pocketWaves

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.skd.audioplayer.R

class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val barAnimators = mutableListOf<ValueAnimator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_splash)

        val logoCard   = findViewById<CardView>(R.id.splashLogoCard)
        val title      = findViewById<TextView>(R.id.splashTitle)
        val tagline    = findViewById<TextView>(R.id.splashTagline)
        val bar1       = findViewById<View>(R.id.bar1)
        val bar2       = findViewById<View>(R.id.bar2)
        val bar3       = findViewById<View>(R.id.bar3)
        val bar4       = findViewById<View>(R.id.bar4)
        val bar5       = findViewById<View>(R.id.bar5)

        // ── Initial state ────────────────────────────────────────────────
        logoCard.alpha      = 0f
        logoCard.scaleX     = 0.4f
        logoCard.scaleY     = 0.4f
        title.alpha         = 0f
        title.translationY  = 50f
        tagline.alpha       = 0f
        tagline.translationY = 50f

        // ── Logo: scale up with overshoot + fade ─────────────────────────
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(logoCard, "alpha",  0f, 1f),
                ObjectAnimator.ofFloat(logoCard, "scaleX", 0.4f, 1f),
                ObjectAnimator.ofFloat(logoCard, "scaleY", 0.4f, 1f)
            )
            duration     = 700
            startDelay   = 150
            interpolator = OvershootInterpolator(1.2f)
            start()
        }

        // ── Title: slide up + fade ───────────────────────────────────────
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(title, "alpha",        0f, 1f),
                ObjectAnimator.ofFloat(title, "translationY", 50f, 0f)
            )
            duration     = 500
            startDelay   = 750
            interpolator = DecelerateInterpolator(1.5f)
            start()
        }

        // ── Tagline: slide up + fade (offset) ────────────────────────────
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(tagline, "alpha",        0f, 1f),
                ObjectAnimator.ofFloat(tagline, "translationY", 50f, 0f)
            )
            duration     = 500
            startDelay   = 950
            interpolator = DecelerateInterpolator(1.5f)
            start()
        }

        // ── Equalizer bars: animate from bottom pivot ────────────────────
        val bars   = listOf(bar1, bar2, bar3, bar4, bar5)
        // Stagger delays so bars ripple outward from center
        val delays = longArrayOf(200, 100, 0, 100, 200)

        bars.forEachIndexed { idx, bar ->
            bar.post {
                bar.pivotY = bar.height.toFloat()   // grow from bottom
                val anim = ValueAnimator.ofFloat(0.15f, 1f).apply {
                    duration     = 420 + idx * 60L
                    repeatMode   = ValueAnimator.REVERSE
                    repeatCount  = ValueAnimator.INFINITE
                    startDelay   = 1200 + delays[idx]
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { bar.scaleY = it.animatedValue as Float }
                }
                barAnimators.add(anim)
                anim.start()
            }
        }

        // ── Navigate to MainActivity after 2.8 s ─────────────────────────
        handler.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }, 2800)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        barAnimators.forEach { it.cancel() }
    }
}
