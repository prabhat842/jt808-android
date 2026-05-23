package com.example.jt808terminal.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.jt808terminal.R

/**
 * GoatAI Fleet Terminal splash screen.
 *
 * Displays for [DISPLAY_MS] ms with staggered fade-in animations, then
 * transitions to [MainActivity].  Acts as the launcher activity so that
 * MainActivity receives its permissions from a fresh Intent stack rather
 * than from a cold start.
 *
 * Animation sequence (all durations in ms):
 *   0    → 700   Logo mark fades in + slides up 24dp
 *   300  → 800   "GoatAI" wordmark fades in
 *   550  → 950   Divider + subtitle fades in
 *   750  → 1050  Protocol tags fade in
 *   900  → 1300  Bottom bar fades in
 *   [DISPLAY_MS] → Navigate to MainActivity (cross-fade transition)
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val DISPLAY_MS = 2600L
    }

    private val navHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: remove system window insets so the dark background fills
        // status bar and navigation bar completely.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        setContentView(R.layout.activity_splash)

        val logo     = findViewById<View>(R.id.splash_logo)
        val wordmark = findViewById<View>(R.id.splash_wordmark)
        val divider  = findViewById<View>(R.id.splash_divider)
        val subtitle = findViewById<View>(R.id.splash_subtitle)
        val tags     = findViewById<View>(R.id.splash_tags)
        val bottom   = findViewById<LinearLayout>(R.id.splash_bottom)

        // ── Logo: fade-in + upward slide ──────────────────────────────
        val logoFade = ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f)
        val logoSlide = ObjectAnimator.ofFloat(logo, View.TRANSLATION_Y, dpToPx(24f), 0f)
        AnimatorSet().apply {
            playTogether(logoFade, logoSlide)
            duration = 700
            startDelay = 0
            interpolator = DecelerateInterpolator(2f)
            start()
        }

        // ── Wordmark ──────────────────────────────────────────────────
        animate(wordmark, startDelay = 300, duration = 500)

        // ── Divider + subtitle (stagger 100ms apart) ──────────────────
        animate(divider,  startDelay = 550, duration = 400)
        animate(subtitle, startDelay = 650, duration = 400)

        // ── Protocol tags ─────────────────────────────────────────────
        animate(tags, startDelay = 750, duration = 400)

        // ── Bottom bar ────────────────────────────────────────────────
        animate(bottom, startDelay = 900, duration = 400)

        // ── Animate loading dots: sequential pulse ────────────────────
        val dot1 = bottom.findViewById<View>(R.id.dot1)
        val dot2 = bottom.findViewById<View>(R.id.dot2)
        val dot3 = bottom.findViewById<View>(R.id.dot3)
        if (dot1 != null && dot2 != null && dot3 != null) {
            pulseDots(dot1, dot2, dot3, startAfterMs = 1100)
        }

        // ── Navigate to MainActivity after display period ─────────────
        navHandler.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, DISPLAY_MS)
    }

    override fun onDestroy() {
        navHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun animate(view: View, startDelay: Long, duration: Long) {
        ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f).apply {
            this.duration    = duration
            this.startDelay  = startDelay
            interpolator     = AccelerateDecelerateInterpolator()
            start()
        }
    }

    /**
     * Loops an alpha pulse across the three loading dots with a 250ms stagger,
     * approximating the .status-dot pulse-dot animation in GoatAI's style.css.
     */
    private fun pulseDots(d1: View, d2: View, d3: View, startAfterMs: Long) {
        val handler = Handler(Looper.getMainLooper())
        fun pulseCycle() {
            for ((i, dot) in listOf(d1, d2, d3).withIndex()) {
                val delay = i * 250L
                ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f, 1f).apply {
                    duration    = 750
                    startDelay  = delay
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
            }
            handler.postDelayed(::pulseCycle, 1500)
        }
        handler.postDelayed(::pulseCycle, startAfterMs)
    }

    private fun dpToPx(dp: Float): Float =
        dp * resources.displayMetrics.density
}
