package io.orbe.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import io.orbe.watch.gesto.Perfil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * O perfil das tentativas de uma calibração de batida: a curva de cada uma (a
 * magnitude da forma, amostra a amostra, alinhada no pico) em traço fino, a
 * média em destaque e a última em claro, num painel para a aceleração e outro
 * para o giro; embaixo, a força de cada tentativa numa régua logarítmica.
 */
@Composable
fun GraficoPerfil(janelas: List<Janela>) {
    val accent = Estilo.accent
    val texto = Estilo.texto
    val curvas = remember(janelas.size, janelas.lastOrNull()) {
        Curvas(janelas.map { magnitudes(it, 0) }, janelas.map { magnitudes(it, 1) })
    }
    val perfil = remember(janelas.size, janelas.lastOrNull()) { Perfil.de(janelas) }
    Column(Modifier.fillMaxWidth().caixa().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Texto("PERFIL · ${janelas.size}", Estilo.mono, Modifier.weight(1f), cor = accent)
            Texto(
                perfil?.let { "limiar ${um2(it.limiar)}" } ?: "mín. 3",
                Estilo.mono, cor = texto.alfa(0.55f),
            )
        }
        Painel("ACEL", curvas.acel, accent, texto)
        Painel("GIRO", curvas.giro, accent, texto)
        Regua(janelas, accent, texto)
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

@Composable
private fun Painel(nome: String, curvas: List<FloatArray>, accent: Color, texto: Color) {
    Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            grade(texto)
            if (curvas.isEmpty()) return@Canvas
            val topo = curvas.maxOf { c -> c.max() }.coerceAtLeast(1e-4f) * 1.1f
            for (c in curvas.dropLast(1)) curva(c, topo, accent.alfa(0.28f), 1.dp.toPx())
            val media = FloatArray(AMOSTRAS) { t -> curvas.sumOf { it[t].toDouble() }.toFloat() / curvas.size }
            // a média com brilho: um traço largo e fraco por baixo do fino
            curva(media, topo, accent.alfa(0.25f), 5.dp.toPx())
            curva(media, topo, accent, 1.8.dp.toPx())
            curva(curvas.last(), topo, texto.alfa(0.85f), 1.dp.toPx())
        }
        Texto(nome, Estilo.mono, Modifier.padding(start = 3.dp), cor = texto.alfa(0.5f))
    }
}

/** A grade do painel e a marca tracejada do pico. */
private fun DrawScope.grade(texto: Color) {
    val linha = texto.alfa(0.07f)
    for (k in 0..3) {
        val y = size.height * k / 3f
        drawLine(linha, Offset(0f, y), Offset(size.width, y), 1f)
    }
    for (t in 0 until AMOSTRAS step 5) {
        val x = size.width * t / (AMOSTRAS - 1f)
        drawLine(linha, Offset(x, size.height - 4.dp.toPx()), Offset(x, size.height), 1f)
    }
    val xp = size.width * ANTES / (AMOSTRAS - 1f)
    drawLine(
        texto.alfa(0.3f), Offset(xp, 0f), Offset(xp, size.height), 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
    )
}

private fun DrawScope.curva(c: FloatArray, topo: Float, cor: Color, largura: Float) {
    val p = Path()
    for (t in c.indices) {
        val x = size.width * t / (AMOSTRAS - 1f)
        val y = size.height * (1f - c[t] / topo)
        if (t == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    drawPath(p, cor, style = Stroke(largura, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A força de cada tentativa (pico da aceleração + do giro) numa régua logarítmica. */
@Composable
private fun Regua(janelas: List<Janela>, accent: Color, texto: Color) {
    val forcas = janelas.map { it.aceleracao + it.giro }
    Box(Modifier.fillMaxWidth().padding(top = 5.dp)) {
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val meio = size.height / 2
            drawLine(texto.alfa(0.15f), Offset(0f, meio), Offset(size.width, meio), 1f)
            if (forcas.isEmpty()) return@Canvas
            val lo = ln(max(forcas.min(), 1e-3f) / 1.5f)
            val hi = ln(max(forcas.max(), 1e-3f) * 1.5f)
            fun x(f: Float) = size.width * ((ln(max(f, 1e-3f)) - lo) / max(hi - lo, 1e-3f))
            forcas.forEachIndexed { i, f ->
                val ultima = i == forcas.lastIndex
                drawCircle(if (ultima) texto else accent.alfa(0.7f), if (ultima) 3.dp.toPx() else 2.dp.toPx(), Offset(x(f), meio))
            }
        }
    }
    if (forcas.isNotEmpty()) {
        Texto(
            "força ${um1(forcas.min())} a ${um1(forcas.max())}",
            Estilo.mono, Modifier.fillMaxWidth().padding(top = 2.dp), cor = texto.alfa(0.55f),
        )
    }
}

private fun um1(v: Float) = "%.1f".format(v).replace('.', ',')
private fun um2(v: Float) = "%.2f".format(v).replace('.', ',')
