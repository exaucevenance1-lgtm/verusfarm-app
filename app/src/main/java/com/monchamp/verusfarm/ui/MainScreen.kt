@file:OptIn(ExperimentalMaterial3Api::class)

package com.monchamp.verusfarm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monchamp.verusfarm.Checks
import com.monchamp.verusfarm.Config
import com.monchamp.verusfarm.MiningUi
import com.monchamp.verusfarm.PowerMode
import com.monchamp.verusfarm.Tone

@Composable
fun MainScreen(
    ui: MiningUi,
    wallet: String,
    customName: String,
    autoName: String,
    mode: PowerMode,
    checks: Checks,
    abi: String,
    cores: Int,
    onToggle: () -> Unit,
    onModeChange: (PowerMode) -> Unit,
    onSaveIdentity: (String, String) -> Unit,
    onAskNotif: () -> Unit,
    onAskBattery: () -> Unit,
    onAskAdmin: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
    val workerName = customName.ifBlank { autoName }
    val walletOk = Config.isUsableWallet(wallet)
    val chargingNote = if (ui.charging) " (secteur)" else ""
    val batteryText = if (ui.batteryPercent > 0) "${ui.batteryPercent} %$chargingNote" else "—"
    var showEdit by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }

    // Première ouverture : on demande l'adresse tout de suite
    LaunchedEffect(walletOk) { if (!walletOk) showEdit = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Header(ui.tone)

        // La pièce maîtresse : un anneau avec un segment par cœur du processeur
        Panel {
            Box(
                modifier = Modifier.align(Alignment.CenterHorizontally).size(240.dp),
                contentAlignment = Alignment.Center
            ) {
                CoreRing(
                    total = cores,
                    active = ui.threads,
                    color = toneColor(ui.tone),
                    track = MaterialTheme.colorScheme.outline
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ui.hashrate, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${ui.threads} cœurs sur $cores",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                ui.status,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = toneColor(ui.tone),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium
            )
            Row(Modifier.fillMaxWidth()) {
                Stat("Parts acceptées", "${ui.accepted}", Modifier.weight(1f))
                Stat(
                    "Température",
                    if (ui.tempC > 0f) "${ui.tempC.toInt()} °C" else "—",
                    Modifier.weight(1f)
                )
                Stat("Batterie", batteryText, Modifier.weight(1f))
            }
        }

        if (ui.enabled) {
            OutlinedButton(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text("Arrêter le minage", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        } else {
            Button(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text("Démarrer le minage", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        }

        if (!ui.engineOk) {
            Panel(border = Corail) {
                Text("Programme de minage manquant", fontWeight = FontWeight.Bold, color = Corail)
                Text(
                    "Cette version de VerusFarm ne contient pas de programme de minage pour ce processeur ($abi). " +
                        "Ajoute libccminer.so dans le dossier jniLibs de ce processeur, puis recompile l'application.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Panel {
            SectionTitle("Puissance de calcul")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PowerMode.values().forEach { m ->
                    ModeChip(m.label, m == mode, Modifier.weight(1f)) { onModeChange(m) }
                }
            }
            Text(
                when (mode) {
                    PowerMode.ECO -> "La moitié des cœurs : le téléphone reste frais et la batterie souffre moins."
                    PowerMode.BALANCED -> "Les trois quarts des cœurs : un bon compromis."
                    PowerMode.MAX -> "Tous les cœurs. L'application ralentit toute seule si le téléphone chauffe trop."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Panel {
            SectionTitle("Cet appareil")
            InfoRow("Nom sur Luckpool", workerName)
            InfoRow("Adresse VRSC", if (walletOk) maskWallet(wallet) else "À renseigner")
            InfoRow("Processeur", "$abi, $cores cœurs")
            if (ui.pool != "—") InfoRow("Serveur", ui.pool)
            OutlinedButton(
                onClick = { showEdit = true },
                shape = RoundedCornerShape(12.dp)
            ) { Text("Modifier l'adresse et le nom") }
        }

        Panel {
            SectionTitle("Pour que le minage ne s'arrête jamais")
            CheckRow(
                "Notifications",
                "Android les exige pour travailler en arrière-plan.",
                checks.notif, "Autoriser", onAskNotif
            )
            CheckRow(
                "Batterie sans restriction",
                "Empêche Android d'endormir le minage.",
                checks.battery, "Autoriser", onAskBattery
            )
            CheckRow(
                "Protection contre la désinstallation",
                "Active l'administrateur de l'appareil.",
                checks.admin, "Activer", onAskAdmin
            )
            CheckRow(
                "Démarrage automatique",
                "Sur Tecno, Infinix, Xiaomi, Samsung : ouvre Batterie ou Démarrage automatique de l'application.",
                false, "Ouvrir les réglages", onOpenAppSettings
            )
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Journal du mineur", Modifier.weight(1f))
                TextButton(onClick = { showLogs = !showLogs }) {
                    Text(if (showLogs) "Masquer" else "Afficher")
                }
            }
            if (showLogs) {
                Text(
                    if (ui.logs.isEmpty()) "Aucun message pour le moment."
                    else ui.logs.takeLast(25).joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showEdit) {
        EditDialog(
            wallet = wallet,
            name = customName,
            autoName = autoName,
            onDismiss = { showEdit = false },
            onSave = { w, n ->
                showEdit = false
                onSaveIdentity(w, n)
            }
        )
    }
}

// ---------------------------------------------------------------- éléments

@Composable
private fun Header(tone: Tone) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("VerusFarm", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Minage de Verus sur Luckpool",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val c = toneColor(tone)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(c.copy(alpha = 0.15f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(8.dp))
            Text(
                when (tone) {
                    Tone.IDLE -> "Arrêté"
                    Tone.OK -> "En marche"
                    Tone.WARN -> "Attention"
                    Tone.ERROR -> "Erreur"
                },
                color = c,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    border: Color = MaterialTheme.colorScheme.outline,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, border, RoundedCornerShape(24.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

// Un segment par cœur : allumé = ce cœur mine, éteint = au repos
@Composable
private fun CoreRing(total: Int, active: Int, color: Color, track: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 14.dp.toPx()
        val n = total.coerceIn(1, 16)
        val gap = 6f
        val step = 360f / n
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(stroke / 2f, stroke / 2f)
        for (i in 0 until n) {
            drawArc(
                color = if (i < active) color else track,
                startAngle = -90f + i * step + gap / 2f,
                sweepAngle = step - gap,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(value, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

@Composable
private fun CheckRow(title: String, detail: String, done: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        if (done) {
            Text("Activé", color = Menthe, fontWeight = FontWeight.SemiBold)
        } else {
            FilledTonalButton(onClick = onClick, shape = RoundedCornerShape(12.dp)) { Text(action) }
        }
    }
}

@Composable
private fun EditDialog(
    wallet: String,
    name: String,
    autoName: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var w by remember { mutableStateOf(if (Config.isPlaceholder(wallet)) "" else wallet) }
    var n by remember { mutableStateOf(name) }
    val valid = Config.isUsableWallet(w.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Identité de cet appareil") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = w,
                    onValueChange = { w = it.trim() },
                    label = { Text("Adresse VRSC") },
                    singleLine = true,
                    isError = w.isNotEmpty() && !valid,
                    supportingText = {
                        Text(if (w.isEmpty() || valid) "Elle commence par la lettre R." else "Adresse invalide : vérifie qu'elle est complète.")
                    }
                )
                OutlinedTextField(
                    value = n,
                    onValueChange = { n = it.filter { c -> c.isLetterOrDigit() }.take(20) },
                    label = { Text("Nom de l'appareil (facultatif)") },
                    singleLine = true,
                    supportingText = { Text("Laisse vide pour le nom automatique : $autoName") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(w.trim(), n) }, enabled = valid) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

private fun maskWallet(w: String): String =
    if (w.length > 12) w.take(6) + "…" + w.takeLast(4) else w
