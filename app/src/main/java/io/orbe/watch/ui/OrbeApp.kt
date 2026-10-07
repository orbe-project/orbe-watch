package io.orbe.watch.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.orbe.watch.gesto.Batida
import io.orbe.watch.gesto.Forca
import io.orbe.watch.gesto.Comando
import androidx.wear.compose.foundation.BasicSwipeToDismissBox
import androidx.wear.compose.foundation.LocalSwipeToDismissBackgroundScrimColor
import androidx.wear.compose.foundation.LocalSwipeToDismissContentScrimColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import io.orbe.watch.Aparencia
import io.orbe.watch.OrbeViewModel
import io.orbe.watch.dados.Ligacao
import io.orbe.watch.gesto.Picos
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * As duas páginas do relógio, lado a lado: o orbe e, arrastando para a
 * esquerda, o menu (a gaveta e as abas). Na vertical (dedo ou coroa) a lista
 * passa de orbe em orbe; no menu, a coroa rola a aba (a gaveta não rola). Voltar da aba
 * cai na gaveta, e da gaveta no orbe. No menu o pager não arrasta: puxar para a
 * direita volta um nível e para a esquerda volta ao orbe (TelaAjustes). O fundo
 * do menu fica parado atrás das duas páginas: só o conteúdo anda, sem a emenda
 * de um fundo no outro.
 */
@Composable
fun OrbeApp(
    vm: OrbeViewModel,
    podeGravar: () -> Boolean,
    pedirMicrofone: () -> Unit,
    editar: (Campo) -> Unit,
    /** o menu pedido pelo adb: "gaveta" ou o nome de uma aba; volta a null depois de abrir */
    menuPedido: MutableState<String?>,
) {
    val aparencia by vm.aparencia.collectAsStateWithLifecycle()
    val ajustes by vm.ajustes.collectAsStateWithLifecycle()
    val retrato by vm.retrato.collectAsStateWithLifecycle()
    val ligacao by vm.ligacao.collectAsStateWithLifecycle()
    val historico by vm.historico.collectAsStateWithLifecycle()
    val agentesPc by vm.agentesPc.collectAsStateWithLifecycle()
    val listaHistorico = rememberTransformingLazyColumnState()
    val redonda = LocalConfiguration.current.isScreenRound
    val paginas = rememberPagerState { 2 }
    val lista = rememberTransformingLazyColumnState()
    // a aba aberta no menu; null = a gaveta
    var aba by remember { mutableStateOf<Aba?>(null) }
    val escopo = rememberCoroutineScope()
    val foco = remember { FocusRequester() }
    // a coroa no orbe: um orbe da lista a cada tanto de giro
    val coroa = remember { MutableSharedFlow<Int>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST) }
    val giro = remember { floatArrayOf(0f) }
    // a calibração da sacudida cobre o menu até salvar ou voltar
    var calibrando by remember { mutableStateOf<Calibracao?>(null) }

    // as batidas, com o app aberto e fora das calibrações: fraca troca de orbe
    // (como a coroa), o estalo abre e fecha o live, dois fecham o chat
    BatidasNoPulso(
        ligado = ajustes.batidas && calibrando == null,
        fracaMin = if (ajustes.batidaFraca > 0f) ajustes.batidaFraca else Batida.FRACA_MIN,
        forteMin = if (ajustes.batidaForte > 0f) ajustes.batidaForte else Batida.FORTE_MIN,
        aoDetectar = vm::avisarBatida,
    ) { c ->
        vm.batida((if (c.forca == Forca.FORTE) 2 else 0) + (if (c.vezes >= 2) 1 else 0), podeGravar())
    }
    // as ações de trocar de orbe (dos toques e das batidas) andam a lista como a coroa
    LaunchedEffect(vm) {
        vm.passosLista.collect { passo -> if (paginas.currentPage == 0 && historico == null) coroa.tryEmit(passo) }
    }

    // com a sessão aberta a tela não apaga no meio da conversa
    val view = LocalView.current
    SideEffect { view.keepScreenOn = retrato.visivel }

    // a ponte aceita a fala do relógio: é a hora de pedir o microfone, uma vez só
    val aceitaFala = (ligacao as? Ligacao.Conectada)?.microfone == true
    LaunchedEffect(aceitaFala, ajustes.microfone, ajustes.pediuMicrofone) {
        if (aceitaFala && ajustes.microfone && !ajustes.pediuMicrofone) {
            vm.pediuMicrofone()
            if (!podeGravar()) pedirMicrofone()
        }
    }

    CompositionLocalProvider(
        LocalTema provides aparencia.tema,
        LocalRolando provides (paginas.isScrollInProgress || lista.isScrollInProgress),
    ) {
      Box(Modifier.fillMaxSize().background(Color.Black)) {
        // sem o fundo atrás do orbe, ele acende junto com a entrada do menu
        FundoVidro(
            Modifier.graphicsLayer {
                alpha = if (aparencia.fundo) 1f else (paginas.currentPage + paginas.currentPageOffsetFraction).coerceIn(0f, 1f)
            },
        )
        // com o fundo atrás do orbe, um véu o escurece na página dele e some na entrada do menu
        if (aparencia.fundo) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    alpha = Aparencia.ESCURO * (1 - (paginas.currentPage + paginas.currentPageOffsetFraction)).coerceIn(0f, 1f)
                }.background(Color.Black),
            )
        }
        HorizontalPager(
            state = paginas,
            modifier = Modifier
                .fillMaxSize()
                .onRotaryScrollEvent { e ->
                    val d = e.verticalScrollPixels
                    if (historico != null) {
                        listaHistorico.dispatchRawDelta(d)
                    } else if (paginas.currentPage == 0) {
                        giro[0] += d
                        if (abs(giro[0]) >= GIRO_POR_PASSO) {
                            coroa.tryEmit(giro[0].sign.toInt())
                            giro[0] = 0f
                        }
                    } else {
                        if (aba != null) lista.dispatchRawDelta(d)
                    }
                    true
                }
                .focusRequester(foco)
                .focusable(),
            // as duas páginas ficam montadas: o menu pede as prévias das miniaturas
            // já na abertura, e não nasce no meio do arrasto
            beyondViewportPageCount = 1,
            // assentado no menu, os puxões são da TelaAjustes: o pager não leva ao orbe por conta própria
            userScrollEnabled = paginas.settledPage == 0,
        ) { pagina ->
            if (pagina == 0) {
                TelaOrbe(vm, redonda, podeGravar, abrirAjustes = { escopo.launch { paginas.animateScrollToPage(1) } }, coroa = coroa)
            } else {
                TelaAjustes(
                    vm, lista, aba,
                    abrir = { a ->
                        // cada aba abre do alto
                        if (a != null) escopo.launch { lista.scrollToItem(0) }
                        aba = a
                    },
                    redonda,
                    visivel = paginas.currentPage == 1 || paginas.targetPage == 1,
                    editar, pedirMicrofone, podeGravar,
                    aoOrbe = { escopo.launch { paginas.animateScrollToPage(0) } },
                    calibrar = { calibrando = it },
                )
            }
        }
        when (calibrando) {
            Calibracao.ABRIR -> TelaCalibracao(
                Picos(ajustes.sacudidaFora, ajustes.sacudidaDentro), redonda,
                salvar = {
                    vm.calibrarSacudida(it)
                    calibrando = null
                },
                padrao = {
                    vm.sacudidaPadrao()
                    calibrando = null
                },
            )
            Calibracao.SAIR -> TelaCalibracaoSair(
                ajustes.sairFora, redonda,
                salvar = {
                    vm.calibrarSair(it)
                    calibrando = null
                },
                padrao = {
                    vm.calibrarSair(0f)
                    calibrando = null
                },
            )
            Calibracao.FRACA, Calibracao.FORTE -> {
                val forca = if (calibrando == Calibracao.FRACA) Forca.FRACA else Forca.FORTE
                TelaCalibracaoBatida(
                    forca,
                    emUso = if (forca == Forca.FRACA) (if (ajustes.batidaFraca > 0f) "%.1f m/s²".format(ajustes.batidaFraca) else "o padrão")
                    else (if (ajustes.batidaForte > 0f) "%.1f m/s²".format(ajustes.batidaForte) else "o padrão"),
                    redonda,
                    salvar = { p, _ ->
                        if (forca == Forca.FRACA) vm.calibrarFraca(p) else vm.calibrarForte(p)
                        calibrando = null
                    },
                    padrao = {
                        vm.batidasPadrao()
                        calibrando = null
                    },
                )
            }
            null -> Unit
        }
        historico?.let { h ->
            // arrastar para a direita fecha só o histórico e volta ao orbe: sem a
            // caixa, o arraste ia ao sistema, que fechava o app inteiro
            CompositionLocalProvider(
                LocalSwipeToDismissBackgroundScrimColor provides Color.Transparent,
                LocalSwipeToDismissContentScrimColor provides Color.Transparent,
            ) {
                BasicSwipeToDismissBox(onDismissed = vm::fecharHistorico) { fundo ->
                    if (!fundo) {
                        TelaHistorico(h, nomeAgente(ajustes, aparencia.skin, agentesPc), listaHistorico, redonda,
                            retomar = vm::retomar, fechar = vm::fecharHistorico)
                    }
                }
            }
        }
      }
    }
    BackHandler(calibrando != null) { calibrando = null }
    BackHandler(calibrando == null && paginas.currentPage == 1) {
        if (aba != null) aba = null else escopo.launch { paginas.animateScrollToPage(0) }
    }
    BackHandler(historico != null) { vm.fecharHistorico() }
    // cada abertura do histórico começa do alto
    LaunchedEffect(historico == null) { if (historico != null) listaHistorico.scrollToItem(0) }
    LaunchedEffect(Unit) { foco.requestFocus() }
    LaunchedEffect(menuPedido.value) {
        val m = menuPedido.value ?: return@LaunchedEffect
        menuPedido.value = null
        paginas.scrollToPage(1)
        aba = Aba.entries.firstOrNull { it.name.equals(m, ignoreCase = true) }
    }
    // de volta ao orbe, o menu recomeça da gaveta na próxima visita
    LaunchedEffect(paginas.settledPage) {
        if (paginas.settledPage == 0) aba = null
    }
}

/** Pixels de giro da coroa por orbe da lista. */
private const val GIRO_POR_PASSO = 90f

/**
 * O detector de batidas ([Batida]) ligado ao acelerômetro e ao giroscópio no
 * mais rápido enquanto o app está aberto e [ligado]; cada comando vai a [aoComando].
 */
@Composable
private fun BatidasNoPulso(
    ligado: Boolean, fracaMin: Float, forteMin: Float,
    aoDetectar: (forte: Boolean) -> Unit, aoComando: (Comando) -> Unit,
) {
    val ctx = LocalContext.current
    val acao = remember { arrayOf(aoComando) }
    acao[0] = aoComando
    val aviso = remember { arrayOf(aoDetectar) }
    aviso[0] = aoDetectar
    val dono = LocalLifecycleOwner.current
    DisposableEffect(ligado, fracaMin, forteMin, dono) {
        if (!ligado) return@DisposableEffect onDispose { }
        val batida = Batida(fracaMin, forteMin)
        val principal = Handler(Looper.getMainLooper())
        val sensores = ctx.getSystemService(SensorManager::class.java)
        val ouvinte = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = event.values
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        batida.acel(event.timestamp / 1_000_000, x, y, z)
                        for (m in batida.tirarMedidas()) {
                            Log.i("OrbeBatida", "pico %.1f giro %.1f largura %d %s gravidade %.1f %.1f %.1f".format(
                                m.pico, m.giro, m.largura, m.forca, m.gravidade[0], m.gravidade[1], m.gravidade[2]))
                            principal.post { aviso[0](m.forca == Forca.FORTE) }
                        }
                        for ((p, g) in batida.tirarPosGiros()) Log.i("OrbeBatida", "pós: pico %.1f giro nos 200 ms %.1f".format(p, g))
                        for (c in batida.tirarComandos()) {
                            Log.i("OrbeBatida", "comando ${c.forca} x${c.vezes}")
                            principal.post { acao[0](c) }
                        }
                    }
                    Sensor.TYPE_GYROSCOPE -> batida.giro(x, y, z)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        // só com o app na frente: pausado, os sensores saem
        val ciclo = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> {
                    batida.zerar()
                    sensores?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensores.registerListener(ouvinte, it, SensorManager.SENSOR_DELAY_FASTEST) }
                    sensores?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensores.registerListener(ouvinte, it, SensorManager.SENSOR_DELAY_FASTEST) }
                }
                Lifecycle.Event.ON_PAUSE -> sensores?.unregisterListener(ouvinte)
                else -> Unit
            }
        }
        dono.lifecycle.addObserver(ciclo)
        onDispose {
            dono.lifecycle.removeObserver(ciclo)
            sensores?.unregisterListener(ouvinte)
        }
    }
}
