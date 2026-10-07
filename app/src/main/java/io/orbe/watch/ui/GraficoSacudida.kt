package io.orbe.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Pontos de cada traço da sacudida no gráfico: ~1,2 s em volta do pico. */
const val PONTOS_TRACO = 48
private const val ANTES_MS = 600L
private const val DEPOIS_MS = 600L

/**
 * O giro do pulso (ωx, ao longo do antebraço) dos últimos instantes, para
 * recortar o traço de cada tentativa da calibração da sacudida.
 */
class TracoGiro {
    private val ms = LongArray(N)
    private val x = FloatArray(N)
    private var n = 0

    fun ler(agoraMs: Long, omegaX: Float) {
        val k = n % N
        ms[k] = agoraMs
        x[k] = omegaX
        n++
    }

    /**
     * O traço em volta do maior pico recente, reamostrado em [PONTOS_TRACO] e
     * virado para o primeiro pico forte ser positivo: "para fora" sempre para
     * fora no gráfico, qualquer que seja o pulso e a postura.
     */
    fun recorte(): FloatArray? {
        val tem = minOf(n, N)
        if (tem < 8) return null
        val ini = n - tem
        var pico = ini
        for (i in ini until n) if (abs(x[i % N]) > abs(x[pico % N])) pico = i
        val t0 = ms[pico % N] - ANTES_MS
        val passo = (ANTES_MS + DEPOIS_MS).toFloat() / (PONTOS_TRACO - 1)
        val out = FloatArray(PONTOS_TRACO)
        var j = ini
        for (p in 0 until PONTOS_TRACO) {
            val t = t0 + (p * passo).toLong()
            while (j < n - 1 && ms[(j + 1) % N] <= t) j++
            val a = j % N
            val b = minOf(j + 1, n - 1) % N
            val dt = (ms[b] - ms[a]).coerceAtLeast(1)
            val u = ((t - ms[a]).toFloat() / dt).coerceIn(0f, 1f)
            out[p] = if (t < ms[ini % N]) 0f else x[a] + (x[b] - x[a]) * u
        }
        // o sentido: o do primeiro trecho que passa de 30% do pico
        val lim = abs(x[pico % N]) * 0.3f
        val primeiro = out.firstOrNull { abs(it) >= lim } ?: 0f
        if (primeiro < 0) for (p in out.indices) out[p] = -out[p]
        return out
    }

    fun zerar() { n = 0 }

    private companion object { const val N = 256 }
}

/**
 * Os traços das tentativas da sacudida em círculo, como o perfil das batidas:
 * o tempo corre no sentido do relógio a partir do alto, o pico na marca
 * tracejada; para fora afasta a curva do anel base, para dentro a aproxima.
 * Cada tentativa em traço fino, a média em destaque e a última em claro; os
 * limiares (fora e, no abrir, dentro) em círculos tracejados.
 */
@Composable
fun GraficoSacudida(tracos: List<FloatArray>, limFora: Float, limDentro: Float?, rotulo: String, modifier: Modifier = Modifier) {
    val accent = Estilo.accent
    val texto = Estilo.texto
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val raio = min(size.width, size.height) / 2
            val base = raio * 0.6f
            val topoValor = maxOf(
                tracos.maxOfOrNull { t -> t.maxOf { abs(it) } } ?: 0f,
                limFora, limDentro ?: 0f, 1f,
            )
            val esc = raio * 0.34f / topoValor
            drawCircle(texto.alfa(0.12f), base, center, style = Stroke(1f))
            val tracejado = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            drawCircle(texto.alfa(0.28f), base + limFora * esc, center, style = Stroke(1f, pathEffect = tracejado))
            limDentro?.let { drawCircle(texto.alfa(0.28f), base - it * esc, center, style = Stroke(1f, pathEffect = tracejado)) }
            val a = angulo(PONTOS_TRACO * ANTES_MS.toFloat() / (ANTES_MS + DEPOIS_MS))
            drawLine(texto.alfa(0.3f), ponto(base - raio * 0.36f, a), ponto(base + raio * 0.36f, a), 1f, pathEffect = tracejado)
            if (tracos.isEmpty()) return@Canvas
            for (t in tracos.dropLast(1)) curva(t, base, esc, accent.alfa(0.28f), 1.dp.toPx())
            val media = FloatArray(PONTOS_TRACO) { p -> tracos.sumOf { it[p].toDouble() }.toFloat() / tracos.size }
            curva(media, base, esc, accent.alfa(0.25f), 5.dp.toPx())
            curva(media, base, esc, accent, 1.8.dp.toPx())
            curva(tracos.last(), base, esc, texto.alfa(0.85f), 1.dp.toPx())
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Texto("SACUDIDA", Estilo.mono, cor = accent)
            Texto("${tracos.size}", Estilo.grupo, cor = texto)
            Texto(rotulo, Estilo.mono, cor = texto.alfa(0.55f), alinhar = TextAlign.Center)
        }
    }
}

private fun angulo(p: Float) = (-PI / 2 + 2 * PI * p / PONTOS_TRACO).toFloat()

private fun DrawScope.ponto(r: Float, a: Float) = Offset(center.x + r * cos(a), center.y + r * sin(a))

/** O traço aberto (a janela não dá a volta: o fim não emenda no começo). */
private fun DrawScope.curva(c: FloatArray, base: Float, esc: Float, cor: Color, largura: Float) {
    val p = Path()
    for (i in c.indices) {
        val o = ponto(base + c[i] * esc, angulo(i.toFloat()))
        if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y)
    }
    drawPath(p, cor, style = Stroke(largura, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
