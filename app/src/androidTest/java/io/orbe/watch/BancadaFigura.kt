package io.orbe.watch

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.GLES30
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.orbe.watch.gl.Oficina
import io.orbe.watch.orbe.Estado
import io.orbe.watch.orbe.Figura
import io.orbe.watch.orbe.Skin
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A bancada das figuras de imagem (o Rei dos Ratos, o Paranoia): os dois passes
 * do Motor (a máscara do imagem.frag num FBO e a cor do pos.frag por cima) na
 * GPU do relógio, fora da tela, do tamanho do mostrador e em resolução cheia.
 * Mede a máscara quadro a quadro com glFinish, falando, e grava alguns quadros
 * (a máscara e a cor) em PNG para comparar com o render do PC.
 *
 *   ./gradlew -Pbancada :app:assembleBancada :app:assembleBancadaAndroidTest
 *   adb install -r app/build/outputs/apk/bancada/app-bancada.apk
 *   adb install -r app/build/outputs/apk/androidTest/bancada/app-bancada-androidTest.apk
 *   adb shell am instrument -w -e class io.orbe.watch.BancadaFigura io.orbe.watch.bancada.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s Bancada
 *   adb exec-out run-as io.orbe.watch.bancada cat files/bancada/humana_cor_75.png > humana_cor_75.png
 */
@RunWith(AndroidJUnit4::class)
class BancadaFigura {
    private val lado = 454             // o mostrador do TicWatch Pro 3, em px

    @Test
    fun reiDosRatos() = bancada(Skin.HUMANA)

    @Test
    fun paranoia() = bancada(Skin.OLHO)

    /**
     * O Rei dos Ratos por partes: o custo da máscara sem cada laço do imagem.frag
     * (as chaves SEM_* só existem para isto), e a máscara de cada variante.
     */
    @Test
    fun reiPorPartes() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val egl = abrirEgl()
        try {
            val of = Oficina(ctx)
            val atlas = of.textura(Skin.HUMANA)
            val mascara = alvo()
            val quad = quadrado()
            val w = lado / ctx.resources.displayMetrics.density.toDouble()
            val pasta = File(ctx.filesDir, "bancada").apply { mkdirs() }
            val variantes = listOf(
                "tudo" to emptyMap(),
                "sem_membros" to mapOf("SEM_MEMBROS" to 1),
                "sem_corpos" to mapOf("SEM_CORPOS" to 1),
                "sem_cabecas" to mapOf("SEM_CABECAS" to 1),
                "so_miolo" to mapOf("SEM_MEMBROS" to 1, "SEM_CORPOS" to 1, "SEM_CABECAS" to 1),
            ).filter { (nome, _) -> SO.isEmpty() || nome in SO }
            for ((nome, v) in variantes) {
                val figura = of.figura(Skin.HUMANA, v)
                assertTrue("a variante $nome não compilou", figura != null)
                val fig = Figura(Skin.HUMANA).apply { glitch = false }
                val ms = FloatArray(12)
                var t = 0.0
                for (i in 0 until 4 + ms.size) {
                    t += 1.0 / 30
                    falar(fig, t)
                    fig.avancar(1.0 / 30)
                    fig.montar(w, w, w / 2, w / 2, w, w)
                    val t0 = System.nanoTime()
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mascara[1])
                    GLES30.glViewport(0, 0, lado, lado)
                    GLES30.glClearColor(0f, 0f, 0f, 0f)
                    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                    GLES30.glDisable(GLES30.GL_BLEND)
                    GLES30.glUseProgram(figura!!.id)
                    figura.aplicar(fig.fx)
                    figura.v1("qt_Opacity", 1f)
                    figura.v4("uvT", 1f, 1f, 0f, 0f)
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlas)
                    figura.amostrador("arte", 0)
                    GLES30.glBindVertexArray(quad)
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                    GLES30.glFinish()
                    if (i >= 4) ms[i - 4] = (System.nanoTime() - t0) / 1e6f
                }
                salvar(mascara[1], File(pasta, "rei_$nome.png"))
                ms.sort()
                Log.i(TAG, "rei %-12s máscara: mediana %7.1f ms  max %7.1f ms".format(nome, ms[ms.size / 2], ms[ms.size - 1]))
            }
        } finally {
            EGL14.eglMakeCurrent(egl[0] as android.opengl.EGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        }
    }

    private fun bancada(skin: Skin) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val egl = abrirEgl()
        try {
            val of = Oficina(ctx)
            val figura = of.figura(skin)
            val pos = of.pos()
            assertTrue("o ${skin.id} não compilou", figura != null && pos != null)
            val atlas = of.textura(skin)
            assertTrue("atlas do ${skin.id}", atlas != 0)
            val mascara = alvo()
            val cor = alvo()
            val quad = quadrado()
            val w = lado / ctx.resources.displayMetrics.density.toDouble()
            // na pasta interna: o shell do Android 11 não lê a externa do app; sai com run-as
            val pasta = File(ctx.filesDir, "bancada").apply { mkdirs() }
            Log.i(TAG, "GPU: ${GLES30.glGetString(GLES30.GL_RENDERER)}; ${skin.id} ${lado}x$lado px")

            for (glitch in listOf(false, true)) {
                val fig = Figura(skin).apply { this.glitch = glitch }
                val ms = FloatArray(N_MEDIDOS)
                var t = 0.0
                for (i in 0 until N_AQUECER + N_MEDIDOS) {
                    t += 1.0 / 30
                    falar(fig, t)
                    fig.avancar(1.0 / 30)
                    fig.montar(w, w, w / 2, w / 2, w, w)
                    val t0 = System.nanoTime()
                    // ── primeiro passe: a máscara ──
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mascara[1])
                    GLES30.glViewport(0, 0, lado, lado)
                    GLES30.glClearColor(0f, 0f, 0f, 0f)
                    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                    GLES30.glDisable(GLES30.GL_BLEND)
                    GLES30.glUseProgram(figura!!.id)
                    figura.aplicar(fig.fx)
                    figura.v1("qt_Opacity", 1f)
                    figura.v4("uvT", 1f, 1f, 0f, 0f)
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlas)
                    figura.amostrador("arte", 0)
                    figura.amostrador("atlas", 0)
                    GLES30.glBindVertexArray(quad)
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                    GLES30.glFinish()
                    if (i >= N_AQUECER) ms[i - N_AQUECER] = (System.nanoTime() - t0) / 1e6f
                    // ── segundo passe: a cor ──
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, cor[1])
                    GLES30.glViewport(0, 0, lado, lado)
                    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                    GLES30.glEnable(GLES30.GL_BLEND)
                    GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                    GLES30.glUseProgram(pos!!.id)
                    pos.aplicar(fig.pos)
                    pos.v1("qt_Opacity", 1f)
                    pos.v4("uvT", 1f, 1f, 0f, 0f)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mascara[0])
                    pos.amostrador("source", 0)
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                    if (!glitch && i in QUADROS_SALVOS) {
                        salvar(mascara[1], File(pasta, "${skin.id}_mascara_$i.png"))
                        salvar(cor[1], File(pasta, "${skin.id}_cor_$i.png"))
                    }
                }
                ms.sort()
                Log.i(TAG, "%-8s glitch=%-5s máscara: mediana %5.1f ms  p90 %5.1f ms  max %5.1f ms".format(
                    skin.id, glitch, ms[ms.size / 2], ms[ms.size * 9 / 10], ms[ms.size - 1]))
            }
        } finally {
            EGL14.eglMakeCurrent(egl[0] as android.opengl.EGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        }
    }

    /** Falando, com a voz subindo e descendo como numa fala (as peças levam os chutes das sílabas). */
    private fun falar(fig: Figura, t: Double) {
        java.util.Arrays.fill(fig.mix, 0.0)
        fig.mix[Estado.SPEAKING] = 1.0
        fig.voz = 0.45 + 0.35 * sin(t * 7.0) * sin(t * 2.3)
    }

    /** O FBO lido para um Bitmap (já pré-multiplicado, como o RGBA do GL) e gravado em PNG. */
    private fun salvar(fbo: Int, arquivo: File) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        val b = ByteBuffer.allocateDirect(lado * lado * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, lado, lado, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, b)
        b.position(0)
        val bmp = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(b)
        arquivo.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        Log.i(TAG, "gravado ${arquivo.absolutePath}")
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

    /** Textura e FBO do tamanho do mostrador: [textura, fbo]. */
    private fun alvo(): IntArray {
        val ids = IntArray(2)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, lado, lado, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glGenFramebuffers(1, ids, 1)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ids[1])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, ids[0], 0)
        return ids
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

    private companion object {
        const val TAG = "Bancada"
        const val N_AQUECER = 30
        const val N_MEDIDOS = 90
        val QUADROS_SALVOS = setOf(45, 75, 105)
        /** as variantes do reiPorPartes a rodar (vazio: todas); -e so "ids,so_miolo" na linha do instrument */
        val SO: Set<String> get() = (InstrumentationRegistry.getArguments().getString("so") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
