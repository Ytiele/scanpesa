package com.scanpesa.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.scanpesa.app.R
import com.scanpesa.app.parser.NumberToken
import com.scanpesa.app.parser.PhotoTokens
import com.scanpesa.app.parser.ScanHighlights
import com.scanpesa.app.ui.AppViewModel
import com.scanpesa.app.ui.BigButton
import com.scanpesa.app.ui.BigOutlinedButton
import com.scanpesa.app.ui.Green
import com.scanpesa.app.ui.ScreenTopBar
import com.scanpesa.app.ui.Space

/**
 * "Tap the number": the captured photo with every payment-like number boxed. The likely payment
 * is pre-selected; tapping any other box switches to it.
 */
@Composable
fun PickScreen(vm: AppViewModel, onContinue: () -> Unit, onRetake: () -> Unit, onManual: () -> Unit, onBack: () -> Unit) {
    val photo by vm.photo.collectAsState()
    val p = photo
    if (p == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val tokens = p.tokens
    var selected by remember(p) { mutableStateOf(PhotoTokens.defaultSelection(tokens, p.candidates)) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val bw = p.bitmap.width; val bh = p.bitmap.height
    val selectedCandidate = selected?.let { PhotoTokens.candidateFor(it, p.text) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenTopBar(
            if (tokens.isEmpty()) "No numbers found" else "Tap the number",
            onBack,
            subtitle = when {
                tokens.isEmpty() -> "Try again a little closer, or type it in"
                selected == null -> "Tap the box with the number you want to pay"
                else -> "Tap another box to change it"
            },
        )

        Box(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = Space.screen)
                .clip(MaterialTheme.shapes.large).background(Color.Black)
                .onSizeChanged { viewSize = it }
                .pointerInput(p, viewSize) {
                    detectTapGestures { pos ->
                        PhotoTokens.hit(
                            tokens, pos.x, pos.y, bw, bh, viewSize.width.toFloat(), viewSize.height.toFloat(),
                            slop = 16.dp.toPx(),
                        )?.let { selected = it }
                    }
                },
        ) {
            Image(p.bitmap.asImageBitmap(), "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val pad = 4.dp.toPx()
                val r = CornerRadius(8.dp.toPx())
                tokens.forEach { t ->
                    val b = PhotoTokens.mapFit(t.box, bw, bh, size.width, size.height)
                    val tl = Offset(b.left - pad, b.top - pad)
                    val sz = Size(b.right - b.left + 2 * pad, b.bottom - b.top + 2 * pad)
                    val isSel = selected?.let { s -> s == t || (s.candidate != null && s.candidate == t.candidate) } == true
                    when {
                        isSel -> {
                            drawRoundRect(Green.copy(alpha = 0.30f), tl, sz, r)
                            drawRoundRect(Green, tl, sz, r, style = Stroke(4.dp.toPx()))
                        }
                        t.candidate != null -> drawRoundRect(Green.copy(alpha = 0.9f), tl, sz, r, style = Stroke(2.dp.toPx()))
                        else -> drawRoundRect(Color.White.copy(alpha = 0.85f), tl, sz, r, style = Stroke(2.dp.toPx()))
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = Space.screen, end = Space.screen, top = Space.m, bottom = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text(
                selectedCandidate?.let { ScanHighlights.summary(listOf(it)) } ?: if (tokens.isEmpty()) " " else "Nothing selected",
                style = MaterialTheme.typography.titleLarge,
                color = if (selectedCandidate != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.xs))
            if (tokens.isEmpty()) {
                BigButton("Retake", onClick = onRetake)
                BigOutlinedButton("Type it in", onClick = onManual)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = onRetake,
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = Space.s),
                    ) {
                        Icon(painterResource(R.drawable.ic_scan), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(Space.xs))
                        Text("Retake", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
                    }
                    BigButton(
                        "Continue",
                        enabled = selectedCandidate != null,
                        onClick = { selectedCandidate?.let { vm.pickFromPhoto(it); onContinue() } },
                        modifier = Modifier.weight(1.3f).heightIn(min = 64.dp),
                    )
                }
            }
        }
    }
}
