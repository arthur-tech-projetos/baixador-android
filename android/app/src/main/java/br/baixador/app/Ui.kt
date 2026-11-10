package br.baixador.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

// ---------- cores e fonte (iguais às do aplicativo para Windows) ----------
val Acc = Color(0xFF2F6BFF)
val Soft = Color(0xFFE8EFFF)
val Line = Color(0xFFE4E8F0)
val Ink = Color(0xFF1B2030)
val Mute = Color(0xFF7A8296)
val Track = Color(0xFFE8ECF4)
val Danger = Color(0xFFD64545)

val Grotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

private fun TextStyle.g() = copy(fontFamily = Grotesk)

private val Typo: Typography = Typography().let { d ->
    Typography(
        displayLarge = d.displayLarge.g(), displayMedium = d.displayMedium.g(), displaySmall = d.displaySmall.g(),
        headlineLarge = d.headlineLarge.g(), headlineMedium = d.headlineMedium.g(), headlineSmall = d.headlineSmall.g(),
        titleLarge = d.titleLarge.g(), titleMedium = d.titleMedium.g(), titleSmall = d.titleSmall.g(),
        bodyLarge = d.bodyLarge.g(), bodyMedium = d.bodyMedium.g(), bodySmall = d.bodySmall.g(),
        labelLarge = d.labelLarge.g(), labelMedium = d.labelMedium.g(), labelSmall = d.labelSmall.g(),
    )
}

@Composable
fun BaixadorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Acc, onPrimary = Color.White,
            background = Color.White, onBackground = Ink,
            surface = Color.White, onSurface = Ink,
        ),
        typography = Typo,
        content = content,
    )
}

private val R8 = RoundedCornerShape(8.dp)
private val R10 = RoundedCornerShape(10.dp)
private val R12 = RoundedCornerShape(12.dp)

// ---------- tela principal ----------
@Composable
fun Root() {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    var tab by remember { mutableIntStateOf(0) }
    var url by remember { mutableStateOf("") }
    val ready by EngineState.ready.collectAsStateWithLifecycle()
    val error by EngineState.error.collectAsStateWithLifecycle()

    LaunchedEffect(Downloads.message) {
        Downloads.message?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            Downloads.message = null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            .systemBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Header(ready, error, Downloads.engineVersion) { Downloads.updateEngine() }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            TabLabel("Baixando", tab == 0) { tab = 0 }
            TabLabel("Concluídos", tab == 1) { tab = 1 }
        }
        Spacer(Modifier.height(12.dp))
        UrlBar(
            value = url,
            onChange = { url = it },
            onPaste = {
                val t = clipboard.getText()?.text.orEmpty().trim()
                if (t.isNotEmpty()) {
                    url = t
                    Downloads.add(t)
                    focus.clearFocus()
                    url = ""
                    tab = 0
                }
            },
            onGo = {
                if (url.isNotBlank()) {
                    Downloads.add(url)
                    focus.clearFocus()
                    url = ""
                    tab = 0
                }
            },
        )
        Spacer(Modifier.height(12.dp))

        val shown = Downloads.items.filter { it.done == (tab == 1) }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (tab == 0) "Nada por aqui ainda.\nCole um link para começar." else "Nenhum download concluído ainda.",
                    color = Mute, textAlign = TextAlign.Center, fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.id }) { ItemCard(it) }
            }
        }
    }

    Downloads.choice?.let { c ->
        FormatDialog(c, onConfirm = { Downloads.choose(it) }, onDismiss = { Downloads.dismissChoice() })
    }
}

@Composable
private fun Header(ready: Boolean, error: String?, version: String?, onUpdate: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(R10).background(Acc),
            contentAlignment = Alignment.Center,
        ) { Text("B", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Baixador", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Ink)
            val s = when {
                error != null -> "Erro ao iniciar o motor"
                !ready -> "Iniciando o motor..."
                else -> "Pronto" + (version?.let { " · yt-dlp $it" } ?: "")
            }
            Text(s, fontSize = 11.sp, color = if (error != null) Danger else Mute)
        }
        OutlinedButton(
            onClick = onUpdate, enabled = ready, shape = R8, border = BorderStroke(1.dp, Line),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) { Text("Atualizar yt-dlp", color = Ink, fontSize = 12.sp) }
    }
}

@Composable
private fun TabLabel(text: String, active: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (active) Ink else Mute)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.height(3.dp).width(if (active) 40.dp else 0.dp)
                .clip(RoundedCornerShape(2.dp)).background(Acc),
        )
    }
}

@Composable
private fun UrlBar(value: String, onChange: (String) -> Unit, onPaste: () -> Unit, onGo: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(R12).border(1.dp, Line, R12).padding(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Cole o link do YouTube, Instagram ou TikTok", color = Mute, fontSize = 13.sp) },
            shape = R10,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Acc, unfocusedBorderColor = Line),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPaste, modifier = Modifier.weight(1f), shape = R8, border = BorderStroke(1.dp, Line)) {
                Text("Colar URL", color = Ink)
            }
            Button(
                onClick = onGo, modifier = Modifier.weight(1f), shape = R8,
                colors = ButtonDefaults.buttonColors(containerColor = Acc),
            ) { Text("Baixar", color = Color.White) }
        }
    }
}

@Composable
private fun ItemCard(item: DownloadItem) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(R10).border(1.dp, Line, R10).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 112.dp, height = 63.dp).clip(R8).background(Track)) {
                if (item.thumbnail != null) {
                    AsyncImage(
                        model = item.thumbnail, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Ink,
                )
                Spacer(Modifier.height(4.dp))
                Text(item.status, color = if (item.failed) Danger else Acc, fontSize = 12.sp)
                if (item.detail.isNotEmpty()) Text(item.detail, color = Mute, fontSize = 12.sp, maxLines = 1)
            }
        }
        if (!item.done && !item.failed) {
            Spacer(Modifier.height(10.dp))
            val bar = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            if (item.percent <= 0f) {
                LinearProgressIndicator(modifier = bar, color = Acc, trackColor = Track)
            } else {
                // barra própria: a do Material 3 desenha uma folga e um pontinho no fim
                Box(bar.background(Track)) {
                    Box(Modifier.fillMaxWidth(item.percent.coerceIn(0f, 1f)).fillMaxHeight().background(Acc))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (item.done && item.fileUri != null) {
                TextButton(onClick = { openFile(ctx, item) }) { Text("Abrir", color = Acc) }
            }
            TextButton(onClick = { Downloads.remove(item) }) {
                Text(if (item.done || item.failed) "Remover" else "Cancelar", color = Mute)
            }
        }
    }
}

private fun openFile(ctx: Context, item: DownloadItem) {
    val uri = Uri.parse(item.fileUri ?: return)
    val i = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, item.mime ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(i)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "Nenhum app para abrir este arquivo.", Toast.LENGTH_SHORT).show()
    }
}

// ---------- janela "Selecionar qualidade e formato" ----------
@Composable
private fun FormatDialog(choice: Downloads.Choice, onConfirm: (Selection) -> Unit, onDismiss: () -> Unit) {
    val info = choice.info
    var isVideo by remember { mutableStateOf(true) }
    var audioFmt by remember { mutableStateOf("MP3") }
    var vIdx by remember { mutableIntStateOf(0) }
    var kbps by remember { mutableIntStateOf(320) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
            Column(Modifier.padding(20.dp)) {
                Text("Selecionar qualidade e formato", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Ink)
                Spacer(Modifier.height(10.dp))
                Text(info.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = Ink)
                info.duration?.let { Text(it, fontSize = 12.sp, color = Mute) }
                Spacer(Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    TabLabel("Vídeo", isVideo) { isVideo = true }
                    TabLabel("Áudio", !isVideo) { isVideo = false }
                }
                Spacer(Modifier.height(14.dp))

                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    if (isVideo) {
                        Text("Formato: MP4", fontSize = 13.sp, color = Mute)
                        Spacer(Modifier.height(8.dp))
                        info.options.forEachIndexed { i, o -> OptionRow(o.label, vIdx == i) { vIdx = i } }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("MP3", audioFmt == "MP3") { audioFmt = "MP3" }
                            Chip("WAV", audioFmt == "WAV") { audioFmt = "WAV" }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (audioFmt == "MP3") {
                            listOf(128, 256, 320).forEach { k -> OptionRow("$k kbps", kbps == k) { kbps = k } }
                        } else {
                            OptionRow("Sem perda (original)", true) {}
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onDismiss, shape = R8, border = BorderStroke(1.dp, Line)) {
                        Text("Cancelar", color = Ink)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirm(
                                Selection(
                                    isVideo = isVideo,
                                    format = if (isVideo) "MP4" else audioFmt,
                                    video = if (isVideo) info.options.getOrNull(vIdx) else null,
                                    kbps = kbps,
                                ),
                            )
                        },
                        shape = R8, colors = ButtonDefaults.buttonColors(containerColor = Acc),
                    ) { Text("Baixar", color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(R8)
            .background(if (selected) Soft else Color.White)
            .border(1.dp, if (selected) Acc else Line, R8)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) { Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, color = Ink) }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(R8).background(if (selected) Acc else Color.White)
            .border(1.dp, if (selected) Acc else Line, R8)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (selected) Color.White else Ink) }
}
