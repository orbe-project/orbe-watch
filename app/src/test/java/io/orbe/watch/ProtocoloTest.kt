package io.orbe.watch

import io.orbe.watch.dados.Ajustes
import io.orbe.watch.dados.Protocolo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocoloTest {
    @Test
    fun enderecoDeCasaVaiPeloWifi() {
        // o que se digita no relógio, com e sem esquema e porta
        for (s in listOf("192.168.15.7", "ws://192.168.15.7:8777", "http://10.0.0.2/", "172.20.1.1:9000", "pc.lan", "pc.local:8777", "localhost")) {
            assertTrue(s, Protocolo.local(s))
        }
    }

    @Test
    fun tailnetEInternetSaemPeloCelular() {
        // a tailnet só responde pelo celular (o relógio não tem Tailscale)
        for (s in listOf("100.95.140.97", "wss://acer-server.tail664e38.ts.net", "8.8.8.8:8777", "172.32.0.1", "", "192.168.15")) {
            assertFalse(s, Protocolo.local(s))
        }
    }

    @Test
    fun aSessaoTrazOTituloEAPasta() {
        val l = Protocolo.sessoes("""[{"vaga": 1, "pid": 9, "rotulo": "davi-2c", "titulo": "Orbe no PC travando", "pasta": "davi", "estado": "parada", "ouve": true}]""")!!
        assertEquals("Orbe no PC travando", l[0].titulo)
        assertEquals("davi", l[0].pasta)
        // a ponte antiga só manda o rótulo
        assertEquals("", Protocolo.sessoes("""[{"vaga": 0, "pid": 9, "rotulo": "davi-2c"}]""")!![0].titulo)
    }

    @Test
    fun aSessaoSemAgenteEDoClaude() {
        // a ponte antiga só conta as do Claude e não diz o agente
        assertEquals("claude", Protocolo.sessoes("""[{"vaga": 0, "pid": 9}]""")!![0].agente)
        assertEquals("opencode", Protocolo.sessoes("""[{"agente": "opencode", "vaga": 0, "pid": 9}]""")!![0].agente)
    }

    @Test
    fun oAgenteDizSeTemInstancias() {
        val ola = Protocolo.ola("""{"agentes": [{"id": "claude", "nome": "Claude Code", "instancias": true}, {"id": "hermes", "nome": "Hermes"}]}""")!!
        assertEquals(listOf(true, false), ola.agentes.map { it.instancias })
    }

    @Test
    fun asEtapasVemDesligadasEOIdiomaDesconhecidoNaoEntra() {
        assertFalse(Ajustes().sincronia().etapas)
        val pc = Protocolo.sincronia("""{"t": 5, "etapas": true, "idioma_etapas": "original"}""")!!
        val aqui = Ajustes().com(pc)
        assertTrue(aqui.etapas)
        assertEquals("original", aqui.idiomaEtapas)
        assertEquals("original", aqui.com(Protocolo.sincronia("""{"t": 6, "etapas": true, "idioma_etapas": "klingon"}""")!!).idiomaEtapas)
        assertTrue(Protocolo.ajustes(aqui.sincronia()).contains("\"idioma_etapas\":\"original\""))
    }

    @Test
    fun focoEEsperaDosOrbesEmParalelo() {
        val f = Protocolo.foco("""{"skin": "ofanim_alado", "agente": "claude", "vaga": 7}""")!!
        assertEquals("ofanim_alado", f.skin)
        assertEquals(7, f.vaga)
        val e = Protocolo.esperas("""[{"skin": "ofanim", "vaga": 3, "cor": "#4DD0E1"}, {"skin": "anel", "vaga": -1, "cor": ""}]""")!!
        assertEquals(listOf("ofanim", "anel"), e.map { it.skin })
        assertEquals("#4DD0E1", e[0].cor)
        assertEquals(emptyList<Any>(), Protocolo.esperas("[]"))
    }

    @Test
    fun oOlaTrazAImagemDoFundo() {
        val ola = Protocolo.ola("""{"v": 1, "papel": ["#000000", "#ffffff", "#000000", "#ffffff"], "papel_imagem": "/9j/4AAQ", "papel_id": "ab12"}""")!!
        assertEquals("/9j/4AAQ", ola.papelImagem)
        assertEquals("ab12", ola.papelId)
        // a ponte antiga não manda: segue o papel do PC
        assertEquals("", Protocolo.ola("""{"v": 1}""")!!.papelImagem)
    }
}
