package io.orbe.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.orbe.watch.gesto.AMOSTRAS
import io.orbe.watch.gesto.ANTES
import io.orbe.watch.gesto.Janela
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * O perfil das tentativas de uma calibração de batida, em círculo: o tempo da
 * janela corre no sentido do relógio a partir do alto (o pico na marca
 * tracejada) e a magnitude da forma afasta a curva do anel base. A aceleração
 * no anel de fora, o giro no de dentro; cada tentativa em traço fino, a média
 * em destaque e a última em claro. No arco de baixo, a força de cada tentativa
 * numa régua logarítmica. O meio fica livre para a lente da Moldura.
 */
@Composable
fun GraficoPerfil(janelas: List<Janela>, modifier: Modifier = Modifier) {
    val accent = Estilo.accent
    val texto = Estilo.texto
    val curvas = remember(janelas.size, janelas.lastOrNull()) {
        Curvas(janelas.map { magnitudes(it, 0) }, janelas.map { magnitudes(it, 1) })
    }
    val forcas = janelas.map { it.aceleracao + it.giro }
    Canvas(modifier.aspectRatio(1f)) {
        val raio = min(size.width, size.height) / 2
        // anéis: o de fora (aceleração) de 0,62 a 0,86 do raio, o de dentro (giro) de 0,40 a 0,58
        anel(curvas.acel, raio * 0.62f, raio * 0.24f, accent, texto)
        anel(curvas.giro, raio * 0.40f, raio * 0.18f, accent, texto)
        pico(raio * 0.38f, raio * 0.9f, texto)
        regua(forcas, raio * 0.95f, accent, texto)
    }
}

private class Curvas(val acel: List<FloatArray>, val giro: List<FloatArray>)

/** A magnitude de um sensor (0 aceleração, 1 giro) em cada amostra da forma. */
private fun magnitudes(j: Janela, sensor: Int): FloatArray = FloatArray(AMOSTRAS) { t ->
    var s = 0f
    for (c in 0 until 3) {
        val v = j.forma[(sensor * 3 + c) * AMOSTRAS + t]
        s += v * v
    }
    sqrt(s)
}

/** O ângulo da amostra [t]: do alto, no sentido do relógio, a volta inteira na janela. */
private fun angulo(t: Float) = (-PI / 2 + 2 * PI * t / AMOSTRAS).toFloat()

private fun DrawScope.ponto(r: Float, a: Float) = Offset(center.x + r * cos(a), center.y + r * sin(a))

/** Um anel: a base, a grade e as curvas, de [base] até [base] + [altura] no máximo. */
private fun DrawScope.anel(curvas: List<FloatArray>, base: Float, altura: Float, accent: Color, texto: Color) {
    drawCircle(texto.alfa(0.12f), base, center, style = Stroke(1f))
    drawCircle(texto.alfa(0.05f), base + altura, center, style = Stroke(1f))
    for (t in 0 until AMOSTRAS step 5) {
        val a = angulo(t.toFloat())
        drawLine(texto.alfa(0.1f), ponto(base - 3.dp.toPx(), a), ponto(base, a), 1f)
    }
    if (curvas.isEmpty()) return
    val topo = curvas.maxOf { c -> c.max() }.coerceAtLeast(1e-4f)
    for (c in curvas.dropLast(1)) curva(c, base, altura / topo, accent.alfa(0.28f), 1.dp.toPx())
    val media = FloatArray(AMOSTRAS) { t -> curvas.sumOf { it[t].toDouble() }.toFloat() / curvas.size }
    // a média com brilho: um traço largo e fraco por baixo do fino
    curva(media, base, altura / topo, accent.alfa(0.25f), 5.dp.toPx())
    curva(media, base, altura / topo, accent, 1.8.dp.toPx())
    curva(curvas.last(), base, altura / topo, texto.alfa(0.85f), 1.dp.toPx())
}

/** A curva fechada de uma tentativa em volta do anel. */
private fun DrawScope.curva(c: FloatArray, base: Float, escala: Float, cor: Color, largura: Float) {
    val p = Path()
    for (t in 0..c.size) {
        val i = t % c.size
        val o = ponto(base + c[i] * escala, angulo(t.toFloat()))
        if (t == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y)
    }
    drawPath(p, cor, style = Stroke(largura, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A marca tracejada do pico, do anel de dentro ao de fora. */
private fun DrawScope.pico(de: Float, ate: Float, texto: Color) {
    val a = angulo(ANTES.toFloat())
    drawLine(
        texto.alfa(0.3f), ponto(de, a), ponto(ate, a), 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
    )
}

/** A força de cada tentativa (pico da aceleração + do giro) num arco embaixo, em escala logarítmica. */
private fun DrawScope.regua(forcas: List<Float>, r: Float, accent: Color, texto: Color) {
    // o arco de baixo: de 135° a 45° passando por 90° (o pé do círculo), da esquerda para a direita
    val ini = (PI * 0.75).toFloat()
    val fim = (PI * 0.25).toFloat()
    val passos = 24
    val p = Path()
    for (k in 0..passos) {
        val o = ponto(r, ini + (fim - ini) * k / passos)
        if (k == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y)
    }
    drawPath(p, texto.alfa(0.15f), style = Stroke(1f))
    if (forcas.isEmpty()) return
    val lo = ln(max(forcas.min(), 1e-3f) / 1.5f)
    val hi = ln(max(forcas.max(), 1e-3f) * 1.5f)
    forcas.forEachIndexed { i, f ->
        val u = (ln(max(f, 1e-3f)) - lo) / max(hi - lo, 1e-3f)
        val ultima = i == forcas.lastIndex
        drawCircle(
            if (ultima) texto else accent.alfa(0.7f),
            if (ultima) 3.dp.toPx() else 2.dp.toPx(),
            ponto(r, ini + (fim - ini) * u),
        )
    }
}
