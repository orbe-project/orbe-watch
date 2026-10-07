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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
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
        "Calibrar o abrir",
        aviso = if (proposta != null) "Fora ${um(proposta.fora)} · dentro ${um(proposta.dentro)} rad/s"
        else "Pulso parado, depois uma sacudida para fora e de volta.",
        feitas = tentativas.size, meta = TENTATIVAS, contagem = "${tentativas.size}", rotulo = "de $TENTATIVAS",
        aoPadrao = padrao,
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
        "Calibrar o sair",
        aviso = if (proposta != null) "Fora ${um(proposta)} rad/s"
        else "Pulso parado, depois uma sacudida só para fora.",
        feitas = foras.size, meta = TENTATIVAS, contagem = "${foras.size}", rotulo = "de $TENTATIVAS",
        aoPadrao = padrao,
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
    val titulo = when (tipo) {
        Calibracao.FRACA -> "Calibrar o toque fraco"
        Calibracao.FORTE -> "Calibrar o estalo"
        else -> "O que não é batida"
    }
    val aviso = when {
        nada && !pronta -> "Digite, toque a tela, mexa o braço como sempre."
        nada -> "${janelas.size} movimentos guardados para recusar."
        pronta -> "Pronto: ${janelas.size} tentativas."
        tipo == Calibracao.FRACA -> "Um toque do dedo médio no dedão. ${posicao(janelas.size)}"
        else -> "Um estalo. ${posicao(janelas.size)}"
    }
    Moldura(
        titulo,
        aviso = aviso,
        feitas = if (nada) SEGUNDOS_NADA - restante else janelas.size,
        meta = if (nada) SEGUNDOS_NADA else TENTATIVAS_BATIDA,
        contagem = if (nada && !pronta) "$restante s" else "${janelas.size}",
        rotulo = if (nada) "gravando" else "de $TENTATIVAS_BATIDA",
        grafico = { m -> GraficoPerfil(janelas.toList(), m) },
        aoPadrao = padrao,
        pronta = pronta, refazer = { janelas.clear(); tempos[1] = 0L; restante = if (nada) SEGUNDOS_NADA else 0 },
        salvar = { salvar(janelas.toList()) },
        emUso = emUso,
    )
}

/**
 * A tela das calibrações, feita para o mostrador redondo e em vidro líquido: o
 * anel de progresso na borda (um gomo por tentativa), o gráfico do perfil (nas
 * batidas) concêntrico com a tela, a lente no centro com a contagem, o aviso
 * numa placa de vidro embaixo dela e os botões no pé, onde a corda do círculo
 * ainda é larga. Sem rolagem: tudo cabe no círculo.
 */
@Composable
private fun Moldura(
    titulo: String,
    aviso: String,
    feitas: Int,
    meta: Int,
    contagem: String,
    rotulo: String,
    aoPadrao: () -> Unit,
    pronta: Boolean,
    refazer: () -> Unit,
    salvar: () -> Unit,
    emUso: String,
    grafico: (@Composable (Modifier) -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        FundoVidro()
        val lado = minOf(maxWidth, maxHeight)
        AnelProgresso(feitas, meta, Modifier.size(lado))
        grafico?.invoke(Modifier.size(lado * 0.80f))
        Texto(
            titulo, Estilo.grupo,
            Modifier.align(Alignment.TopCenter).padding(top = lado * 0.10f).width(lado * 0.58f),
            alinhar = TextAlign.Center, linhas = 1,
        )
        // a lente: a contagem no centro exato da tela
        Column(
            Modifier.offset(y = -lado * 0.04f).size(lado * 0.30f).vidro(CircleShape),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Texto(contagem, Estilo.grupo.copy(fontSize = 17.sp), alinhar = TextAlign.Center, linhas = 1)
            Texto(if (pronta) "pronto" else rotulo, Estilo.mono, cor = Estilo.texto.alfa(0.6f), linhas = 1)
        }
        Box(
            Modifier.offset(y = lado * 0.20f).width(lado * 0.66f)
                .vidro(RoundedCornerShape(14.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Texto(aviso, Estilo.subtitulo, alinhar = TextAlign.Center, linhas = 4)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = lado * 0.07f).width(lado * 0.62f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (pronta) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BotaoVidro("Refazer", Modifier.weight(1f), aoClicar = refazer)
                    BotaoVidro("Salvar", Modifier.weight(1f), destaque = true, aoClicar = salvar)
                }
            } else {
                BotaoVidro("Padrão", aoClicar = aoPadrao)
            }
            Vao(3.dp)
            Texto("em uso: $emUso", Estilo.mono, cor = Estilo.texto.alfa(0.55f), alinhar = TextAlign.Center, linhas = 1)
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

/** A posição do braço pedida a cada cinco tentativas: o perfil guarda os exemplos de todas. */
private fun posicao(feitas: Int) = when (feitas / 5) {
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
