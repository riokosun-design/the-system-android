package com.thesystem.app.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * SOLO LEVELING SYSTEM LOCK — the un-dismissible full-screen overlay drawn
 * over blocked apps and adult content (v0.24.0, spec §5/§6).
 *
 * Pure framework views (no Compose inside a WindowManager, no resources to
 * preload): rock-solid on 2GB devices, zero allocation churn after attach.
 * The window itself is added by [SystemDisciplineService] as
 * TYPE_ACCESSIBILITY_OVERLAY, so it needs no SYSTEM_ALERT_WINDOW permission.
 */
class SystemLockOverlayView(
    context: Context,
    accent: Int,
    header: String,
    body: String,
    cta: String,
    onCta: () -> Unit,
) : FrameLayout(context) {

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = accent
    }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true      // swallow every touch that lands here
        isFocusable = true
        setWillNotDraw(false)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val m = dp(28f).toInt()
            setPadding(m, m, m, m)
        }

        fun label(text: String, sizeSp: Float, color: Int, spacing: Float, bold: Boolean = true): TextView =
            TextView(context).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                setTextColor(color)
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                letterSpacing = spacing
                gravity = Gravity.CENTER
            }

        column.addView(label(header, 15f, accent, 0.14f))
        column.addView(label(body, 13f, Color.WHITE, 0.02f, bold = false).apply {
            setLineSpacing(0f, 1.35f)
            setPadding(0, dp(20f).toInt(), 0, dp(8f).toInt())
        })

        val button = TextView(context).apply {
            text = "[ $cta ]"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            gravity = Gravity.CENTER
            setBackgroundColor(accent)
            val hp = dp(22f).toInt(); val vp = dp(14f).toInt()
            setPadding(hp, vp, hp, vp)
            setOnClickListener { onCta() }
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(26f).toInt() }
        column.addView(button, btnParams)

        column.addView(
            label("THE SYSTEM IS WATCHING", 10f, Color.DKGRAY, 0.2f).apply {
                setPadding(0, dp(30f).toInt(), 0, 0)
            },
        )

        addView(
            column,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = dp(10f)
        val corner = dp(26f)
        // Solo Leveling window frame: thin border + corner brackets
        canvas.drawRect(inset, inset, width - inset, height - inset, framePaint)
        val p = framePaint
        // top-left
        canvas.drawLine(inset, inset + corner, inset, inset, p)
        canvas.drawLine(inset, inset, inset + corner, inset, p)
        // top-right
        canvas.drawLine(width - inset - corner, inset, width - inset, inset, p)
        canvas.drawLine(width - inset, inset, width - inset, inset + corner, p)
        // bottom-left
        canvas.drawLine(inset, height - inset - corner, inset, height - inset, p)
        canvas.drawLine(inset, height - inset, inset + corner, height - inset, p)
        // bottom-right
        canvas.drawLine(width - inset - corner, height - inset, width - inset, height - inset, p)
        canvas.drawLine(width - inset, height - inset - corner, width - inset, height - inset, p)
    }

    companion object {
        const val ACCENT_CYAN = 0xFF22D3EE.toInt()
        const val ACCENT_RED = 0xFFFF1744.toInt()

        /** §5 — quest-locked social media. */
        fun socialLock(context: Context, appLabel: String, onCta: () -> Unit) =
            SystemLockOverlayView(
                context = context,
                accent = ACCENT_CYAN,
                header = "[ ! ] ACCESS DENIED BY THE SYSTEM [ ! ]",
                body = "Your Daily Quests are incomplete. Weak hunters do not get entertainment. " +
                    "$appLabel stays sealed until today's training is done. " +
                    "Finish your quests to unlock social media.",
                cta = "COMPLETE DAILY QUESTS NOW",
                onCta = onCta,
            )

        /** §6 — adult content intervention. */
        fun pornViolation(context: Context, onCta: () -> Unit) =
            SystemLockOverlayView(
                context = context,
                accent = ACCENT_RED,
                header = "[ ! ] SYSTEM INTERVENTION [ ! ]",
                body = "Corrupted impulse detected. You swore to become stronger, not a slave to " +
                    "cheap urges. The System has sealed this page. Regain control immediately.",
                cta = "RETURN TO TRAINING",
                onCta = onCta,
            )
    }
}
