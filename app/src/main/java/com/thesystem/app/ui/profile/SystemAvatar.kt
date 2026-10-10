package com.thesystem.app.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.thesystem.app.core.theme.SkyBlue

/** The four built-in presets ride users.avatar_url as system:// keys (mig 029). */
val SYSTEM_AVATARS = listOf(
    "system://monolith", // Shadow Monarch Monolith — deep violet / dark obsidian
    "system://aegis",    // Iron Knight Aegis — steel blue / neon cyan
    "system://glitch",   // System Core glitch runes — electric blue
    "system://berserk",  // Berserker Flame — crimson / charcoal
)

/**
 * System avatar — pure-Canvas geometric presets (zero asset weight, low-end
 * safe) or a custom storage photo via Coil. NULL url → initial-letter die.
 */
@Composable
fun SystemAvatar(avatarUrl: String?, username: String, modifier: Modifier = Modifier) {
    if (avatarUrl != null && avatarUrl.startsWith("http")) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = "avatar",
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
        return
    }
    Box(
        modifier
            .clip(CircleShape)
            .background(Color(0xFF0D1013)),
        contentAlignment = Alignment.Center,
    ) {
        when (avatarUrl) {
            "system://monolith" -> Canvas(Modifier.fillMaxSize()) { drawMonolith() }
            "system://aegis" -> Canvas(Modifier.fillMaxSize()) { drawAegis() }
            "system://glitch" -> Canvas(Modifier.fillMaxSize()) { drawGlitch() }
            "system://berserk" -> Canvas(Modifier.fillMaxSize()) { drawBerserk() }
            else -> Text(
                (username.firstOrNull() ?: 'H').uppercaseChar().toString(),
                color = SkyBlue, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** AVATAR 1 — Shadow Monarch Monolith: violet pillar rising out of obsidian. */
private fun DrawScope.drawMonolith() {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF0B0714))
    // halo behind the crown
    drawCircle(Color(0xFF7C3AED).copy(alpha = 0.35f), radius = w * 0.34f, center = Offset(w * 0.5f, h * 0.30f))
    // tapered monolith body
    val p = Path().apply {
        moveTo(w * 0.40f, h * 0.16f)
        lineTo(w * 0.60f, h * 0.16f)
        lineTo(w * 0.74f, h * 0.92f)
        lineTo(w * 0.26f, h * 0.92f)
        close()
    }
    drawPath(p, Color(0xFF7C3AED))
    // inner core slit
    drawRect(
        Color(0xFFA78BFA),
        topLeft = Offset(w * 0.47f, h * 0.30f),
        size = androidx.compose.ui.geometry.Size(w * 0.06f, h * 0.44f),
    )
    // base shadow
    drawOval(Color(0xFF05030A), topLeft = Offset(w * 0.18f, h * 0.86f), size = androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.10f))
}

/** AVATAR 2 — Iron Knight Aegis: steel shield with a neon-cyan core. */
private fun DrawScope.drawAegis() {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF0A1220))
    val shield = Path().apply {
        moveTo(w * 0.5f, h * 0.12f)
        lineTo(w * 0.82f, h * 0.26f)
        lineTo(w * 0.78f, h * 0.60f)
        quadraticTo(w * 0.74f, h * 0.80f, w * 0.5f, h * 0.90f)
        quadraticTo(w * 0.26f, h * 0.80f, w * 0.22f, h * 0.60f)
        lineTo(w * 0.18f, h * 0.26f)
        close()
    }
    drawPath(shield, Color(0xFF12263D))
    drawPath(shield, SkyBlue, style = Stroke(width = w * 0.045f))
    // visor slit + power core
    drawRect(Color(0xFF0A1220), topLeft = Offset(w * 0.32f, h * 0.38f), size = androidx.compose.ui.geometry.Size(w * 0.36f, h * 0.07f))
    drawCircle(SkyBlue, radius = w * 0.09f, center = Offset(w * 0.5f, h * 0.60f))
    drawCircle(Color(0xFFE0F2FE), radius = w * 0.035f, center = Offset(w * 0.5f, h * 0.60f))
}

/** AVATAR 3 — System Core glitch runes: overlapping electric-blue signals. */
private fun DrawScope.drawGlitch() {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF050A12))
    // scanlines
    listOf(0.18f, 0.50f, 0.82f).forEach { y ->
        drawRect(
            SkyBlue.copy(alpha = 0.45f),
            topLeft = Offset(0f, h * y),
            size = androidx.compose.ui.geometry.Size(w, h * 0.025f),
        )
    }
    // stacked rune squares with ghost offsets
    fun sq(cx: Float, cy: Float, r: Float, c: Color) =
        drawRect(c, topLeft = Offset(w * (cx - r), h * (cy - r)), size = androidx.compose.ui.geometry.Size(w * r * 2, h * r * 2))
    sq(0.44f, 0.44f, 0.20f, Color(0xFF2563EB).copy(alpha = 0.85f))
    sq(0.56f, 0.56f, 0.20f, SkyBlue.copy(alpha = 0.65f))
    sq(0.50f, 0.50f, 0.10f, Color(0xFF050A12))
    sq(0.50f, 0.50f, 0.05f, Color(0xFF93C5FD))
}

/** AVATAR 4 — Berserker Flame: crimson tear over charcoal. */
private fun DrawScope.drawBerserk() {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF140606))
    val flame = Path().apply {
        moveTo(w * 0.50f, h * 0.10f)
        cubicTo(w * 0.68f, h * 0.34f, w * 0.82f, h * 0.40f, w * 0.76f, h * 0.64f)
        cubicTo(w * 0.72f, h * 0.82f, w * 0.62f, h * 0.92f, w * 0.50f, h * 0.92f)
        cubicTo(w * 0.38f, h * 0.92f, w * 0.28f, h * 0.82f, w * 0.24f, h * 0.64f)
        cubicTo(w * 0.20f, h * 0.42f, w * 0.38f, h * 0.34f, w * 0.50f, h * 0.10f)
        close()
    }
    drawPath(flame, Color(0xFFEF4444))
    val inner = Path().apply {
        moveTo(w * 0.50f, h * 0.40f)
        cubicTo(w * 0.62f, h * 0.54f, w * 0.66f, h * 0.62f, w * 0.62f, h * 0.76f)
        cubicTo(w * 0.58f, h * 0.86f, w * 0.54f, h * 0.90f, w * 0.50f, h * 0.90f)
        cubicTo(w * 0.46f, h * 0.90f, w * 0.42f, h * 0.86f, w * 0.38f, h * 0.76f)
        cubicTo(w * 0.34f, h * 0.62f, w * 0.42f, h * 0.56f, w * 0.50f, h * 0.40f)
        close()
    }
    drawPath(inner, Color(0xFFFCA5A5))
}
