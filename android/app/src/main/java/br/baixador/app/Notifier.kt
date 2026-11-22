package br.baixador.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat

/** Notificações de "concluído" / "falhou" (uma por download, somem ao tocar). */
object Notifier {
    private const val CHANNEL = "concluidos"

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun id(item: DownloadItem) = 1000 + (item.id.hashCode() and 0x7fffffff) % 1_000_000

    private fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26 && nm(ctx).getNotificationChannel(CHANNEL) == null) {
            nm(ctx).createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads concluídos", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun openApp(ctx: Context, req: Int) = PendingIntent.getActivity(
        ctx, req,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun done(ctx: Context, item: DownloadItem) = safe {
        channel(ctx)
        val n = id(item)
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download concluído")
            .setContentText(item.title)
            .setAutoCancel(true)
            .addAction(0, "Ver no Baixador", openApp(ctx, n + 1))
        val uri = item.fileUri
        if (uri != null) {
            val file = PendingIntent.getActivity(
                ctx, n,
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(uri), item.mime ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.setContentIntent(file).addAction(0, "Abrir", file)
        } else {
            b.setContentIntent(openApp(ctx, n))
        }
        nm(ctx).notify(n, b.build())
    }

    fun failed(ctx: Context, item: DownloadItem) = safe {
        channel(ctx)
        val n = id(item)
        val text = "${item.title}\n${item.status}"
        nm(ctx).notify(
            n,
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Falha no download")
                .setContentText(item.status)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx, n))
                .build(),
        )
    }

    fun cancel(ctx: Context, item: DownloadItem) = safe { nm(ctx).cancel(id(item)) }

    // sem permissão de notificação (Android 13+) o sistema só ignora; o try é por garantia
    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Exception) { }
    }
}
