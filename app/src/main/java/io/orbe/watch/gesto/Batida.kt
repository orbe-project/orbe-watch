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
    /** o dedo na tela: até aqui nada conta, e o comando em curso cai */
    @Volatile var surdoAte = 0L
    /** a vibração do aviso: até aqui nada conta, mas o comando em curso segue (ele é o que vibrou) */
    @Volatile var vibrandoAte = 0L
    /** a última batida reconhecida (ms desde o boot): o estalo gira o pulso e a sacudida de sair não pode valer nela */
    @Volatile var ultimoTrancoMs = 0L
}

/** A força de uma batida: o toque fraco (dedo médio no dedão, ou a ponta dos dedos na mesa) ou o estalo. */
enum class Forca { FRACA, FORTE }

/**
 * Uma sequência de batidas: [vezes] movimentos (1 a 4); com [forca] FORTE o
 * último foi o estalo (os anteriores, toques fracos), com FRACA todos foram toques.
 */
data class Comando(val forca: Forca, val vezes: Int) {
    /** a posição na lista das ações: 0 a 3 os toques, 4 a 7 os que terminam em estalo */
    val indice get() = (if (forca == Forca.FORTE) 4 else 0) + vezes - 1
}

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

        /**
         * As tentativas sem as que são tremor: força abaixo de um quarto da mediana
         * (a calibração do estalo pegou 0,4 e 0,8 junto de estalos de 22, e o
         * perfil passou a aceitar tremor como estalo).
         */
        fun semTremor(l: List<Janela>): List<Janela> {
            if (l.size < 3) return l
            val mediana = l.map { Batida.forca(it) }.sorted()[l.size / 2]
            return l.filter { Batida.forca(it) >= mediana / 4f }
        }
        fun de(fracasTodas: List<Janela>, fortesTodas: List<Janela>, nada: List<Janela>): ModeloBatida {
            val fracas = semTremor(fracasTodas)
            val fortes = semTremor(fortesTodas)
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
 * Os comandos: toques fracos a menos de [JUNTAR_MS] um do outro somam (até
 * [MAX_VEZES]); o estalo fecha a sequência na hora; a pressão do dedo antes do
 * estalo e o rebote depois dele são do estalo; um toque além do máximo cancela
 * (digitar); com o dedo na tela ou a vibração, nada conta. Vale
 * em qualquer posição do braço: o perfil já separa o gesto do resto.
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
    private var surdoAte = 0L
    // os comandos
    private var pendente: Forca? = null
    private var vezes = 0
    private var ultimaMs = 0L
    private var ultimoEstaloMs = 0L
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
        if (ms < TelaTocada.vibrandoAte) {
            // a média não bebe o tremor do motor: senão a segunda batida, logo depois, é recusada
            picoEm = -1
            if (pendente != null && ms - ultimaMs > JUNTAR_MS) soltar()
            return
        }
        if (picoEm < 0) {
            if (e >= gatilho && media < gatilho * QUIETO && ms >= surdoAte) {
                picoEm = n - 1; picoE = e; picoMs = ms
            } else {
                media += (e - media) * 0.05f
            }
        } else {
            if (e > picoE && n - 1 - picoEm <= 6) { picoEm = n - 1; picoE = e; picoMs = ms }
            // um pulso bem maior depois do pico (o candidato era resto da vibração ou
            // tremor): o candidato passa a ser ele, senão a batida de verdade se perde
            else if (e > picoE * 1.5f && e >= gatilho) {
                registros += "candidato trocado: %.2f por %.2f".format(picoE, e)
                picoEm = n - 1; picoE = e; picoMs = ms
            }
            if (n - 1 - picoEm >= AMOSTRAS - ANTES - 1) {
                fecharJanela()
                picoEm = -1
                surdoAte = ms + REFRATARIO_MS
                media = 0f
            }
        }
        // com um candidato aberto (pico já visto, janela ainda enchendo) o comando
        // espera: o candidato pode ser o toque seguinte da mesma sequência
        if (pendente != null && picoEm < 0 && ms - ultimaMs > JUNTAR_MS) soltar()
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
        val (f, motivo) = m.classificar(j)
        registros += "acel %.1f giro %.1f: %s".format(aMax, gMax, motivo)
        if (f != null) { janelas += j; TelaTocada.ultimoTrancoMs = picoMs; contar(picoMs, f) }
    }

    private fun contar(ms: Long, f: Forca) {
        if (f == Forca.FORTE) {
            // o toque logo antes é a pressão do dedo do próprio estalo
            if (vezes > 0 && ms - ultimaMs <= PRE_ESTALO_MS) vezes--
            comandos += Comando(Forca.FORTE, vezes + 1)
            pendente = null; vezes = 0; ultimoEstaloMs = ms
            return
        }
        if (ms - ultimoEstaloMs <= PRE_ESTALO_MS) return // o rebote do estalo
        pendente = Forca.FRACA
        vezes++
        ultimaMs = ms
        if (vezes > MAX_VEZES) {
            registros += "toque além de $MAX_VEZES: comando cancelado (rajada)"
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
        const val MAX_VEZES = 4
        const val JUNTAR_MS = 1250L              // os pares do Davi: 650 a 1150 ms (pico a pico)

        /** um número só para comparar a força de duas janelas */
        fun forca(j: Janela) = j.aceleracao + j.giro
    }
}
