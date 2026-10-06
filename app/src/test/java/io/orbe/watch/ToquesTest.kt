package io.orbe.watch

import io.orbe.watch.dados.AcaoToque
import io.orbe.watch.dados.Ajustes
import io.orbe.watch.dados.Sincronia
import io.orbe.watch.gesto.Sacudida
import io.orbe.watch.orbe.Skin
import org.junit.Assert.assertEquals
import org.junit.Test

class ToquesTest {
    @Test
    fun osToquesDePadraoSaoOsDeSempre() {
        val a = Ajustes()
        assertEquals(AcaoToque.ABRIR, a.toque(1))
        assertEquals(AcaoToque.LIVE, a.toque(2))
        assertEquals(AcaoToque.ENCERRAR, a.toque(3))
        assertEquals(AcaoToque.NADA, a.toque(4))
        // três toques encerram na hora, sem esperar um quarto
        assertEquals(3, a.maisToques())
    }

    @Test
    fun oMaiorToqueComAcaoDecideAEspera() {
        val historico = Ajustes(toques = listOf(AcaoToque.ABRIR, AcaoToque.LIVE, AcaoToque.ENCERRAR, AcaoToque.HISTORICO))
        assertEquals(4, historico.maisToques())
        val nenhum = Ajustes(toques = List(4) { AcaoToque.NADA })
        assertEquals(0, nenhum.maisToques())
        assertEquals(AcaoToque.NADA, nenhum.toque(7))
    }

    @Test
    fun oQueOPcNaoConheceFicaComoEstaAqui() {
        val aqui = Ajustes(
            toques = listOf(AcaoToque.LIVE, AcaoToque.ABRIR, AcaoToque.NADA, AcaoToque.HISTORICO),
            ordem = listOf("anel", "ofanim"), fundo = false, sacudidaFora = 6.2f, sairFora = 5f,
        )
        val depois = aqui.com(Sincronia(t = 10, voz = false))
        assertEquals(aqui.toques, depois.toques)
        assertEquals(aqui.ordem, depois.ordem)
        assertEquals(false, depois.fundo)
        assertEquals(6.2f, depois.sacudidaFora)
        assertEquals(5f, depois.sairFora)
        assertEquals(false, depois.voz)
    }

    @Test
    fun oPcTrocaOsToquesEVoltaACalibracaoAoPadrao() {
        val aqui = Ajustes(sacudidaFora = 6.2f, sacudidaDentro = 9f, sairFora = 5f)
        val depois = aqui.com(
            Sincronia(t = 10, toques = listOf("historico", "nao_existe"), sacudidaFora = 0f, sacudidaDentro = 0f, sairFora = 0f),
        )
        // o que não se reconhece fica como estava
        assertEquals(listOf(AcaoToque.HISTORICO, AcaoToque.LIVE, AcaoToque.ENCERRAR, AcaoToque.NADA), depois.toques)
        assertEquals(Sacudida.FORA_MIN, depois.sacudidaFora)
        assertEquals(Sacudida.DENTRO_MIN, depois.sacudidaDentro)
        assertEquals(0f, depois.sairFora)
    }

    @Test
    fun aSincroniaLevaTudoQueORelogioConfigura() {
        val s = Ajustes(live = true, sacudida = false).sincronia()
        assertEquals(listOf("abrir", "live", "encerrar", "nada"), s.toques)
        assertEquals(true, s.live)
        assertEquals(false, s.sacudida)
        assertEquals(Skin.entries.size, s.ordem?.size)
    }
}
