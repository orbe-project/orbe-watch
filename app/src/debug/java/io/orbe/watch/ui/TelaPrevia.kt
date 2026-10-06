package io.orbe.watch.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.orbe.watch.dados.SessaoInfo
import io.orbe.watch.orbe.Instancias
import io.orbe.watch.orbe.Skin

// As prévias da tela do orbe no Android Studio (só no debug). O orbe em GL não
// roda na prévia: no lugar dele entra a skin parada (previa_<skin>, renderizada
// pelo orbe-qt), e as miniaturas também. O resto é o desenho de verdade: o
// fundo, o rótulo curvado com as instâncias ativas antes da pasta e o orbe que
// espera a vez no canto de baixo à direita.

private fun cor(instancia: Int, tema: Tema): Color =
    Instancias.cores[Instancias.cor(instancia)]?.let { Color(it[0], it[1], it[2]) } ?: tema.accent

@Composable
private fun TelaDeExemplo(skin: Skin, instancia: Int, ativas: List<Int>, esperando: Pair<Skin, Int>?, comImagem: Boolean) {
    val ctx = LocalContext.current
    @Suppress("DiscouragedApi")
    val idFundo = ctx.resources.getIdentifier("previa_fundo", "drawable", ctx.packageName)
    val imagem = if (comImagem && idFundo != 0) BitmapFactory.decodeResource(ctx.resources, idFundo)?.asImageBitmap() else null
    val tema = Tema.Padrao.copy(imagem = imagem)
    CompositionLocalProvider(LocalTema provides tema) {
        Box(Modifier.fillMaxSize().clip(CircleShape)) {
            FundoVidro()
            SkinParada(skin, cor(instancia, tema), Modifier.align(Alignment.Center).size(150.dp))
            val miniaturas = ativas.map { k ->
                ChavePrevia(Papel.INSTANCIA, skin, 14.dp, 14.dp, glitch = false, linhas = false, cor(k, tema), tema.anel, tema.fundo) to (k == instancia)
            }
            RotuloSessao(
                SessaoInfo(vaga = instancia, titulo = "Implementar orbs do Projetos", pasta = "davi", canal = true),
                abreClaude = true, agente = "Claude Code", cor = cor(instancia, tema), comPe = true,
                modifier = Modifier.fillMaxSize(), miniaturas = miniaturas,
            )
            esperando?.let { (s, k) ->
                Miniatura(
                    ChavePrevia(Papel.INSTANCIA, s, 26.dp, 26.dp, glitch = false, linhas = false, cor(k, tema), tema.anel, tema.fundo),
                    Modifier.align(Alignment.Center).offset(x = 63.dp, y = 63.dp).size(26.dp),
                )
            }
        }
    }
}

@Preview(name = "Paranoia, 3 instâncias, fundo escolhido", device = "id:wearos_large_round", showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviaOlho() = TelaDeExemplo(Skin.OLHO, instancia = 0, ativas = listOf(0, 1, 2), esperando = null, comImagem = true)

@Preview(name = "Rei dos Ratos, com o Ophanim esperando a vez", device = "id:wearos_large_round", showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviaHumanaEsperando() = TelaDeExemplo(Skin.HUMANA, instancia = 1, ativas = listOf(0, 1), esperando = Skin.OFANIM to 1, comImagem = false)

@Preview(name = "Ophanim, sem imagem de fundo", device = "id:wearos_large_round", showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviaOfanim() = TelaDeExemplo(Skin.OFANIM, instancia = 0, ativas = listOf(0), esperando = Skin.OLHO to 0, comImagem = false)
