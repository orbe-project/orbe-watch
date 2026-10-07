package io.orbe.watch.gesto

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * O dedo na tela e a própria vibração balançam o relógio como uma batida:
 * marcam até quando (ms desde o boot, a base dos sensores) o detector fica surdo.
 */
object TelaTocada {
    @Volatile var surdoAte = 0L
    /** o último candidato a batida (ms desde o boot): o estalo gira o pulso e a sacudida de sair não pode valer nele */
    @Volatile var ultimoTrancoMs = 0L
}

/** A força de uma batida: o toque fraco (dedo médio no dedão, ou a ponta dos dedos na mesa) ou o estalo. */
enum class Forca { FRACA, FORTE }

/** O que uma ou duas batidas seguidas da mesma força deram. */
data class Comando(val forca: Forca, val vezes: Int)

const val AMOSTRAS = 35                     // ~350 ms a 100 Hz
private const val ANTES = 8                 // amostras antes do pico na janela
const val DIMENSAO = AMOSTRAS * 6

/**
 * A janela de um gesto: [AMOSTRAS] leituras alinhadas no pico, a aceleração e o
 * giro sem a parte lenta (3 eixos cada). [forma] é isso normalizado (cada
 * sensor com o mesmo peso, comprimento 1): o perfil do movimento; [aceleracao]
 * e [giro] são os picos (a força).
 */
class Janela(val forma: FloatArray, val aceleracao: Float, val giro: Float) {
    fun texto(): String = buildString {
        append("%.3f".format(java.util.Locale.ROOT, aceleracao)).append(' ').append("%.3f".format(java.util.Locale.ROOT, giro))
        for (v in forma) append(' ').append("%.4f".format(java.util.Locale.ROOT, v))
    }

    companion object {
        fun de(t: String): Janela? {
            val p = t.trim().split(' ').mapNotNull { it.toFloatOrNull() }
            if (p.size != 2 + DIMENSAO) return null
            return Janela(p.subList(2, p.size).toFloatArray(), p[0], p[1])
        }

        fun lista(t: String): List<Janela> = t.split('|').mapNotNull { if (it.isBlank()) null else de(it) }
        fun texto(l: List<Janela>): String = l.joinToString("|") { it.texto() }
    }
}

private fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

/**
 * O perfil aprendido de uma força: a forma média das tentativas, o limiar de
 * semelhança (a menos parecida delas com a média, com folga) e a faixa da força
 * (média e desvio do logaritmo dos picos).
 */
class Perfil(val forma: FloatArray, val limiar: Float, val mA: Float, val sA: Float, val mG: Float, val sG: Float) {
    fun parecenca(j: Janela) = dot(j.forma, forma)

    /** a força da janela na faixa das tentativas (com folga para a calibração ser curta) */
    fun naFaixa(j: Janela): Boolean {
        val zA = (ln(max(j.aceleracao, 1e-3f)) - mA) / max(sA, 0.25f)
        val zG = (ln(max(j.giro, 1e-3f)) - mG) / max(sG, 0.35f)
        return abs(zA) <= 3f && abs(zG) <= 3.5f
    }

    companion object {
        fun de(tentativas: List<Janela>): Perfil? {
            if (tentativas.size < 3) return null
            val m = FloatArray(DIMENSAO)
            for (t in tentativas) for (i in m.indices) m[i] += t.forma[i]
            val n = sqrt(dot(m, m)).coerceAtLeast(1e-6f)
            for (i in m.indices) m[i] /= n
            val limiar = (tentativas.minOf { dot(it.forma, m) } - 0.08f).coerceIn(0.35f, 0.9f)
            val la = tentativas.map { ln(max(it.aceleracao, 1e-3f)) }
            val lg = tentativas.map { ln(max(it.giro, 1e-3f)) }
            return Perfil(m, limiar, media(la), desvio(la), media(lg), desvio(lg))
        }

        private fun media(l: List<Float>) = l.sum() / l.size
        private fun desvio(l: List<Float>): Float {
            val mm = media(l)
            return sqrt(l.sumOf { ((it - mm) * (it - mm)).toDouble() }.toFloat() / l.size)
        }
    }
}

/**
 * O que o relógio aprendeu dos gestos: o perfil do toque fraco, o do estalo e
 * exemplos do que não é batida (digitar, tocar a tela, mexer o braço).
 */
class ModeloBatida(val fraca: Perfil?, val forte: Perfil?, val nada: List<Janela>, val gatilho: Float) {

    /** A força do gesto, ou null (não é batida); o texto diz por quê. */
    fun classificar(j: Janela): Pair<Forca?, String> {
        val candidatos = listOfNotNull(fraca?.let { Forca.FRACA to it }, forte?.let { Forca.FORTE to it })
        if (candidatos.isEmpty()) return null to "sem calibração"
        val (f, p, s) = candidatos.map { (f, p) -> Triple(f, p, p.parecenca(j)) }.maxBy { it.third }
        val ruido = nada.maxOfOrNull { dot(it.forma, j.forma) } ?: -1f
        return when {
            s < p.limiar -> null to "forma %.2f abaixo de %.2f (%s)".format(s, p.limiar, f)
            !p.naFaixa(j) -> null to "força fora da faixa do %s".format(f)
            ruido >= s -> null to "mais parecido com o que não é batida (%.2f contra %.2f)".format(ruido, s)
            else -> f to "forma %.2f: %s".format(s, f)
        }
    }

    val calibrado get() = fraca != null || forte != null

    companion object {
        const val GATILHO_PADRAO = 0.6f
        fun de(fracas: List<Janela>, fortes: List<Janela>, nada: List<Janela>): ModeloBatida {
            val forcas = (fracas + fortes).map { it.aceleracao + it.giro }
            val gatilho = if (forcas.isEmpty()) GATILHO_PADRAO else (forcas.min() * 0.7f).coerceIn(0.3f, 3f)
            return ModeloBatida(Perfil.de(fracas), Perfil.de(fortes), nada, gatilho)
        }
    }
}

/**
 * Batidas no pulso pelo perfil do movimento: um candidato é um pulso no
 * acelerômetro ou no giroscópio (sem a parte lenta: gravidade e gesto do braço)
 * com o pulso quieto antes; a janela dele ([Janela]) vai ao [modelo], que diz se
 * é o toque fraco, o estalo ou nada. Em [coletando] (a calibração), toda janela
 * sai, sem comando e em qualquer postura.
 *
 * Os comandos: batidas da mesma força dentro de [JUNTAR_MS] somam; a pressão
 * do dedo antes do estalo e o rebote depois dele são do estalo; um terceiro na
 * janela cancela (digitar); com o dedo na tela ou a vibração, nada conta; no
 * uso, só com o antebraço [ELEVACAO_MIN_GRAUS] acima da horizontal.
 */
class Batida(
    var modelo: ModeloBatida?,
    private val coletando: Boolean = false,
    private val gatilhoColeta: Float = 0.45f,
) {
    // a parte lenta (filtro de primeira ordem) da aceleração e do giro
    private val lenta = FloatArray(6)
    private var temLenta = false
    private val giroAgora = FloatArray(3)
    // as últimas leituras sem a parte lenta: 6 canais, num anel
    private val anel = Array(6) { FloatArray(ANEL) }
    private var n = 0L
    private var media = 0f
    // o candidato em andamento
    private var picoEm = -1L
    private var picoE = 0f
    private var picoMs = 0L
    private val gravidade = FloatArray(3)
    private var surdoAte = 0L
    // os comandos
    private var pendente: Forca? = null
    private var vezes = 0
    private var ultimaMs = 0L
    private val janelas = ArrayList<Janela>()
    private val comandos = ArrayList<Comando>()
    private val registros = ArrayList<String>()

    fun zerar() {
        temLenta = false; n = 0; media = 0f; picoEm = -1; pendente = null; vezes = 0
        janelas.clear(); comandos.clear(); registros.clear()
    }

    /** O giroscópio (rad/s): a última leitura entra com a próxima do acelerômetro. */
    fun giro(x: Float, y: Float, z: Float) {
        giroAgora[0] = x; giroAgora[1] = y; giroAgora[2] = z
    }

    /** Uma leitura do acelerômetro (m/s²) no instante [ms]. */
    fun acel(ms: Long, x: Float, y: Float, z: Float) {
        val v = floatArrayOf(x, y, z, giroAgora[0], giroAgora[1], giroAgora[2])
        if (!temLenta) { v.copyInto(lenta); temLenta = true; return }
        val k = (n % ANEL).toInt()
        var ea = 0f
        var eg = 0f
        for (c in 0 until 6) {
            lenta[c] += (v[c] - lenta[c]) * LENTA
            val r = v[c] - lenta[c]
            anel[c][k] = r
            if (c < 3) ea += r * r else eg += r * r
        }
        val e = sqrt(ea) + sqrt(eg)
        n++
        val gatilho = if (coletando) gatilhoColeta else (modelo?.gatilho ?: ModeloBatida.GATILHO_PADRAO)

        if (ms < TelaTocada.surdoAte) {
            picoEm = -1; pendente = null; vezes = 0
            media += (e - media) * 0.05f
            return
        }
        if (picoEm < 0) {
            if (e >= gatilho && media < gatilho * QUIETO && ms >= surdoAte) {
                picoEm = n - 1; picoE = e; picoMs = ms
                for (c in 0 until 3) gravidade[c] = lenta[c]
                TelaTocada.ultimoTrancoMs = ms
            } else {
                media += (e - media) * 0.05f
            }
        } else {
            if (e > picoE && n - 1 - picoEm <= 6) { picoEm = n - 1; picoE = e; picoMs = ms }
            if (n - 1 - picoEm >= AMOSTRAS - ANTES - 1) {
                fecharJanela()
                picoEm = -1
                surdoAte = ms + REFRATARIO_MS
                media = 0f
            }
        }
        if (pendente != null && ms - ultimaMs > JUNTAR_MS) soltar()
    }

    private fun fecharJanela() {
        val forma = FloatArray(DIMENSAO)
        var aMax = 0f
        var gMax = 0f
        for (t in 0 until AMOSTRAS) {
            val idx = picoEm - ANTES + t
            if (idx < 0 || idx > n - 1 || n - 1 - idx >= ANEL) continue
            val k = (idx % ANEL).toInt()
            var a2 = 0f
            var g2 = 0f
            for (c in 0 until 6) {
                val r = anel[c][k]
                forma[c * AMOSTRAS + t] = r
                if (c < 3) a2 += r * r else g2 += r * r
            }
            aMax = max(aMax, sqrt(a2)); gMax = max(gMax, sqrt(g2))
        }
        // os dois sensores pesam igual na forma (o giro, em rad/s, é menor que a aceleração)
        var na = 0f
        var ng = 0f
        for (i in 0 until 3 * AMOSTRAS) na += forma[i] * forma[i]
        for (i in 3 * AMOSTRAS until DIMENSAO) ng += forma[i] * forma[i]
        na = sqrt(na).coerceAtLeast(1e-6f) * RAIZ2
        ng = sqrt(ng).coerceAtLeast(1e-6f) * RAIZ2
        for (i in 0 until 3 * AMOSTRAS) forma[i] /= na
        for (i in 3 * AMOSTRAS until DIMENSAO) forma[i] /= ng
        val j = Janela(forma, aMax, gMax)
        if (coletando) { janelas += j; return }
        val m = modelo ?: return
        if (!naPostura()) { registros += "acel %.1f giro %.1f: braço abaixo de 15°".format(aMax, gMax); return }
        val (f, motivo) = m.classificar(j)
        registros += "acel %.1f giro %.1f: %s".format(aMax, gMax, motivo)
        if (f != null) { janelas += j; contar(picoMs, f) }
    }

    /** O antebraço pelo menos [ELEVACAO_MIN_GRAUS] acima da horizontal: x do relógio corre ao longo dele. */
    private fun naPostura(): Boolean {
        val g = sqrt(gravidade[0] * gravidade[0] + gravidade[1] * gravidade[1] + gravidade[2] * gravidade[2])
        return g > 1f && gravidade[0] / g >= SEN_ELEVACAO
    }

    private fun contar(ms: Long, f: Forca) {
        // a pressão do dedo antes do estalo e o rebote depois dele são do estalo
        if (f == Forca.FORTE && pendente == Forca.FRACA && vezes == 1 && ms - ultimaMs <= PRE_ESTALO_MS) {
            pendente = Forca.FORTE; ultimaMs = ms; return
        }
        if (f == Forca.FRACA && pendente == Forca.FORTE && ms - ultimaMs <= PRE_ESTALO_MS) return
        if (pendente != null && pendente != f) soltar()
        pendente = f
        vezes++
        ultimaMs = ms
        if (vezes > 2) {
            registros += "terceiro na janela: comando cancelado (rajada)"
            pendente = null; vezes = 0; surdoAte = ms + SURDO_MS
        }
    }

    private fun soltar() {
        pendente?.let { comandos += Comando(it, vezes) }
        pendente = null
        vezes = 0
    }

    /** As janelas desde a última vez: na coleta, todas; no uso, as que contaram. */
    fun tirarJanelas(): List<Janela> = janelas.toList().also { janelas.clear() }

    fun tirarComandos(): List<Comando> = comandos.toList().also { comandos.clear() }

    /** O que se decidiu de cada candidato, para o log. */
    fun tirarRegistros(): List<String> = registros.toList().also { registros.clear() }

    companion object {
        private const val ANEL = 64
        private const val LENTA = 0.12f          // ~2 Hz a 100 Hz: abaixo disso é o braço e a gravidade
        private const val QUIETO = 0.5f          // a energia média antes do candidato abaixo da metade do gatilho
        private const val REFRATARIO_MS = 40L
        private const val SURDO_MS = 400L
        private const val PRE_ESTALO_MS = 350L
        private const val RAIZ2 = 1.41421f
        const val JUNTAR_MS = 750L               // os pares do Davi: 650 a 1090 ms
        const val ELEVACAO_MIN_GRAUS = 15
        private const val SEN_ELEVACAO = 0.2588f // sen 15°

        /** um número só para comparar a força de duas janelas */
        fun forca(j: Janela) = j.aceleracao + j.giro
    }
}
