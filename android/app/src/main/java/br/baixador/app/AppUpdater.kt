package br.baixador.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização do app: o GitHub Actions publica em dev-latest o APK e um version.json.
 * Ao abrir, compara o versionCode de lá com o instalado; se for maior, pergunta,
 * baixa o APK e abre o instalador do Android.
 */
object AppUpdater {
    private const val BASE = "https://github.com/arthur-tech-projetos/baixador-android/releases/download/dev-latest/"

    data class Info(val code: Long, val name: String, val notes: String)

    var available by mutableStateOf<Info?>(null)
    var downloading by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    private var checked = false

    private fun installedCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Baixador-Updater")
        return c
    }

    /** Uma vez por abertura do app. Falha silenciosa (sem internet etc.). */
    suspend fun check(ctx: Context) {
        if (checked) return
        checked = true
        val info = withContext(Dispatchers.IO) {
            try {
                val c = open(BASE + "version.json")
                val txt = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
                val j = JSONObject(txt)
                val code = j.getLong("versionCode")
                if (code > installedCode(ctx)) Info(code, j.optString("versionName"), j.optString("notes").trim()) else null
            } catch (_: Exception) {
                null
            }
        }
        if (info != null) available = info
    }

    suspend fun downloadAndInstall(ctx: Context) {
        // Android 8+: o usuário precisa liberar "instalar apps desconhecidos" para o Baixador (uma vez só)
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(ctx, "Ative \"Permitir desta fonte\" e depois toque em Atualizar de novo.", Toast.LENGTH_LONG).show()
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        downloading = true
        progress = 0f
        try {
            val apk = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
                val f = File(dir, "Baixador.apk")
                val c = open(BASE + "Baixador-debug.apk")
                try {
                    val total = c.contentLengthLong
                    c.inputStream.use { inp ->
                        f.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var got = 0L
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                if (total > 0) progress = got.toFloat() / total
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
                f
            }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(ctx, "Falha ao baixar a atualização: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            downloading = false
        }
    }
}

@Composable
fun UpdateDialog() {
    val info = AppUpdater.available ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(8.dp)
    AlertDialog(
        onDismissRequest = { if (!AppUpdater.downloading) AppUpdater.available = null },
        containerColor = Color.White,
        title = { Text("Atualização disponível", fontWeight = FontWeight.Bold, color = Ink) },
        text = {
            Column {
                Text("Versão ${info.name} do Baixador.", color = Ink, fontSize = 14.sp)
                if (info.notes.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                        Text(info.notes, color = Mute, fontSize = 13.sp)
                    }
                }
                if (AppUpdater.downloading) {
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Track)) {
                        Box(Modifier.fillMaxWidth(AppUpdater.progress.coerceIn(0f, 1f)).fillMaxHeight().background(Acc))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Baixando... ${(AppUpdater.progress * 100).toInt()}%", color = Mute, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { scope.launch { AppUpdater.downloadAndInstall(ctx) } },
                enabled = !AppUpdater.downloading,
                shape = shape,
                colors = ButtonDefaults.buttonColors(containerColor = Acc),
            ) { Text("Atualizar") }
        },
        dismissButton = {
            TextButton(onClick = { AppUpdater.available = null }, enabled = !AppUpdater.downloading) {
                Text("Depois", color = Mute)
            }
        },
    )
}
