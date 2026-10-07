package io.orbe.watch.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import io.orbe.watch.dados.Historico
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * O histórico de sessões do agente do orbe em tela, aberto pelos toques (no
 * padrão, tocar e segurar; aba Gestos): escolher uma retoma a conversa no PC e o orbe
 * passa a falar com ela. No alto as mais recentes, embaixo os projetos (as
 * pastas onde há conversas); tocar num projeto mostra só as dele, e o voltar
 * (ou o arraste para a direita) volta aos projetos. Nos projetos, o voltar
 * fecha sem escolher.
 */
@Composable
fun TelaHistorico(
    historico: Historico,
    agente: String,
    lista: TransformingLazyColumnState,
    redonda: Boolean,
    /** o projeto aberto (o cwd); null, os recentes e a lista dos projetos */
    projeto: String?,
    abrirProjeto: (String) -> Unit,
    retomar: (String) -> Unit,
    modifier: Modifier = Modifier,
    fechar: () -> Unit = {},
) {
    val limite = with(LocalDensity.current) { 48.dp.toPx() }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // por cima do orbe: ser o alvo do toque basta para ele não chegar ao
            // orbe (irmão de baixo); sem consumir, o arraste para a direita fica
            // para a caixa de dispensar (OrbeApp), que volta ao orbe
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            // arrastar para a esquerda também volta ao orbe (o app não fecha)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dx = 0f
                    val arraste = awaitHorizontalTouchSlopOrCancellation(down.id) { ch, sobra ->
                        if (sobra < 0) { ch.consume(); dx += sobra }
                    }
                    if (arraste != null && dx < 0) {
                        horizontalDrag(arraste.id) { ch -> dx += ch.positionChange().x; ch.consume() }
                        if (dx < -limite) fechar()
                    }
                }
            },
    ) {
        FundoVidro()
        val margem = maxWidth * (if (redonda) 0.12f else 0.05f)
        Menu(lista, margem) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Texto("Histórico", Estilo.grupo.copy(fontSize = 13.sp), alinhar = TextAlign.Center)
                    if (agente.isNotEmpty()) Texto(agente, Estilo.subtitulo, cor = Estilo.texto.alfa(0.55f), alinhar = TextAlign.Center)
                }
            }
            when {
                historico.carregando -> linha { Linha("Buscando as sessões…") }
                historico.erro.isNotEmpty() -> linha { Linha(historico.erro) }
                historico.sessoes.isEmpty() -> linha { Linha("Nenhuma sessão") }
                projeto != null -> {
                    val deste = historico.sessoes.filter { chave(it) == projeto }
                    grupo(nomeProjeto(projeto), caminho(projeto))
                    deste.forEach { s ->
                        linha { Linha(s.titulo.ifEmpty { s.id.take(8) }, subtitulo = quando(s.quando), aoClicar = { retomar(s.id) }) }
                    }
                }
                else -> {
                    grupo("Recentes")
                    historico.sessoes.take(RECENTES).forEach { s ->
                        linha {
                            Linha(
                                s.titulo.ifEmpty { s.id.take(8) },
                                subtitulo = listOf(pasta(s.pasta), quando(s.quando)).filter { it.isNotEmpty() }.joinToString(" · "),
                                aoClicar = { retomar(s.id) },
                            )
                        }
                    }
                    // os projetos pela atividade mais recente (a lista já vem da mais nova)
                    val projetos = historico.sessoes.groupBy(::chave)
                    grupo("Projetos", "${projetos.size} com conversas")
                    projetos.forEach { (cwd, l) ->
                        linha {
                            Linha(
                                nomeProjeto(cwd),
                                subtitulo = listOf(
                                    if (l.size == 1) "1 conversa" else "${l.size} conversas",
                                    quando(l.first().quando),
                                ).filter { it.isNotEmpty() }.joinToString(" · "),
                                aoClicar = { abrirProjeto(cwd) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** As mais recentes no alto do histórico, antes dos projetos. */
private const val RECENTES = 4

/** O projeto de uma sessão: o caminho da pasta (ou só o nome, de ponte que não manda o caminho). */
private fun chave(s: io.orbe.watch.dados.SessaoPassada) = s.cwd.ifEmpty { s.pasta }

/** O nome do projeto: a última pasta do caminho; a pasta pessoal é "~". */
private fun nomeProjeto(cwd: String): String {
    val c = caminho(cwd)
    return if (c == "~") "~" else c.trimEnd('/').substringAfterLast('/').ifEmpty { c }
}

/** O caminho com a pasta pessoal abreviada (Linux e macOS). */
private fun caminho(cwd: String): String = cwd.replace(Regex("^/(home|Users)/[^/]+"), "~")

/** A pasta como no rótulo da sessão: com a barra na frente. */
private fun pasta(p: String): String = if (p.isEmpty() || p.startsWith("/")) p else "/$p"

/** Há quanto tempo, curto: "há 5 min", "ontem", "3 out". */
private fun quando(s: Long): String {
    if (s <= 0) return ""
    val d = System.currentTimeMillis() / 1000 - s
    return when {
        d < 60 -> "agora"
        d < 3600 -> "há ${d / 60} min"
        d < 86400 -> "há ${d / 3600} h"
        d < 2 * 86400 -> "ontem"
        else -> SimpleDateFormat("d MMM", Locale.forLanguageTag("pt-BR")).format(Date(s * 1000)).trimEnd('.')
    }
}
