package io.orbe.watch.gesto

import kotlin.math.sqrt

/**
 * O dedo na tela: tocar o relógio manda tranco pelo pulso como uma batida.
 * A MainActivity marca até quando (relógio do sistema, ms desde o boot, a base
 * dos sensores) o detector fica surdo.
 */
object TelaTocada {
    @Volatile var surdoAte = 0L
    /** o último tranco de batida (ms desde o boot): o estalo gira o pulso e a sacudida de sair não pode valer nele */
    @Volatile var ultimoTrancoMs = 0L
}

/** A força de uma batida: o toque fraco (dedo médio no dedão, ou a ponta dos dedos na mesa) ou o estalo. */
enum class Forca { FRACA, FORTE }

/** O que uma ou duas batidas seguidas da mesma força deram. */
data class Comando(val forca: Forca, val vezes: Int)

/** Uma batida detectada: a força e o que se mediu dela (para a calibração e o log). */
data class Medida(val pico: Float, val giro: Float, val largura: Int, val forca: Forca, val gravidade: FloatArray)

/**
 * Batidas do pulso pelo acelerômetro e pelo giroscópio: uma perturbação
 * instantânea (o tranco que o estalo ou a batida entre os dedos manda pelo
 * pulso) com o relógio quieto antes e de volta ao quieto logo depois. Mover o
 * braço é longo e não arma; o tranco do estalo dura um ou dois quadros a 100 Hz.
 *
 * A força sai do pico da aceleração sem a parte lenta (a gravidade e o gesto
 * do braço): até [forteMin], fraca; daí para cima, forte. Os limiares vêm da
 * calibração de cada uma. Batidas da mesma força dentro de [JUNTAR_MS] somam;
 * o comando sai depois dessa espera sem outra.
 */
class Batida(
    /** pico mínimo para contar como batida (a fraca mais leve da calibração, com margem) */
    var fracaMin: Float = FRACA_MIN,
    /** a partir deste pico, forte (entre a fraca mais forte e o estalo mais fraco da calibração) */
    var forteMin: Float = FORTE_MIN,
    /** na calibração: qualquer postura (a regra do braço erguido é do uso) */
    private val calibrando: Boolean = false,
) {
    // a parte lenta da aceleração (filtro de primeira ordem): o que sobra é o tranco
    private var lx = 0f
    private var ly = 0f
    private var lz = 0f
    private var temLenta = false
    // o "quieto" antes do tranco: média móvel do tranco e do giro
    private var ruido = 0f
    private var giroMedio = 0f
    // o tranco em andamento
    private var emPico = false
    private var picoMax = 0f
    private var picoGiro = 0f
    private var largura = 0
    private var depois = 0
    private var bloqueadoAte = 0L
    private var giroAgora = 0f
    // a fila do comando
    private var pendente: Forca? = null
    private var vezes = 0
    private var ultimaMs = 0L
    private var ultimoTrancoMs = -1_000_000L   // o último tranco curto de qualquer força (digitar dá rajada)
    private val medidas = ArrayList<Medida>()
    private val comandos = ArrayList<Comando>()
    private val descartes = ArrayList<String>()

    /** Por que cada tranco curto não contou, desde a última vez (para o log). */
    fun tirarDescartes(): List<String> = descartes.toList().also { descartes.clear() }

    fun zerar() {
        temLenta = false; ruido = 0f; giroMedio = 0f
        emPico = false; pendente = null; vezes = 0
        medidas.clear(); comandos.clear()
    }

    // o giro do pulso nos 200 ms depois de cada tranco (o estalo gira o pulso; o toque, pouco): só para o log, por ora
    private var giroPosAte = 0L
    private var giroPosMax = 0f
    private var giroPosPico = 0f
    private val posGiros = ArrayList<Pair<Float, Float>>()

    /** (pico do tranco, giro máximo nos 200 ms seguintes) de cada tranco curto. */
    fun tirarPosGiros(): List<Pair<Float, Float>> = posGiros.toList().also { posGiros.clear() }

    /** O giroscópio (rad/s): só a intensidade, para o quieto e a medida. */
    fun giro(x: Float, y: Float, z: Float) {
        giroAgora = sqrt(x * x + y * y + z * z)
        if (giroPosAte > 0L && giroAgora > giroPosMax) giroPosMax = giroAgora
        giroMedio += (giroAgora - giroMedio) * 0.1f
        if (emPico && giroAgora > picoGiro) picoGiro = giroAgora
    }

    /** Uma leitura do acelerômetro (m/s²) no instante [ms]. */
    fun acel(ms: Long, x: Float, y: Float, z: Float) {
        if (!temLenta) { lx = x; ly = y; lz = z; temLenta = true; return }
        if (giroPosAte > 0L && ms > giroPosAte) {
            posGiros += giroPosPico to giroPosMax
            giroPosAte = 0L
        }
        lx += (x - lx) * LENTA; ly += (y - ly) * LENTA; lz += (z - lz) * LENTA
        val dx = x - lx; val dy = y - ly; val dz = z - lz
        val tranco = sqrt(dx * dx + dy * dy + dz * dz)
        if (ms < TelaTocada.surdoAte) {
            // o dedo está (ou acabou de estar) na tela: nada conta, e o comando em curso cai
            emPico = false; pendente = null; vezes = 0
            ruido += (tranco - ruido) * 0.08f
            return
        }
        if (!emPico) {
            val quieto = ruido < QUIETO && giroMedio < GIRO_QUIETO
            if (quieto && ms >= bloqueadoAte && tranco >= fracaMin * GATILHO) {
                emPico = true; picoMax = tranco; picoGiro = giroAgora; largura = 1; depois = 0
                TelaTocada.ultimoTrancoMs = ms
            } else {
                ruido += (tranco - ruido) * 0.08f
            }
        } else {
            if (tranco > picoMax) { picoMax = tranco; depois = 0 } else depois++
            if (tranco > picoMax * 0.5f) largura++
            // o tranco voltou ao quieto: decide; durou demais: era o braço
            if (tranco < picoMax * 0.3f || depois >= VOLTA_AMOSTRAS) {
                emPico = false
                bloqueadoAte = ms + REFRATARIO_MS
                val curto = largura <= LARGURA_MAX && depois < VOLTA_AMOSTRAS
                if (curto && giroPosAte == 0L) { giroPosAte = ms + 200; giroPosMax = picoGiro; giroPosPico = picoMax }
                if (curto && picoMax >= fracaMin && !naPostura()) descartes += "pico %.1f: braço abaixo de 15°".format(picoMax)
                if (curto && picoMax >= fracaMin && naPostura()) {
                    val f = if (picoMax >= forteMin) Forca.FORTE else Forca.FRACA
                    val anterior = ultimoTrancoMs
                    ultimoTrancoMs = ms
                    if (pendente == null && ms - anterior < ISOLADO_MS) {
                        // tranco logo depois de outro sem comando em curso: é rajada (digitar)
                        bloqueadoAte = ms + SURDO_MS
                        descartes += "pico %.1f: rajada (outro tranco %d ms antes)".format(picoMax, ms - anterior)
                    } else {
                        medidas += Medida(picoMax, picoGiro, largura, f, floatArrayOf(lx, ly, lz))
                        contar(ms, f)
                    }
                }
                ruido = 0f
            }
        }
        // a espera do segundo terminou: sai o comando
        if (pendente != null && ms - ultimaMs > JUNTAR_MS) soltar()
    }

    /**
     * O antebraço pelo menos [ELEVACAO_MIN_GRAUS] acima da horizontal (a posição
     * de falar): x do relógio corre ao longo do antebraço e a gravidade nele
     * cresce com a mão subindo (medido no relógio do Davi: 1 a 4 m/s² nos toques).
     */
    private fun naPostura(): Boolean {
        if (calibrando) return true
        val g = sqrt(lx * lx + ly * ly + lz * lz)
        return g > 1f && lx / g >= SEN_ELEVACAO
    }

    private fun contar(ms: Long, f: Forca) {
        // o estalo começa com a pressão do dedo (um tranco leve) e estala logo
        // depois: o forte que chega em seguida a uma fraca sozinha toma o lugar dela
        if (f == Forca.FORTE && pendente == Forca.FRACA && vezes == 1 && ms - ultimaMs <= PRE_ESTALO_MS) {
            pendente = Forca.FORTE
            ultimaMs = ms
            return
        }
        // e o rebote fraco logo depois do estalo também é dele
        if (f == Forca.FRACA && pendente == Forca.FORTE && ms - ultimaMs <= PRE_ESTALO_MS) return
        if (pendente != null && pendente != f) soltar()
        pendente = f
        vezes++
        ultimaMs = ms
        // um terceiro na janela: era rajada, nada sai
        if (vezes > 2) {
            descartes += "terceiro toque na janela: comando cancelado"
            pendente = null
            vezes = 0
            bloqueadoAte = ms + SURDO_MS
        }
    }

    private fun soltar() {
        pendente?.let { comandos += Comando(it, vezes) }
        pendente = null
        vezes = 0
    }

    /** As batidas medidas desde a última vez (a calibração lê daqui). */
    fun tirarMedidas(): List<Medida> = medidas.toList().also { medidas.clear() }

    /** Os comandos prontos desde a última vez. */
    fun tirarComandos(): List<Comando> = comandos.toList().also { comandos.clear() }

    companion object {
        /** padrões antes de calibrar, em m/s² de tranco */
        const val FRACA_MIN = 2.0f
        const val FORTE_MIN = 7.0f
        private const val LENTA = 0.12f          // ~2 Hz a 100 Hz: abaixo disso é o braço e a gravidade
        private const val GATILHO = 0.8f         // arma um pouco abaixo do mínimo, mede o pico inteiro
        private const val QUIETO = 0.8f          // tranco médio do pulso parado
        private const val GIRO_QUIETO = 1.2f     // rad/s: girando o pulso não é batida
        private const val LARGURA_MAX = 4        // amostras acima da metade do pico (~40 ms)
        private const val VOLTA_AMOSTRAS = 12    // ~120 ms para voltar ao quieto
        private const val REFRATARIO_MS = 80L    // o rebote do tranco não conta como outra (curto: o estalo vem logo depois da pressão)
        private const val PRE_ESTALO_MS = 350L   // a pressão do dedo antes do estalo
        private const val ISOLADO_MS = 500L      // sem outro tranco antes disso: o primeiro de um comando
        private const val SURDO_MS = 600L        // depois de uma rajada, um tempo sem contar
        const val ELEVACAO_MIN_GRAUS = 15
        private const val SEN_ELEVACAO = 0.2588f  // sen 15°
        const val JUNTAR_MS = 750L               // até aqui, a segunda da mesma força soma (os pares do Davi: 650 a 1090 ms)
    }
}

/** Calibração: fraca no mínimo das fracas medidas com margem; forte entre a fraca mais forte e o estalo mais fraco. */
fun limiaresBatida(fracas: List<Float>, fortes: List<Float>): Pair<Float, Float> {
    val fracaMin = ((fracas.minOrNull() ?: Batida.FRACA_MIN / MARGEM_BATIDA) * MARGEM_BATIDA).coerceAtLeast(PISO_FRACA)
    val fracaMax = fracas.maxOrNull() ?: Batida.FRACA_MIN
    val forteMenor = fortes.minOrNull() ?: Batida.FORTE_MIN
    // a meio caminho (geométrico) entre as duas; sem separação, logo acima da fraca mais forte
    val forteMin = if (forteMenor > fracaMax) sqrt(fracaMax * forteMenor) else fracaMax * 1.15f
    return fracaMin to forteMin.coerceAtLeast(fracaMin * 1.2f)
}

/** a batida precisa de 85% da tentativa mais leve da calibração (com 60%, como a sacudida, pegava o pulso parado) */
const val MARGEM_BATIDA = 0.85f
/** nenhum limiar abaixo disto (m/s²): é o tremor do pulso parado */
const val PISO_FRACA = 0.5f
