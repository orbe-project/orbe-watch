package io.orbe.watch

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import io.orbe.watch.gesto.ServicoSacudida

/**
 * O orbe e a Hina (HinaWatch) dividem o relógio: quem está na tela fica, e o
 * outro não abre por cima dele sozinho (pela sacudida ou, no orbe, pela
 * resposta que o traz de volta). Cada app responde num provider se está na
 * tela; alarme e notificação não passam por aqui e abrem como sempre.
 */
object Vizinhos {
    private const val TAG = "Orbe"
    /** o provider da Hina; sem ela instalada (ou numa versão sem ele), a consulta dá falso */
    private val HINA = Uri.parse("content://com.projecthina.hina.tela")

    /** A Hina está na tela agora (a MainActivity dela entre o onStart e o onStop). */
    fun hinaNaFrente(c: Context): Boolean = try {
        c.contentResolver.query(HINA, null, null, null, null)?.use { it.moveToFirst() && it.getInt(0) == 1 } ?: false
    } catch (e: Exception) {
        Log.d(TAG, "Hina: ${e.message}")
        false
    }
}

/** Responde à Hina se o orbe está na tela: content://io.orbe.watch.tela, uma linha com em_tela (0 ou 1). */
class TelaProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf("em_tela")).apply { addRow(arrayOf(if (ServicoSacudida.orbeNaFrente) 1 else 0)) }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
