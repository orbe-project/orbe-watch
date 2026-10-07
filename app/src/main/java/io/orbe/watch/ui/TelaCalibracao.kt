package io.orbe.watch.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.basicCurvedText
import io.orbe.watch.gesto.Batida
import io.orbe.watch.gesto.Janela
import io.orbe.watch.gesto.ModeloBatida
import io.orbe.watch.gesto.Picos
import io.orbe.watch.gesto.Sacudida
import io.orbe.watch.gesto.ServicoSacudida
import io.orbe.watch.gesto.limiarSair
import io.orbe.watch.gesto.limiares
import kotlin.math.sqrt

/** Qual sacudida a tela de calibração mede. */
enum class Calibracao { ABRIR, SAIR, FRACA, FORTE, NADA }

/**
 * Calibração da sacudida de abrir (a do HinaWatch): mede [TENTATIVAS] sacudidas
 * fora→dentro do jeito que o serviço vê, com a mesma espera parada antes de
 * cada uma, e propõe os limiares a partir da mais fraca. Enquanto a tela está
 * aberta, o serviço não abre o orbe.
 */
@Composable
fun TelaCalibracao(
    emUso: Picos,
    redonda: Boolean,
    salvar: (Picos) -> Unit,
    padrao: () -> Unit,
) {
    val tentativas = remember { mutableStateListOf<Picos>() }
    val sacudida = remember { Sacudida() }
    Sensores(sacudida) {
        val par = sacudida.tirarPar() ?: return@Sensores
        // um ajeitar do pulso entre as tentativas não é sacudida
        if (par.dentro < TENTATIVA_MIN || tentativas.size >= TENTATIVAS) return@Sensores
        tentativas += par
        // cada tentativa recomeça com a espera parada, como ao acender a tela
        sacudida.zerar()
    }
    val proposta = if (tentativas.size >= TENTATIVAS) limiares(tentativas) else null
    Moldura(
        "Calibrar o abrir", redonda,
        aviso = if (proposta != null) "Fora ${um(proposta.fora)} · dentro ${um(proposta.dentro)} rad/s"
        else "Pulso parado, depois uma sacudida para fora e de volta. Tentativa ${tentativas.size + 1} de $TENTATIVAS.",
        tentativas = tentativas.map { "fora ${um(it.fora)} · dentro ${um(it.dentro)}" },
        padrao = "fora ${um(Sacudida.FORA_MIN)} · dentro ${um(Sacudida.DENTRO_MIN)}", aoPadrao = padrao,
        pronta = proposta != null, refazer = { tentativas.clear() }, salvar = { proposta?.let(salvar) },
        emUso = "fora ${um(emUso.fora)} · dentro ${um(emUso.dentro)}",
    )
}

/**
 * Calibração da sacudida de sair (a do fechar do HinaWatch): mede [TENTATIVAS]
 * sacudidas só para fora com o detector armado uma vez, como o orbe aberto vê
 * (sem a espera parada a cada uma), e propõe o fora a partir da mais fraca.
 * Com o orbe aberto a sacudida sai no pico para fora, então só o fora conta.
 */
@Composable
fun TelaCalibracaoSair(
    emUso: Float,
    redonda: Boolean,
    salvar: (Float) -> Unit,
    padrao: () -> Unit,
) {
    val foras = remember { mutableStateListOf<Float>() }
    val sacudida = remember { Sacudida(paradoMs = Sacudida.PARADO_MS) }
    Sensores(sacudida) {
        val fora = sacudida.tirarFora() ?: return@Sensores
        if (fora < TENTATIVA_MIN || foras.size >= TENTATIVAS) return@Sensores
        foras += fora
    }
    val proposta = if (foras.size >= TENTATIVAS) limiarSair(foras) else null
    Moldura(
        "Calibrar o sair", redonda,
        aviso = if (proposta != null) "Fora ${um(proposta)} rad/s"
        else "Pulso parado, depois uma sacudida só para fora. Tentativa ${foras.size + 1} de $TENTATIVAS.",
        tentativas = foras.map { "fora ${um(it)}" },
        padrao = "fora ${um(Sacudida.FORA_MIN)}", aoPadrao = padrao,
        pronta = proposta != null, refazer = { foras.clear() }, salvar = { proposta?.let(salvar) },
        emUso = if (emUso > 0f) "fora ${um(emUso)}" else "o padrão, fora ${um(Sacudida.FORA_MIN)}",
    )
}

/**
 * Calibração das batidas pelo perfil do movimento. No toque fraco e no estalo,
 * [TENTATIVAS_BATIDA] tentativas: os trancos que chegam juntos (a pressão do
 * dedo, o gesto, o rebote) são uma tentativa só, com a janela mais forte do
 * grupo. Em "o que não é batida", [SEGUNDOS_NADA] segundos de movimento comum
 * (digitar, tocar a tela, mexer o braço): toda janela vira exemplo do que recusar.
 */
@Composable
fun TelaCalibracaoBatida(
    tipo: Calibracao,
    emUso: String,
    redonda: Boolean,
    salvar: (List<Janela>) -> Unit,
    padrao: () -> Unit,
    dizer: (String) -> Unit = {},
) {
    val janelas = remember { mutableStateListOf<Janela>() }
    val batida = remember { Batida(null, coletando = true) }
    val nada = tipo == Calibracao.NADA
    // o grupo em andamento (tentativas) e o relógio da gravação (nada)
    val grupo = remember { arrayOfNulls<Janela>(1) }
    val tempos = remember { longArrayOf(0L, 0L) }      // último tranco do grupo, começo da gravação
    var restante by remember { mutableIntStateOf(if (nada) SEGUNDOS_NADA else 0) }
    SensoresBatida(batida) {
        val agora = android.os.SystemClock.elapsedRealtime()
        if (nada) {
            if (tempos[1] == 0L) tempos[1] = agora
            val r = SEGUNDOS_NADA - ((agora - tempos[1]) / 1000).toInt()
            if (r != restante) restante = r.coerceAtLeast(0)
            for (j in batida.tirarJanelas()) if (restante > 0 && janelas.size < NADA_MAX) janelas += j
            return@SensoresBatida
        }
        for (j in batida.tirarJanelas()) {
            Log.i("OrbeBatida", "calibração $tipo: acel %.1f giro %.1f".format(j.aceleracao, j.giro))
            val g = grupo[0]
            if (g == null || Batida.forca(j) > Batida.forca(g)) grupo[0] = j
            tempos[0] = agora
        }
        val g = grupo[0]
        if (g != null && agora - tempos[0] > 500) {
            grupo[0] = null
            if (janelas.size < TENTATIVAS_BATIDA) {
                Log.i("OrbeBatida", "calibração $tipo: tentativa acel %.1f giro %.1f".format(g.aceleracao, g.giro))
                janelas += g
                // tremor que entrou como tentativa sai, e a tentativa volta a faltar
                val limpas = ModeloBatida.semTremor(janelas.toList())
                if (limpas.size < janelas.size) {
                    Log.i("OrbeBatida", "calibração $tipo: ${janelas.size - limpas.size} tentativas fracas demais descartadas")
                    janelas.clear(); janelas += limpas
                }
            }
        }
    }
    val pronta = if (nada) restante == 0 else janelas.size >= TENTATIVAS_BATIDA
    // a voz do orbe diz a posição do braço a cada troca, e o fim
    val etapa = if (nada) -1 else if (pronta) 99 else janelas.size / POR_POSICAO
    LaunchedEffect(etapa) {
        when {
            nada -> Unit
            pronta -> dizer("Pronto. Pode salvar.")
            else -> dizer(posicao(janelas.size))
        }
    }
    val titulo = when (tipo) {
        Calibracao.FRACA -> "toque fraco"
        Calibracao.FORTE -> "estalo"
        else -> "o que não é batida"
    }
    val dica = when {
        nada && !pronta -> "digite, toque a tela, mexa o braço · $restante s"
        nada -> "${janelas.size} movimentos para recusar"
        pronta -> "pronto"
        tipo == Calibracao.FRACA -> "dedo médio no dedão"
        else -> "um estalo de dedos"
    }
    MolduraRedonda(
        titulo, dica,
        amostras = if (nada) null else Amostras(janelas.size, TENTATIVAS_BATIDA, POR_POSICAO),
        grafico = { m -> GraficoPerfil(janelas.toList(), if (nada || pronta) null else posicaoCurta(janelas.size), m) },
        mostrarPadrao = janelas.isEmpty() && (!nada || restante == SEGUNDOS_NADA), aoPadrao = padrao,
        pronta = pronta, refazer = { janelas.clear(); tempos[1] = 0L; restante = if (nada) SEGUNDOS_NADA else 0 },
        salvar = { salvar(janelas.toList()) },
    )
}

/**
 * A calibração das batidas no mostrador redondo: o gráfico no centro exato da
 * tela, concêntrico com as barras das amostras na borda; o título curvo no
 * alto e a dica curva no pé, na faixa entre o gráfico e a borda; os botões
 * só quando servem (Padrão antes da primeira tentativa, Refazer e Salvar no fim).
 */
@Composable
private fun MolduraRedonda(
    titulo: String,
    dica: String,
    amostras: Amostras?,
    grafico: @Composable (Modifier) -> Unit,
    mostrarPadrao: Boolean,
    aoPadrao: () -> Unit,
    pronta: Boolean,
    refazer: () -> Unit,
    salvar: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        FundoVidro()
        val lado = minOf(maxWidth, maxHeight)
        amostras?.let { AnelAmostras(it, Modifier.size(lado)) }
        grafico(Modifier.size(lado * 0.78f))
        val cor = Estilo.texto.alfa(0.7f)
        CurvedLayout(Modifier.size(lado - 14.dp), anchor = 270f) {
            basicCurvedText(titulo, style = { CurvedTextStyle(color = cor, fontSize = 10.sp) })
        }
        CurvedLayout(Modifier.size(lado - 14.dp), anchor = 90f, angularDirection = CurvedDirection.Angular.Reversed) {
            basicCurvedText(
                dica, angularDirection = CurvedDirection.Angular.Reversed,
                style = { CurvedTextStyle(color = cor, fontSize = 9.sp) },
            )
        }
        if (pronta || mostrarPadrao) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = lado * 0.17f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (pronta) {
                    Botao("Refazer", aoClicar = refazer)
                    Botao("Salvar", destaque = true, aoClicar = salvar)
                } else {
                    Botao("Padrão", aoClicar = aoPadrao)
                }
            }
        }
    }
}

/** A tela das calibrações, sobre o mesmo fundo do menu. */
@Composable
private fun Moldura(
    titulo: String,
    redonda: Boolean,
    aviso: String,
    tentativas: List<String>,
    padrao: String,
    aoPadrao: () -> Unit,
    pronta: Boolean,
    refazer: () -> Unit,
    salvar: () -> Unit,
    emUso: String,
    grafico: (@Composable () -> Unit)? = null,
    amostras: Amostras? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        FundoVidro()
        // as barras das amostras na borda, paradas enquanto o resto rola
        amostras?.let { AnelAmostras(it, Modifier.fillMaxSize()) }
        val margem = maxWidth * (if (redonda) 0.14f else 0.05f)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = margem),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Vao(if (redonda) 30.dp else 12.dp)
            Texto(titulo, Estilo.grupo, alinhar = TextAlign.Center)
            Vao(8.dp)
            Texto(aviso, Estilo.subtitulo, alinhar = TextAlign.Center)
            Vao(10.dp)
            if (grafico != null) {
                grafico()
                Vao(8.dp)
            }
            Column(Modifier.caixa()) {
                tentativas.forEachIndexed { i, t ->
                    Linha("Tentativa ${i + 1}", subtitulo = t)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Estilo.texto.alfa(0.08f)))
                }
                Linha("Padrão", subtitulo = padrao, aoClicar = aoPadrao)
            }
            if (pronta) {
                Vao(10.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Botao("Refazer", Modifier.weight(1f), aoClicar = refazer)
                    Botao("Salvar", Modifier.weight(1f), destaque = true, aoClicar = salvar)
                }
            }
            Vao(8.dp)
            Texto("Em uso: $emUso", Estilo.mono, cor = Estilo.texto.alfa(0.6f), alinhar = TextAlign.Center)
            Vao(if (redonda) 40.dp else 16.dp)
        }
    }
}

/**
 * Liga acelerômetro e giroscópio à [sacudida] enquanto a tela está aberta;
 * [aoLer] roda depois de cada leitura do giroscópio, para tirar o que ela mediu.
 */
@Composable
private fun Sensores(sacudida: Sacudida, aoLer: () -> Unit) {
    val ctx = LocalContext.current
    val ler = remember { arrayOf(aoLer) }
    ler[0] = aoLer
    DisposableEffect(sacudida) {
        ServicoSacudida.calibrando = true
        val sensores = ctx.getSystemService(SensorManager::class.java)
        var gy = 0f
        var gz = 0f
        val ouvinte = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        gy += (event.values[1] - gy) * ALFA_GRAVIDADE
                        gz += (event.values[2] - gz) * ALFA_GRAVIDADE
                        sacudida.gravidade(gy, gz)
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        val (x, y, z) = event.values
                        sacudida.ler(event.timestamp / 1_000_000, x, sqrt(x * x + y * y + z * z))
                        sacudida.tirarResumo()?.let { Log.i("OrbeGesto", "calibração: $it") }
                        ler[0]()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensores?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensores.registerListener(ouvinte, it, SensorManager.SENSOR_DELAY_GAME) }
        sensores?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensores.registerListener(ouvinte, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose {
            sensores?.unregisterListener(ouvinte)
            ServicoSacudida.calibrando = false
        }
    }
}

/** Liga acelerômetro e giroscópio no mais rápido à [batida] enquanto a tela está aberta. */
@Composable
private fun SensoresBatida(batida: Batida, aoLer: () -> Unit) {
    val ctx = LocalContext.current
    val ler = remember { arrayOf(aoLer) }
    ler[0] = aoLer
    DisposableEffect(batida) {
        ServicoSacudida.calibrando = true
        val sensores = ctx.getSystemService(SensorManager::class.java)
        val ouvinte = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = event.values
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        batida.acel(event.timestamp / 1_000_000, x, y, z)
                        ler[0]()
                    }
                    Sensor.TYPE_GYROSCOPE -> batida.giro(x, y, z)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensores?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensores.registerListener(ouvinte, it, SensorManager.SENSOR_DELAY_FASTEST) }
        sensores?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensores.registerListener(ouvinte, it, SensorManager.SENSOR_DELAY_FASTEST) }
        onDispose {
            sensores?.unregisterListener(ouvinte)
            ServicoSacudida.calibrando = false
        }
    }
}

private fun um(v: Float) = "%.1f".format(v).replace('.', ',')

private const val TENTATIVAS = 3
private const val TENTATIVAS_BATIDA = 20

private const val POR_POSICAO = 5

/** A posição em poucas palavras, para o texto curvo do gráfico. */
private fun posicaoCurta(feitas: Int) = "posição ${minOf(feitas / POR_POSICAO, 3) + 1} de 4 · " + when (feitas / POR_POSICAO) {
    0 -> "braço erguido"
    1 -> "braço à frente"
    2 -> "braço solto"
    else -> "pulso virado"
}

/** As amostras feitas de [meta], em grupos de [porGrupo] (uma posição do braço cada). */
class Amostras(val feitas: Int, val meta: Int, val porGrupo: Int)

/**
 * As barras das amostras na borda da tela: uma por tentativa, finas, as feitas
 * no accent e as que faltam quase apagadas; um vão maior entre os grupos marca
 * cada posição do braço.
 */
@Composable
private fun AnelAmostras(a: Amostras, modifier: Modifier) {
    val accent = Estilo.accent
    val texto = Estilo.texto
    androidx.compose.foundation.Canvas(modifier) {
        val raio = minOf(size.width, size.height) / 2 - 4.dp.toPx()
        val grupos = (a.meta + a.porGrupo - 1) / a.porGrupo
        val vaoGrupo = 6f
        val vao = 1.6f
        val porGrau = (360f - grupos * vaoGrupo) / a.meta
        val lado = androidx.compose.ui.geometry.Size(raio * 2, raio * 2)
        val topo = androidx.compose.ui.geometry.Offset(center.x - raio, center.y - raio)
        for (k in 0 until a.meta) {
            val ini = -90f + vaoGrupo / 2 + (k / a.porGrupo) * vaoGrupo + k * porGrau + vao / 2
            drawArc(
                if (k < a.feitas) accent.alfa(0.75f) else texto.alfa(0.10f),
                ini, porGrau - vao, false, topo, lado,
                style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
            )
        }
    }
}

/** A posição do braço pedida a cada cinco tentativas: o perfil guarda os exemplos de todas. */
private fun posicao(feitas: Int) = when (feitas / POR_POSICAO) {
    0 -> "Braço erguido, tela para você."
    1 -> "Braço na horizontal, à frente."
    2 -> "Braço solto, ao lado do corpo."
    else -> "Pulso virado, tela para cima ou de lado."
}
private const val SEGUNDOS_NADA = 15
private const val NADA_MAX = 24
private const val ALFA_GRAVIDADE = 0.2f

/** pico mínimo para uma tentativa contar (o dentro no abrir, o fora no sair): abaixo disso é o pulso se ajeitando */
private const val TENTATIVA_MIN = 5f
