package io.orbe.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlin.math.min

/*
 * Liquid glass sem desfoque: o relógio é Android 11 (sem RenderEffect), então o
 * vidro é desenhado: sombra embaixo, corpo translúcido com um tom do accent, um
 * brilho especular no alto e a borda clara em cima e escura embaixo, como luz
 * vindo de cima. Tudo em drawWithCache: as escovas só refazem quando o tamanho muda.
 */

/** O vidro líquido atrás do conteúdo, na [forma]; [aceso] o tinge com o accent (o botão principal). */
@Composable
fun Modifier.vidro(forma: Shape = CircleShape, aceso: Boolean = false, apertado: Boolean = false): Modifier {
    val accent = Estilo.accent
    val texto = Estilo.texto
    return drawWithCache {
        val contorno = forma.createOutline(size, layoutDirection, this)
        val caminho = Path().apply { addOutline(contorno) }
        val sombra = 2.dp.toPx()
        val corpo = Brush.verticalGradient(
            if (aceso) listOf(accent.alfa(if (apertado) 0.75f else 0.62f), accent.alfa(if (apertado) 0.55f else 0.40f))
            else listOf(texto.alfa(if (apertado) 0.20f else 0.13f), texto.alfa(if (apertado) 0.08f else 0.03f)),
        )
        val tinta = accent.alfa(if (aceso) 0f else 0.07f)
        val brilho = Brush.verticalGradient(
            0f to Color.White.alfa(if (aceso) 0.32f else 0.22f), 0.45f to Color.Transparent,
            startY = 0f, endY = size.height,
        )
        val borda = Brush.linearGradient(
            0f to Color.White.alfa(0.60f), 0.45f to Color.White.alfa(0.06f), 1f to Color.White.alfa(0.22f),
            start = Offset(0f, 0f), end = Offset(size.width, size.height),
        )
        onDrawWithContent {
            translate(top = sombra) { drawOutline(contorno, Color.Black.alfa(0.28f)) }
            drawOutline(contorno, corpo)
            drawOutline(contorno, tinta)
            clipPath(caminho) { drawRect(brilho, size = Size(size.width, size.height * 0.5f)) }
            drawOutline(contorno, borda, style = Stroke(1.dp.toPx()))
            drawContent()
        }
    }.clip(forma)
}

/** Um botão de vidro em pílula. */
@Composable
fun BotaoVidro(texto: String, modifier: Modifier = Modifier, destaque: Boolean = false, aoClicar: () -> Unit) {
    val fonte = remember { MutableInteractionSource() }
    val apertado by fonte.collectIsPressedAsState()
    Box(
        modifier
            .height(32.dp)
            .vidro(CircleShape, aceso = destaque, apertado = apertado)
            .clickable(fonte, indication = null, onClick = aoClicar)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Texto(texto, Estilo.botao, cor = if (destaque) Estilo.accentFg else Estilo.texto, linhas = 1)
    }
}

/**
 * O anel de progresso na borda da tela redonda: um trilho de vidro e [meta]
 * gomos, os [feitos] acesos no accent com brilho, começando no alto.
 */
@Composable
fun AnelProgresso(feitos: Int, meta: Int, modifier: Modifier = Modifier) {
    val accent = Estilo.accent
    val texto = Estilo.texto
    Canvas(modifier) {
        val largura = 7.dp.toPx()
        val raio = min(size.width, size.height) / 2 - largura
        val topo = Offset(center.x - raio, center.y - raio)
        val lado = Size(raio * 2, raio * 2)
        // o trilho: corpo translúcido e as duas bordas, a de fora mais clara
        drawCircle(texto.alfa(0.06f), raio, center, style = Stroke(largura))
        drawCircle(Color.White.alfa(0.20f), raio + largura / 2, center, style = Stroke(1f))
        drawCircle(Color.White.alfa(0.07f), raio - largura / 2, center, style = Stroke(1f))
        if (meta <= 0) return@Canvas
        val passo = 360f / meta
        val vao = if (meta > 1) min(3f, passo * 0.25f) else 0f
        for (k in 0 until meta) {
            val ini = -90f + k * passo + vao / 2
            val aceso = k < feitos
            if (aceso) drawArc(accent.alfa(0.25f), ini, passo - vao, false, topo, lado, style = Stroke(largura * 1.8f, cap = StrokeCap.Butt))
            drawArc(
                if (aceso) accent else texto.alfa(0.10f), ini, passo - vao, false, topo, lado,
                style = Stroke(if (aceso) largura * 0.7f else largura * 0.35f, cap = StrokeCap.Butt),
            )
        }
    }
}
