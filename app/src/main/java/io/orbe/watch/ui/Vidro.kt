package io.orbe.watch.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/*
 * Vidro líquido, de leve. O relógio é Android 11 (sem RenderEffect, sem desfoque
 * do que está atrás), então o volume vem da luz: o corpo quase transparente, um
 * brilho especular fino na borda de cima, a borda de baixo um pouco mais escura
 * por dentro (a espessura do vidro) e um reflexo largo e fraco no alto. No
 * toque, a peça afunda um pouco com mola, como no iOS.
 */

/**
 * O vidro atrás do conteúdo, na [forma]. [tinta] tinge o corpo (o botão
 * principal leva o accent); [apertado] afunda e acende um pouco.
 */
@Composable
fun Modifier.vidro(forma: Shape, tinta: Color? = null, apertado: Boolean = false): Modifier {
    val escala by animateFloatAsState(
        if (apertado) 0.96f else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "vidro",
    )
    val luz by animateFloatAsState(if (apertado) 1f else 0f, spring(stiffness = Spring.StiffnessMedium), label = "luz")
    return graphicsLayer { scaleX = escala; scaleY = escala }
        .drawWithCache {
            val contorno = forma.createOutline(size, layoutDirection, this)
            val caminho = Path().apply { addOutline(contorno) }
            val fio = 0.8.dp.toPx()
            val base = tinta
            val corpo = if (base != null) {
                Brush.verticalGradient(listOf(base.alfa(0.62f + 0.12f * luz), base.alfa(0.42f + 0.12f * luz)))
            } else {
                Brush.verticalGradient(listOf(Color.White.alfa(0.085f + 0.05f * luz), Color.White.alfa(0.025f + 0.04f * luz)))
            }
            // o reflexo largo do alto, cortado na metade de cima
            val reflexo = Brush.verticalGradient(
                0f to Color.White.alfa(if (base != null) 0.20f else 0.07f), 0.5f to Color.Transparent,
            )
            // a espessura: escurece por dentro junto à borda de baixo
            val fundo = Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.alfa(0.22f))
            // o fio da borda: aceso em cima, apagado no meio, um resto embaixo
            val borda = Brush.verticalGradient(
                0f to Color.White.alfa(0.42f), 0.35f to Color.White.alfa(0.08f),
                0.8f to Color.White.alfa(0.03f), 1f to Color.White.alfa(0.12f),
            )
            onDrawWithContent {
                drawOutline(contorno, corpo)
                clipPath(caminho) {
                    drawRect(reflexo)
                    drawRect(fundo)
                }
                drawOutline(contorno, borda, style = Stroke(fio))
                drawContent()
            }
        }
        .clip(forma)
}
