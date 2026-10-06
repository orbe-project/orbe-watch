package io.orbe.watch

import android.opengl.EGL14
import android.opengl.GLES30
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.orbe.watch.gl.Oficina
import io.orbe.watch.gl.Programa
import io.orbe.watch.gl.Sombreador
import io.orbe.watch.orbe.Anel
import io.orbe.watch.orbe.Estado
import io.orbe.watch.orbe.Skin
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A bancada do anel: o primeiro passe (anel.frag) na GPU do relógio, fora da
 * tela (pbuffer + FBO do tamanho do mostrador), com os uniforms do Anel de
 * verdade, medido quadro a quadro com glFinish, parado e falando. Compara o
 * anel.frag de agora (com a variante que o Motor escolhe) com o de referência
 * (assets/anel_referencia.frag, o do último commit), em tempo e em pixels.
 *
 *   ./gradlew -Pbancada :app:assembleBancada :app:assembleBancadaAndroidTest
 *   adb install -r app/build/outputs/apk/bancada/app-bancada.apk
 *   adb install -r app/build/outputs/apk/androidTest/bancada/app-bancada-androidTest.apk
 *   adb shell am instrument -w -e class io.orbe.watch.BancadaAnel io.orbe.watch.bancada.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s Bancada
 */
@RunWith(AndroidJUnit4::class)
class BancadaAnel {
    private val lado = 454             // o mostrador do TicWatch Pro 3, em px

    private class Programas(val referencia: Programa, val novo: Programa, val semLinguas: Programa) {
        /** O que o Motor usa no quadro: a variante que a arte pede. */
        fun doQuadro(anel: Anel) = if (anel.variante.isEmpty()) novo else semLinguas
    }

    /** O anel.frag de agora, com e sem SEM_LINGUAS, e o de referência (o do último commit, nos assets da bancada). */
    private fun programas(): Programas {
        val inst = InstrumentationRegistry.getInstrumentation()
        val fonte = inst.targetContext.assets.open("shaders/anel.frag").bufferedReader().use { it.readText() }
        val referencia = inst.context.assets.open("anel_referencia.frag").bufferedReader().use { it.readText() }
        return Programas(
            Programa(ligar(Sombreador.VERTICE, Sombreador.paraEs(referencia))),
            Programa(ligar(Sombreador.VERTICE, Sombreador.paraEs(fonte))),
            Programa(ligar(Sombreador.VERTICE, Sombreador.paraEs(fonte, mapOf("SEM_LINGUAS" to 1)))),
        )
    }

    @Test
    fun anel() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val egl = abrirEgl()
        try {
            val atlas = Oficina(ctx).textura(Skin.ANEL)
            assertTrue("atlas do anel", atlas != 0)
            val ps = programas()
            val fbo = fbo()
            val quad = quadrado()
            val w = lado / ctx.resources.displayMetrics.density.toDouble()
            Log.i(TAG, "GPU: ${GLES30.glGetString(GLES30.GL_RENDERER)}; ${lado}x$lado px")
            for (estado in listOf("parado", "falando")) {
                for ((nome, escolher) in listOf<Pair<String, (Anel) -> Programa>>(
                    "referencia" to { _ -> ps.referencia },
                    "novo" to ps::doQuadro,
                )) {
                    val ms = medir(escolher, Anel(), estado, atlas, fbo, quad, w)
                    Log.i(TAG, "%-10s %-8s mediana %5.1f ms  p90 %5.1f ms  max %5.1f ms".format(
                        nome, estado, ms[ms.size / 2], ms[ms.size * 9 / 10], ms[ms.size - 1]))
                }
            }
        } finally {
            EGL14.eglMakeCurrent(egl[0] as android.opengl.EGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        }
    }

    /**
     * O anel de agora tem que desenhar o mesmo que o de referência: os mesmos
     * quadros (os mesmos uniforms), parado e falando, comparados byte a byte.
     */
    @Test
    fun mesmoDesenhoQueAReferencia() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val egl = abrirEgl()
        try {
            val atlas = Oficina(ctx).textura(Skin.ANEL)
            val ps = programas()
            val fbo = fbo()
            val quad = quadrado()
            val w = lado / ctx.resources.displayMetrics.density.toDouble()
            val a = ByteBuffer.allocateDirect(lado * lado * 4)
            val b = ByteBuffer.allocateDirect(lado * lado * 4)
            var pior = 0
            for (estado in listOf("parado", "falando")) {
                val anel = Anel()
                var quadros = 0
                var diferentes = 0L
                var maior = 0
                var t = 0.0
                for (i in 0 until 300) {
                    t += 1.0 / 30
                    estado(anel, estado, t)
                    anel.avancar(1.0 / 30)
                    anel.montar(w, w, w / 2, w / 2, w, w)
                    if (i % 10 != 9) continue
                    desenhar(ps.referencia, anel, atlas, fbo, quad)
                    ler(a)
                    desenhar(ps.doQuadro(anel), anel, atlas, fbo, quad)
                    ler(b)
                    quadros++
                    for (k in 0 until lado * lado * 4) {
                        val d = kotlin.math.abs((a.get(k).toInt() and 0xff) - (b.get(k).toInt() and 0xff))
                        if (d > 0) diferentes++
                        if (d > maior) maior = d
                    }
                }
                Log.i(TAG, "desenho $estado: $quadros quadros, $diferentes bytes diferentes de ${quadros.toLong() * lado * lado * 4}, maior diferença $maior")
                pior = maxOf(pior, maior)
            }
            assertTrue("o desenho mudou (maior diferença $pior)", pior <= 2)
        } finally {
            EGL14.eglMakeCurrent(egl[0] as android.opengl.EGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        }
    }

    /** Parado, ou falando com a voz subindo e descendo como numa fala (as línguas e as gotas acordam). */
    private fun estado(anel: Anel, estado: String, t: Double) {
        java.util.Arrays.fill(anel.mix, 0.0)
        if (estado == "falando") {
            anel.mix[Estado.SPEAKING] = 1.0
            anel.estado = Estado.SPEAKING
            val v = 0.45 + 0.35 * sin(t * 7.0) * sin(t * 2.3)
            anel.nivel = v
            anel.nivelS = v
        } else {
            anel.mix[Estado.IDLE] = 1.0
            anel.estado = Estado.IDLE
            anel.nivel = 0.0
            anel.nivelS = 0.0
        }
    }

    private fun desenhar(p: Programa, anel: Anel, atlas: Int, fbo: Int, quad: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, lado, lado)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(p.id)
        p.aplicar(anel.fx)
        p.v1("qt_Opacity", 1f)
        p.v4("uvT", 1f, 1f, 0f, 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlas)
        p.amostrador("atlas", 0)
        GLES30.glBindVertexArray(quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun ler(b: ByteBuffer) {
        b.position(0)
        GLES30.glReadPixels(0, 0, lado, lado, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, b)
        b.position(0)
    }

    /** Os tempos ordenados de [N_MEDIDOS] quadros, depois de [N_AQUECER]. */
    private fun medir(escolher: (Anel) -> Programa, anel: Anel, estado: String, atlas: Int, fbo: Int, quad: Int, w: Double): FloatArray {
        val ms = FloatArray(N_MEDIDOS)
        var t = 0.0
        for (i in 0 until N_AQUECER + N_MEDIDOS) {
            t += 1.0 / 30
            estado(anel, estado, t)
            anel.avancar(1.0 / 30)
            anel.montar(w, w, w / 2, w / 2, w, w)
            val t0 = System.nanoTime()
            desenhar(escolher(anel), anel, atlas, fbo, quad)
            GLES30.glFinish()
            if (i >= N_AQUECER) ms[i - N_AQUECER] = (System.nanoTime() - t0) / 1e6f
        }
        ms.sort()
        return ms
    }

    private fun abrirEgl(): Array<Any> {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(d, IntArray(2), 0, IntArray(2), 1)
        val cfgs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(
            d, intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE,
            ), 0, cfgs, 0, 1, n, 0,
        )
        val c = EGL14.eglCreateContext(d, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val s = EGL14.eglCreatePbufferSurface(d, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
        assertTrue("eglMakeCurrent", EGL14.eglMakeCurrent(d, s, s, c))
        return arrayOf(d, c, s)
    }

    private fun fbo(): Int {
        val ids = IntArray(2)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, lado, lado, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glGenFramebuffers(1, ids, 1)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ids[1])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, ids[0], 0)
        return ids[1]
    }

    private fun quadrado(): Int {
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        GLES30.glBindVertexArray(ids[0])
        GLES30.glGenBuffers(1, ids, 1)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[1])
        val v = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val b = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(v).also { it.position(0) }
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, v.size * 4, b, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        return ids[0]
    }

    private fun ligar(vertice: String, fragmento: String): Int {
        fun compilar(tipo: Int, fonte: String): Int {
            val s = GLES30.glCreateShader(tipo)
            GLES30.glShaderSource(s, fonte)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            assertTrue("compilar: ${GLES30.glGetShaderInfoLog(s)}", ok[0] != 0)
            return s
        }
        val id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, compilar(GLES30.GL_VERTEX_SHADER, vertice))
        GLES30.glAttachShader(id, compilar(GLES30.GL_FRAGMENT_SHADER, fragmento))
        GLES30.glLinkProgram(id)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
        assertTrue("link: ${GLES30.glGetProgramInfoLog(id)}", ok[0] != 0)
        return id
    }

    private companion object {
        const val TAG = "Bancada"
        const val N_AQUECER = 30
        const val N_MEDIDOS = 90
    }
}
