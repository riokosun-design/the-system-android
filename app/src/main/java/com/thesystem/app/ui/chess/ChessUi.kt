package com.thesystem.app.ui.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thesystem.app.core.theme.FaintGray
import com.thesystem.app.core.theme.LabelGray
import com.thesystem.app.core.theme.PaperWhite
import com.thesystem.app.core.theme.SkyBlue
import com.thesystem.app.core.theme.SkyBlueDim
import com.thesystem.app.core.theme.TrackGray

/**
 * CHESS DESIGN SYSTEM (§3 premium rule) — one surface recipe for every card
 * in the Chess section: charcoal fill, 8% hairline, 14dp radius, icon chip.
 * Dark deck (true black) lives behind these; SkyBlue is the only accent.
 */
internal val ChessCardSurface = Color(0xFF121417)
internal val ChessCardSurfaceDisabled = Color(0xFF0B0C0E)
internal val ChessCardLine = Color(0x14FFFFFF)
internal val ChessCardShape = RoundedCornerShape(14.dp)

/** Vertical grid card — the seven home doors. Fixed height = one grid row,
 *  every door the same size; titles may wrap to two lines, nothing clips. */
@Composable
internal fun ChessGridCard(
    title: String,
    sub: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .height(148.dp)
            .clip(ChessCardShape)
            .background(if (enabled) ChessCardSurface else ChessCardSurfaceDisabled)
            .border(1.dp, ChessCardLine, ChessCardShape)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(
                    if (enabled) SkyBlueDim else TrackGray.copy(alpha = 0.35f),
                    RoundedCornerShape(11.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, null,
                tint = if (enabled) SkyBlue else FaintGray,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            color = if (enabled) PaperWhite else FaintGray,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            sub,
            color = if (enabled) LabelGray else FaintGray,
            fontSize = 11.sp, lineHeight = 15.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Horizontal list-row card — full-width doors, lesson rows, drill rows.
 *  Icon chip left, title + one status line center, affordance right.
 *  A sealed row stays tappable (it explains itself) but reads disabled. */
@Composable
internal fun ChessRowCard(
    title: String,
    sub: String?,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 76.dp,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(ChessCardShape)
            .background(if (enabled) ChessCardSurface else ChessCardSurfaceDisabled)
            .border(1.dp, ChessCardLine, ChessCardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .background(
                    if (enabled) SkyBlueDim else TrackGray.copy(alpha = 0.35f),
                    RoundedCornerShape(11.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, null,
                tint = if (enabled) SkyBlue else FaintGray,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) PaperWhite else FaintGray,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    sub,
                    color = if (enabled) LabelGray else FaintGray,
                    fontSize = 12.sp, lineHeight = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            if (enabled) Icons.Default.ChevronRight else Icons.Default.Lock,
            null,
            tint = FaintGray,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Non-interactive info block (settings/engine identity) — same hairline +
 *  surface, but prose instead of HUD labels: readable gray, normal case. */
@Composable
internal fun ChessInfoBlock(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(ChessCardShape)
            .background(ChessCardSurface)
            .border(1.dp, ChessCardLine, ChessCardShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = PaperWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(body, color = LabelGray, fontSize = 12.sp, lineHeight = 17.sp)
    }
}
