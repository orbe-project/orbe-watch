package io.orbe.watch

import io.orbe.watch.orbe.OrbeCena
import io.orbe.watch.orbe.Skin
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.sin

/**
 * O limite do laço das línguas no relógio (nLingua, do Anel para o anel.frag):
 * acima dele só há línguas apagadas, que o shader já pulava, então o desenho
 * com o limite é o mesmo de antes.
 */
class AnelLinguasTest {
    @Test
    fun limiteNuncaCortaUmaLinguaAtiva() {
        var comLinguas = 0
        var semLinguas = 0
        for (estado in listOf("idle", "listening", "speaking", "thinking")) {
            val cena = OrbeCena(entrar = false).apply {
                skin = Skin.ANEL; redonda = true; tamanho = 1.05
                if (estado != "idle") comando("show $estado")
            }
            repeat(300) { i ->
                when (estado) {
                    "speaking" -> cena.comando("level %.3f %.3f".format(Locale.ROOT, 0.45 + 0.4 * sin(i * 1.1), 0.5 + 0.3 * sin(i * 0.37)))
                    "listening" -> cena.comando("mic %.3f".format(Locale.ROOT, 0.3 + 0.3 * sin(i * 0.9)))
                }
                val fx = cena.passo(1.0 / 30, 227.0, 227.0).fx
                val n = fx["nLingua"]!![0].toInt()
                val ponta = { k: Int -> fx["lingua$k"]?.get(2) ?: 0f }
                for (k in n until 10) assertTrue("$estado, quadro $i: língua $k ativa acima do limite $n", ponta(k) <= 0f)
                if (n > 0) {
                    assertTrue("$estado, quadro $i: o limite $n passa da última ativa", ponta(n - 1) > 0f)
                    comLinguas++
                } else {
                    semLinguas++
                }
            }
        }
        // os dois casos passaram pelo teste: com línguas (falando) e sem (parado)
        assertTrue(comLinguas > 0 && semLinguas > 0)
    }
}
