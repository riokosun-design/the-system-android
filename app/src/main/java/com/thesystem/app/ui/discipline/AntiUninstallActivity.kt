package com.thesystem.app.ui.discipline

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.thesystem.app.core.discipline.DisciplinePrefs
import com.thesystem.app.core.discipline.TypingGuard

/**
 * §7 — SYSTEM ABANDONMENT PROTOCOL.
 *
 * Launched by [com.thesystem.app.service.SystemDisciplineService] the moment
 * the hunter opens an uninstall / force-stop / permission screen for THE
 * SYSTEM. Escape is always ONE TAP away ("I AM NOT A COWARD"). Deletion
 * demands the full confession — typed perfectly, in one sitting, with no
 * paste, no autocorrect and no mercy.
 *
 * Anti-cheat law (spec §7.3):
 *  · Suggestions/autocorrect off (visible-password keyboard semantics).
 *  · Selection toolbar empty → no paste menu; >1-char edits rejected →
 *    clipboard dumps land dead even if an IME fires one.
 *  · >15s silence or leaving the screen → progress resets to 0%.
 *  · 100% accuracy → 60 seconds of amnesty, then the guard re-arms.
 */
class AntiUninstallActivity : ComponentActivity() {

    companion object {
        @Volatile var visible: Boolean = false
            private set

        fun launch(context: Context) {
            val i = Intent(context, AntiUninstallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent { AbandonmentProtocolScreen(::escapeToTraining, ::grantAmnesty) }
    }

    override fun onResume() { super.onResume(); visible = true }
    override fun onPause() { visible = false; super.onPause() }

    /** The honest exit: back to the dashboard, quitting forgotten. */
    private fun escapeToTraining() {
        runCatching {
            val i = packageManager.getLaunchIntentForPackage(packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (i != null) startActivity(i)
        }
        finishAndRemoveTask()
    }

    /** Perfect confession → 60s amnesty, then close so the hunter may act. */
    private fun grantAmnesty() {
        DisciplinePrefs.releaseUninstallGuard(this, TypingGuard.AMNESTY_MS)
        finishAndRemoveTask()
    }
}

private val ProtocolRed = Color(0xFFFF1744)
private val ProtocolCyan = Color(0xFF22D3EE)

/** Kills the selection toolbar — no copy, no paste, no select-all menu. */
private object EmptyTextToolbar : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hide
    override fun showMenu(
        rect: androidx.compose.ui.geometry.Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = Unit
    override fun hide() = Unit
}

@Composable
private fun AbandonmentProtocolScreen(onEscape: () -> Unit, onAmnestyGranted: () -> Unit) {
    val guard = remember { TypingGuard(TypingGuard.CONFESSION) }
    var version by remember { mutableIntStateOf(0) }      // recompose on every engine mutation
    var amnestyFired by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // no way back except the confession or the cyan oath
    BackHandler { /* the System does not negotiate */ }

    // the keyboard rises on its own — the ritual starts now
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(350)
        runCatching { focusRequester.requestFocus(); keyboard?.show() }
    }

    // idle watchdog: 15 seconds of silence and the ritual restarts
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            if (guard.expireIfIdle(System.currentTimeMillis())) version++
        }
    }

    // leaving the screen = silence
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { guard.reset(); version++ }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // a perfect confession ends the ritual: 60 seconds of amnesty
    LaunchedEffect(amnestyFired, version) {
        @Suppress("unused_expression") version
        if (!amnestyFired && guard.isComplete) {
            amnestyFired = true
            kotlinx.coroutines.delay(1_600)
            onAmnestyGranted()
        }
    }

    CompositionLocalProvider(LocalTextToolbar provides EmptyTextToolbar) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            @Suppress("unused_expression") version // recompose anchor
            Spacer(Modifier.height(18.dp))
            Text(
                "[ ! ] SYSTEM ABANDONMENT PROTOCOL [ ! ]",
                color = ProtocolRed, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "You are one tap from quitting on yourself. The System does not hold prisoners — " +
                    "but it demands honesty. Type the confession below, word for word, " +
                    "without a single mistake, and you are free for sixty seconds.",
                color = Color(0xFFB0B0B0), fontSize = 12.5.sp, lineHeight = 18.sp,
            )
            if (guard.resets > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "RITUAL RESET ×${guard.resets} — hesitation is expensive.",
                    color = ProtocolRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(16.dp))

            // the confession, live-judged character by character
            Box(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, ProtocolRed.copy(alpha = 0.6f), CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
                    .padding(14.dp),
            ) {
                Text(judgedConfession(guard), fontSize = 13.5.sp, lineHeight = 21.sp)
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ACCURACY ${(guard.progress01() * 100).toInt()}%",
                    color = ProtocolCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "IDLE KILLS PROGRESS · PASTE IS DEAD",
                    color = Color(0xFF555555), fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFF222222))) {
                Box(
                    Modifier
                        .fillMaxWidth(guard.progress01().coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(ProtocolCyan),
                )
            }
            Spacer(Modifier.height(14.dp))

            TextField(
                value = guard.typed,
                onValueChange = { new ->
                    // single-character edits only — pastes and bulk edits die here
                    val old = guard.typed
                    val delta = new.length - old.length
                    val surgical = kotlin.math.abs(delta) <= 1 && commonAffix(old, new)
                    if (surgical || new.isEmpty()) {
                        guard.onInput(new, System.currentTimeMillis())
                        version++
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .focusRequester(focusRequester),
                textStyle = TextStyle(color = Color.White, fontSize = 13.sp, lineHeight = 20.sp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,   // kills suggestions + voice typing on sane IMEs
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.None,
                ),
                visualTransformation = VisualTransformation.None,
                shape = RectangleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF0A0A0A),
                    unfocusedContainerColor = Color(0xFF0A0A0A),
                    focusedIndicatorColor = ProtocolRed,
                    unfocusedIndicatorColor = Color(0xFF333333),
                    cursorColor = ProtocolCyan,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                ),
                enabled = !amnestyFired,
            )

            if (amnestyFired) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "[ CONFESSION ACCEPTED — LOCK RELEASED FOR 60 SECONDS ]",
                    color = ProtocolCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Spacer(Modifier.height(22.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .border(1.5.dp, ProtocolCyan, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                    .background(ProtocolCyan.copy(alpha = 0.14f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onEscape,
                    )
                    .padding(vertical = 15.dp, horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "I AM NOT A COWARD — TAKE ME BACK TO MY TRAINING",
                    color = ProtocolCyan, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The target text, painted: verified prefix cyan, errors red, rest gray. */
private fun judgedConfession(guard: TypingGuard): AnnotatedString {
    val typed = guard.typed
    return buildAnnotatedString {
        guard.target.forEachIndexed { i, c ->
            val color = when {
                i >= typed.length -> Color(0xFF5A5A5A)
                typed[i] == c -> ProtocolCyan
                else -> ProtocolRed
            }
            pushStyle(SpanStyle(color = color))
            append(c)
            pop()
        }
    }
}

/** True when [next] is [prev] with at most one character inserted / deleted /
 *  replaced at a single position — the definition of an honest keystroke.
 *  Anything bigger arrived by paste, swipe or voice: dead on arrival. */
private fun commonAffix(prev: String, next: String): Boolean {
    if (next.isEmpty() || prev.isEmpty()) return true
    var head = 0
    while (head < minOf(prev.length, next.length) && prev[head] == next[head]) head++
    var tail = 0
    while (
        tail < minOf(prev.length - head, next.length - head) &&
        prev[prev.length - 1 - tail] == next[next.length - 1 - tail]
    ) tail++
    val prevMid = prev.length - head - tail
    val nextMid = next.length - head - tail
    return prevMid <= 1 && nextMid <= 1
}
