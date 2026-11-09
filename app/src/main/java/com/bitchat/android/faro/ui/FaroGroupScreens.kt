package com.bitchat.android.faro.ui

import android.Manifest
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.faro.FaroGroups
import com.bitchat.android.faro.FaroJoin
import com.bitchat.android.faro.FaroLocation
import com.bitchat.android.faro.FaroMessage
import com.bitchat.android.ui.QRCodeImage
import com.bitchat.android.ui.ScannerView
import com.bitchat.android.ui.rememberIsOnline
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

// ---------------------------------------------------------------- Conversación de grupo

@Composable
internal fun GroupChatScreen(nav: FaroNav, groupId: String) {
    val groups by FaroGroups.groups.collectAsStateWithLifecycle()
    val all by FaroGroups.messages.collectAsStateWithLifecycle()
    val g = groups.firstOrNull { it.id == groupId }
    if (g == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val msgs = all[g.id].orEmpty()
    val online = rememberIsOnline()
    var menu by remember { mutableStateOf(false) }
    var askLeave by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val color = groupColorHex(groups, g.id)

    LaunchedEffect(msgs.size) {
        FaroGroups.markRead(g.id)
        if (msgs.isNotEmpty()) listState.scrollToItem(msgs.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { nav.go(Route.Info(g.id)) }.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupIcon(color, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(g.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${g.members.size + 1} personas · ${if (online) "por internet y Bluetooth" else "por Bluetooth"}",
                    fontSize = 12.sp, color = FaroColors.Muted, maxLines = 1,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Más opciones") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = FaroColors.Card) {
                DropdownMenuItem(text = { Text("Ver en el mapa") }, onClick = { menu = false; nav.mapFilter = g.id; nav.home(Tab.MAPA) })
                DropdownMenuItem(text = { Text("Personas") }, onClick = { menu = false; nav.go(Route.Info(g.id)) })
                if (g.admin) DropdownMenuItem(text = { Text("Invitar a alguien") }, onClick = { menu = false; nav.go(Route.Invite(g.id)) })
                HorizontalDivider(color = FaroColors.Line)
                DropdownMenuItem(text = { Text("Salir del grupo", color = FaroColors.Danger) }, onClick = { menu = false; askLeave = true })
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(FaroColors.Line))

    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Gap(6.dp) }
        if (msgs.isEmpty()) {
            item {
                Text(
                    "Escribe al grupo. Los mensajes llegan por Bluetooth entre móviles cercanos y, si hay, por internet. Solo los leen los miembros.",
                    modifier = Modifier.fillMaxWidth().padding(20.dp), textAlign = TextAlign.Center,
                    color = FaroColors.Muted, fontSize = 13.5.sp, lineHeight = 19.sp,
                )
            }
        }
        items(msgs, key = { it.id }) { m -> Bubble(m) }
        item { Gap(6.dp) }
    }

    Box(Modifier.fillMaxWidth().height(1.dp).background(FaroColors.Line))
    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val send = { if (text.isNotBlank()) { FaroGroups.sendText(g.id, text); text = "" } }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Escribe un mensaje") },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = RoundedCornerShape(26.dp),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = FaroColors.Line2, focusedBorderColor = FaroColors.Accent,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(if (text.isNotBlank()) FaroColors.Accent else FaroColors.Line2)
                .clickable(enabled = text.isNotBlank()) { send() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar", tint = FaroColors.AccentInk, modifier = Modifier.size(20.dp)) }
    }

    }
    if (askLeave) LeaveDialog(g.name, onLeave = { askLeave = false; FaroGroups.leaveGroup(g.id); nav.home(Tab.CHATS) }, onCancel = { askLeave = false })
}

@Composable
private fun Bubble(m: FaroMessage) {
    val color = if (m.mine) FaroColors.Accent else Color(0xFFE8E8E8)
    val shape = if (m.mine) RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
        Column(Modifier.widthIn(max = 290.dp).border(1.5.dp, color, shape).padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (!m.mine) Text(m.authorName, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = hex(colorFor(m.authorId)))
            Text(m.text, fontSize = 15.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(m.timestampMs)),
                fontSize = 11.sp, color = FaroColors.Dim, modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun LeaveDialog(name: String, onLeave: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = FaroColors.Card,
        title = { Text("¿Salir de $name?") },
        text = { Text("Dejarás de ver sus mensajes y su ubicación. Para volver tendrán que invitarte otra vez.") },
        confirmButton = { TextButton(onClick = onLeave) { Text("Salir del grupo", color = FaroColors.Danger) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } },
    )
}

// ---------------------------------------------------------------- Información del grupo

@Composable
internal fun GroupInfoScreen(nav: FaroNav, groupId: String) {
    val groups by FaroGroups.groups.collectAsStateWithLifecycle()
    val positions by FaroGroups.positions.collectAsStateWithLifecycle()
    val me by FaroLocation.myLocation.collectAsStateWithLifecycle()
    val g = groups.firstOrNull { it.id == groupId }
    if (g == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    var askLeave by remember { mutableStateOf(false) }
    val ppl = people(listOf(g), positions, FaroGroups.myId())

    BackBar(g.name, onBack = { nav.back() })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GroupIcon(groupColorHex(groups, g.id), 72.dp)
            Gap(8.dp)
            Text("${g.members.size + 1} personas · grupo privado", fontSize = 13.5.sp, color = FaroColors.Muted)
        }
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().card().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Compartir mi ubicación", fontSize = 15.sp)
                Text("Para emergencias. El grupo ve dónde estás.", fontSize = 12.sp, color = FaroColors.Muted)
            }
            Switch(
                checked = g.shareLocation,
                onCheckedChange = { FaroGroups.setShareLocation(g.id, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = FaroColors.Accent, checkedThumbColor = FaroColors.AccentInk),
            )
        }
        SectionTitle("Personas")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(FaroGroups.myName(), "#A7C7E7", 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tú", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (g.admin) AdminBadge()
                }
                Text(if (g.shareLocation) "Compartes tu ubicación" else "No compartes tu ubicación", fontSize = 12.5.sp, color = FaroColors.Muted)
            }
        }
        ppl.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.name, colorFor(p.id), 38.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    val (txt, c) = when {
                        p.needsHelp -> "Necesita ayuda" to FaroColors.Danger
                        p.pos == null -> "Sin ubicación compartida" to FaroColors.Muted
                        p.silent -> "Sin noticias · ${hoursText(p.silentFor ?: 0L)}" to FaroColors.Warn
                        else -> ("Bien · " + (me?.let { m -> p.location()?.let { "a " + formatDistance(m.distanceTo(it)) } } ?: ago(p.pos.timestampMs))) to FaroColors.Muted
                    }
                    Text(txt, fontSize = 12.5.sp, color = c)
                }
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (g.admin) OutlinePill("Invitar a alguien", Modifier.fillMaxWidth()) { nav.go(Route.Invite(g.id)) }
            TextButton(onClick = { askLeave = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Salir del grupo", color = FaroColors.Danger, fontSize = 15.sp)
            }
        }
        if (g.admin) Hint("Eres el administrador: nadie entra sin que tú lo aceptes.", Modifier.padding(bottom = 16.dp))
    }
    if (askLeave) LeaveDialog(g.name, onLeave = { askLeave = false; FaroGroups.leaveGroup(g.id); nav.home(Tab.CHATS) }, onCancel = { askLeave = false })
}

@Composable
private fun AdminBadge() {
    Text(
        "Administrador", fontSize = 11.sp, color = FaroColors.Muted,
        modifier = Modifier.padding(start = 6.dp).border(1.dp, FaroColors.Line2, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

// ---------------------------------------------------------------- Crear

@Composable
internal fun CreateGroupScreen(nav: FaroNav) {
    var name by remember { mutableStateOf("") }
    BackBar("Crear un grupo", onBack = { nav.back() })
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Nombre del grupo", fontSize = 14.sp, color = FaroColors.Muted)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            placeholder = { Text("Por ejemplo, Los primos") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = FaroColors.Line2, focusedBorderColor = FaroColors.Accent),
        )
        Text("Serás el administrador: nadie entra sin que tú lo aceptes.", fontSize = 13.5.sp, color = FaroColors.Muted, lineHeight = 19.sp)
        FilledPill("Crear grupo", Modifier.fillMaxWidth(), enabled = name.isNotBlank()) {
            val g = FaroGroups.createGroup(name)
            FaroGroups.setShareLocation(g.id, true)
            nav.back()
            nav.go(Route.Group(g.id))
            nav.go(Route.Invite(g.id))
        }
    }
}

// ---------------------------------------------------------------- Invitar

@Composable
internal fun InviteScreen(nav: FaroNav, groupId: String) {
    val g = FaroGroups.group(groupId)
    val context = LocalContext.current
    // El QR es un enlace de un solo uso: quien lo escanee pide entrar y tú decides.
    val qr = remember(groupId) { FaroGroups.inviteLink(groupId) }
    BackBar("Invitar a ${g?.name ?: "grupo"}", onBack = { nav.back() })
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Si estáis juntos, que escanee este código con Faro.", fontSize = 14.sp, color = FaroColors.Muted, textAlign = TextAlign.Center)
        Gap(14.dp)
        if (qr != null) {
            Box(Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White).padding(14.dp)) {
                QRCodeImage(data = qr, size = 220.dp)
            }
        } else {
            Text("No se ha podido crear la invitación.")
        }
        Gap(10.dp)
        Text("Sirve para una persona · caduca en 7 días", fontSize = 12.5.sp, color = FaroColors.Dim)
        Gap(22.dp)
        OutlinePill("Enviar enlace…", Modifier.fillMaxWidth()) {
            val link = FaroGroups.inviteLink(groupId) ?: return@OutlinePill
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Te invito al grupo «${g?.name ?: ""}» de Faro, para estar en contacto en una emergencia: $link")
            }
            context.startActivity(Intent.createChooser(send, "Enviar invitación"))
        }
        Gap(14.dp)
        Row(Modifier.fillMaxWidth().card(FaroColors.Line2).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, tint = FaroColors.Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Con el código o con el enlace, te llegará un aviso y tú decides si entra. El enlace no contiene la clave del grupo.",
                fontSize = 13.5.sp, lineHeight = 19.sp, color = Color(0xFFD6D6D6),
            )
        }
    }
}

// ---------------------------------------------------------------- Unirse

@OptIn(ExperimentalPermissionsApi::class)
@Composable
internal fun ScanScreen(nav: FaroNav, onCode: (String) -> Unit) {
    val camera = rememberPermissionState(Manifest.permission.CAMERA)
    BackBar("Unirme con un código", onBack = { nav.back() })
    if (camera.status.isGranted) {
        Box(
            Modifier.padding(16.dp).fillMaxWidth().height(360.dp).clip(RoundedCornerShape(24.dp))
                .border(1.5.dp, FaroColors.Accent, RoundedCornerShape(24.dp))
        ) {
            ScannerView(onScan = { text -> if (FaroJoin.isLink(text) || text.startsWith("faro:")) onCode(text) })
        }
        Text(
            "Apunta al código que te enseñan desde Faro. Si te han enviado un enlace, ábrelo y Faro se abrirá solo.",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), textAlign = TextAlign.Center,
            color = FaroColors.Muted, fontSize = 14.sp, lineHeight = 20.sp,
        )
    } else {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Para escanear el código, Faro necesita usar la cámara.", fontSize = 16.sp)
            FilledPill("Permitir cámara", Modifier.fillMaxWidth()) { camera.launchPermissionRequest() }
        }
    }
}

/** Invitación recibida (escaneada o abierta como enlace): confirmar y pedir entrar. */
@Composable
internal fun JoinFlow(code: String, onDone: () -> Unit) {
    val parsed = remember(code) {
        if (FaroJoin.isLink(code)) FaroGroups.previewLink(code) else null
    }
    var sent by remember { mutableStateOf(false) }
    val message: String? = when {
        sent -> null
        parsed == null -> "Este código es de una versión antigua de Faro. Pide que te enseñen uno nuevo."
        parsed is FaroJoin.Parse.Expired -> "Esta invitación ha caducado. Pide una nueva."
        parsed !is FaroJoin.Parse.Ok -> "Esta invitación no es válida."
        else -> null
    }
    if (sent) {
        val inv = (parsed as FaroJoin.Parse.Ok).invite
        InfoDialog("Te has apuntado a «${inv.groupName}». Te avisaremos cuando ${inv.inviterName} te deje entrar.", onDone)
        return
    }
    if (message != null) {
        InfoDialog(message, onDone)
        return
    }
    val inv = (parsed as FaroJoin.Parse.Ok).invite
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = FaroColors.Card,
        title = { Text("Unirte a «${inv.groupName}»", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Te invita ${inv.inviterName}.")
                if (inv.members.isNotEmpty()) Text("Están dentro: ${inv.members.joinToString(", ")}", color = FaroColors.Muted, fontSize = 14.sp)
                Text("Al entrar compartirás tu ubicación con este grupo, solo para emergencias. Puedes desactivarla cuando quieras.")
                Text("${inv.inviterName} tendrá que aceptarte.", color = FaroColors.Muted, fontSize = 14.sp)
            }
        },
        confirmButton = { TextButton(onClick = { FaroGroups.acceptLink(inv); sent = true }) { Text("Pedir entrar", fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDone) { Text("No, gracias") } },
    )
}

@Composable
private fun InfoDialog(text: String, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = FaroColors.Card,
        confirmButton = { TextButton(onClick = onDone) { Text("Entendido") } },
        text = { Text(text) },
    )
}
