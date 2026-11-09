package com.bitchat.android.faro.ui

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bitchat.android.faro.FaroGroup
import com.bitchat.android.faro.FaroGroups
import com.bitchat.android.faro.FaroInvite
import com.bitchat.android.faro.FaroJoin
import com.bitchat.android.faro.FaroLocation
import com.bitchat.android.faro.FaroMessage
import com.bitchat.android.faro.FaroOfflineMap
import com.bitchat.android.faro.FaroProtocol
import com.bitchat.android.faro.MemberPosition
import com.bitchat.android.ui.QRCodeImage
import com.bitchat.android.ui.ScannerView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import kotlin.math.abs
import kotlin.math.roundToInt

private sealed class Screen {
    object List : Screen()
    data class Create(val parentId: String? = null) : Screen()
    object Scan : Screen()
    data class Group(val id: String) : Screen()
    data class Invite(val id: String) : Screen()
}

/** Colores pastel para distinguir a cada miembro. */
private val PASTELS = listOf("#FFB4A8", "#B4F08C", "#D7C2FF", "#FFE08A", "#8FE3E0", "#F5B8DA", "#C9E4A7", "#F7C59F")

internal fun colorFor(id: String): String = PASTELS[abs(id.hashCode()) % PASTELS.size]

/**
 * Grupos privados de Faro a pantalla completa: lista, crear, invitar/unirse por QR y, dentro de
 * cada grupo, mapa con la familia y chat.
 *
 * [pendingInvite] es una invitación recibida (por QR o enlace) que hay que confirmar.
 */
@Composable
fun FaroGroupsDialog(onDismiss: () -> Unit, pendingInvite: String? = null) {
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    var confirm by remember { mutableStateOf<FaroInvite.Invite?>(null) }
    var linkConfirm by remember { mutableStateOf<FaroJoin.LinkInvite?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun handleCode(code: String) {
        if (FaroJoin.isLink(code)) {
            when (val r = FaroGroups.previewLink(code)) {
                is FaroJoin.Parse.Ok -> linkConfirm = r.invite
                FaroJoin.Parse.Expired -> message = "Esta invitación ha caducado. Pide un enlace nuevo."
                else -> message = "Este enlace de invitación no es válido."
            }
            return
        }
        when (val r = FaroGroups.previewCode(code)) {
            is FaroInvite.Result.Ok -> confirm = r.invite
            FaroInvite.Result.Expired -> message = "Esta invitación ha caducado. Pide que te enseñen un QR nuevo."
            FaroInvite.Result.Invalid -> message = "Esta invitación no es válida."
            FaroInvite.Result.NotFaro -> message = "Este código no es una invitación de Faro."
        }
    }

    LaunchedEffect(pendingInvite) { pendingInvite?.let(::handleCode) }

    Dialog(
        onDismissRequest = {
            if (screen == Screen.List) onDismiss() else screen = Screen.List
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                when (val s = screen) {
                    Screen.List -> GroupList(
                        onClose = onDismiss,
                        onOpen = { screen = Screen.Group(it) },
                        onCreate = { screen = Screen.Create() },
                        onScan = { screen = Screen.Scan },
                    )
                    is Screen.Create -> CreateGroup(
                        parentId = s.parentId,
                        onBack = { screen = s.parentId?.let { Screen.Group(it) } ?: Screen.List },
                        onCreated = { screen = Screen.Group(it) },
                    )
                    Screen.Scan -> ScanInvite(
                        onBack = { screen = Screen.List },
                        onCode = { code -> screen = Screen.List; handleCode(code) },
                    )
                    is Screen.Group -> GroupDetail(
                        groupId = s.id,
                        onBack = { screen = Screen.List },
                        onInvite = { screen = Screen.Invite(s.id) },
                        onCreateSub = { screen = Screen.Create(parentId = s.id) },
                    )
                    is Screen.Invite -> InviteQr(groupId = s.id, onBack = { screen = Screen.Group(s.id) })
                }
            }
        }
    }

    linkConfirm?.let { inv ->
        JoinConfirmDialog(
            groupName = inv.groupName,
            inviterName = inv.inviterName,
            members = inv.members,
            onAccept = {
                FaroGroups.acceptLink(inv)
                linkConfirm = null
                message = "Listo. Entrarás en «${inv.groupName}» en cuanto el móvil de ${inv.inviterName} reciba tu solicitud, por internet o por la red cercana."
            },
            onDecline = { linkConfirm = null },
        )
    }

    confirm?.let { inv ->
        JoinConfirmDialog(
            groupName = inv.groupName,
            onAccept = {
                when (val r = FaroGroups.joinInvite(inv, shareLocation = true)) {
                    is FaroGroups.JoinResult.Joined -> screen = Screen.Group(r.group.id)
                    is FaroGroups.JoinResult.AlreadyMember -> screen = Screen.Group(r.group.id)
                    else -> message = "No se ha podido entrar en el grupo."
                }
                confirm = null
            },
            onDecline = { confirm = null },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("Entendido") } },
            text = { Text(text) },
        )
    }
}

// ---------------------------------------------------------------- Lista

@Composable
private fun GroupList(onClose: () -> Unit, onOpen: (String) -> Unit, onCreate: () -> Unit, onScan: () -> Unit) {
    val groups by FaroGroups.groups.collectAsState()
    val waiting by FaroJoin.waiting.collectAsState()
    var askShare by remember { mutableStateOf(false) }
    val context = LocalContext.current
    TopBar(title = "Grupos", onBack = onClose) {
        TextButton(onClick = { askShare = true }) {
            Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Compartir Faro")
        }
    }
    if (askShare) {
        AlertDialog(
            onDismissRequest = { askShare = false },
            title = { Text("Compartir Faro") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Cuantas más personas cerca tengan Faro, más lejos llegan los mensajes. Puedes pasarla sin internet:")
                    SecondaryButton("Por Quick Share u otra app", Icons.Filled.Share, Modifier.fillMaxWidth()) {
                        askShare = false
                        com.bitchat.android.faro.FaroShare.shareWithApps(context)
                    }
                    SecondaryButton("Por Wi‑Fi con un QR", Icons.Filled.QrCode, Modifier.fillMaxWidth()) {
                        askShare = false
                        com.bitchat.android.faro.FaroShare.shareByWifi(context)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { askShare = false }) { Text("Cerrar") } },
        )
    }
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(
            "Grupos privados para estar en contacto con los tuyos en una emergencia, con o sin internet.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
            lineHeight = 21.sp,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Crear grupo", Icons.Filled.Add, Modifier.weight(1f), onClick = onCreate)
            SecondaryButton("Unirme con QR", Icons.Filled.QrCodeScanner, Modifier.weight(1f), onScan)
        }
        Spacer(Modifier.height(20.dp))
        waiting.forEach { name ->
            Text(
                "Esperando a entrar en «$name»…",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
    }
    if (groups.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Todavía no tienes grupos.\nCrea uno para tu familia o únete con el QR de alguien.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(groups.filter { it.parentId == null }, key = { it.id }) { g ->
                GroupCard(g, onClick = { onOpen(g.id) })
                groups.filter { it.parentId == g.id }.forEach { sub ->
                    GroupCard(sub, indent = true, onClick = { onOpen(sub.id) })
                }
            }
        }
    }
}

@Composable
private fun GroupCard(g: FaroGroup, indent: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(start = if (indent) 40.dp else 20.dp, end = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(g.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            val n = g.members.size + 1
            Text(
                "$n ${if (n == 1) "persona" else "personas"} · ${if (g.shareLocation) "compartes tu ubicación" else "ubicación desactivada"}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (g.shareLocation) Icon(Icons.Filled.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
    }
}

// ---------------------------------------------------------------- Crear

@Composable
private fun CreateGroup(parentId: String?, onBack: () -> Unit, onCreated: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val parent = parentId?.let { FaroGroups.group(it) }
    TopBar(title = if (parent != null) "Subgrupo de ${parent.name}" else "Crear grupo", onBack = onBack)
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("¿Cómo se llama el grupo?", fontSize = 17.sp)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            placeholder = { Text(if (parent != null) "Por ejemplo: Abuelos" else "Por ejemplo: Familia") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        )
        Text(
            if (parent != null) {
                "Un subgrupo tiene su propia clave: aunque alguien esté en «${parent.name}», solo entrará si le invitas a este."
            } else {
                "Solo podrá entrar quien tú invites. Los mensajes y ubicaciones van cifrados: nadie fuera del grupo puede leerlos."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
        )
        PrimaryButton("Crear", Icons.Filled.Add, Modifier.fillMaxWidth(), enabled = name.isNotBlank()) {
            val g = FaroGroups.createGroup(name, parentId)
            FaroGroups.setShareLocation(g.id, true)
            onCreated(g.id)
        }
    }
}

// ---------------------------------------------------------------- Invitar / unirse

@Composable
private fun InviteQr(groupId: String, onBack: () -> Unit) {
    val code = remember(groupId) { FaroGroups.inviteCode(groupId) }
    val g = FaroGroups.group(groupId)
    val context = LocalContext.current
    TopBar(title = "Invitar a ${g?.name ?: "grupo"}", onBack = onBack)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (code == null) {
            Text("No se ha podido crear la invitación.")
            return@Column
        }
        Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White).padding(14.dp)) {
            QRCodeImage(data = code, size = 260.dp)
        }
        Text(
            "Que la otra persona abra Faro → Grupos → «Unirme con QR» y escanee este código.",
            textAlign = TextAlign.Center,
            fontSize = 16.sp,
        )
        Text(
            "Enséñalo solo a quien quieras invitar: da acceso al grupo. Caduca en 15 minutos.",
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text("¿No estáis juntos?", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        SecondaryButton("Enviar enlace de invitación", Icons.Filled.Share, Modifier.fillMaxWidth()) {
            val link = FaroGroups.inviteLink(groupId) ?: return@SecondaryButton
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    android.content.Intent.EXTRA_TEXT,
                    "Te invito al grupo «${g?.name ?: ""}» de Faro, para estar en contacto en una emergencia: $link"
                )
            }
            context.startActivity(android.content.Intent.createChooser(send, "Enviar invitación"))
        }
        Text(
            "El enlace no contiene la clave del grupo: solo sirve para una persona, caduca en 7 días y tu móvil le dará acceso cuando lo acepte.",
            textAlign = TextAlign.Center,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun ScanInvite(onBack: () -> Unit, onCode: (String) -> Unit) {
    val camera = rememberPermissionState(Manifest.permission.CAMERA)
    TopBar(title = "Unirme con QR", onBack = onBack)
    if (camera.status.isGranted) {
        Box(
            Modifier.padding(20.dp).fillMaxWidth().height(360.dp).clip(RoundedCornerShape(24.dp))
                .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp))
        ) {
            ScannerView(onScan = { text -> if (text.startsWith("faro:")) onCode(text) })
        }
        Text(
            "Apunta al QR que te enseñan desde Faro.",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Para escanear el QR, Faro necesita usar la cámara.", fontSize = 16.sp)
            PrimaryButton("Permitir cámara", Icons.Filled.QrCodeScanner, Modifier.fillMaxWidth()) {
                camera.launchPermissionRequest()
            }
        }
    }
}

@Composable
fun JoinConfirmDialog(
    groupName: String,
    members: List<String> = emptyList(),
    inviterName: String? = null,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("Unirte a «$groupName»", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                inviterName?.let { Text("Te invita: $it") }
                if (members.isNotEmpty()) Text("Están dentro: ${members.joinToString(", ")}")
                Text(
                    "Al unirte compartirás tu ubicación con este grupo, solo para emergencias, para que puedan saber dónde estás si no hay cobertura.",
                )
                Text(
                    "Nadie fuera del grupo puede verla. Puedes desactivarla cuando quieras desde el grupo.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }
        },
        confirmButton = {
            Button(onClick = onAccept, shape = RoundedCornerShape(50)) { Text("Unirme") }
        },
        dismissButton = { TextButton(onClick = onDecline) { Text("No, gracias") } },
    )
}

// ---------------------------------------------------------------- Grupo

@Composable
private fun GroupDetail(groupId: String, onBack: () -> Unit, onInvite: () -> Unit, onCreateSub: () -> Unit) {
    val groups by FaroGroups.groups.collectAsState()
    val g = groups.firstOrNull { it.id == groupId }
    var tab by remember { mutableStateOf(0) }
    var askLeave by remember { mutableStateOf(false) }
    if (g == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    TopBar(title = g.name, onBack = onBack) {
        IconButton(onClick = onInvite) { Icon(Icons.Filled.QrCode, contentDescription = "Invitar") }
    }
    TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Mapa", fontSize = 16.sp) })
        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Chat", fontSize = 16.sp) })
    }
    when (tab) {
        0 -> FamilyMap(g, onLeave = { askLeave = true }, onCreateSub = if (g.parentId == null) onCreateSub else null)
        else -> GroupChat(g)
    }
    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text("¿Salir de «${g.name}»?") },
            text = { Text("Dejarás de ver sus mensajes y ubicaciones. Para volver necesitarás otra invitación.") },
            confirmButton = {
                TextButton(onClick = { askLeave = false; FaroGroups.leaveGroup(g.id); onBack() }) {
                    Text("Salir", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { askLeave = false }) { Text("Cancelar") } },
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun FamilyMap(g: FaroGroup, onLeave: () -> Unit, onCreateSub: (() -> Unit)?) {
    val positions by FaroGroups.positions.collectAsState()
    val me by FaroLocation.myLocation.collectAsState()
    val sos by FaroLocation.sosActive.collectAsState()
    val offline by FaroOfflineMap.state.collectAsState()
    val members = positions[g.id].orEmpty().values.sortedBy { it.name }
    val heading = rememberHeading()
    var confirmSos by remember { mutableStateOf(false) }
    val locationPerms = rememberMultiplePermissionsState(
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    )

    LaunchedEffect(Unit) { FaroLocation.refreshForDisplay() }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Box(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(320.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
            ) {
                FaroMapView(
                    points = mapPoints(members, me, "Yo", ::colorFor),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                when (val o = offline) {
                    is FaroOfflineMap.State.Ready -> "Mapa de tu zona guardado para usar sin conexión (${"%.1f".format(o.megabytes)} MB)"
                    is FaroOfflineMap.State.Downloading -> "Guardando el mapa de tu zona para usarlo sin conexión… ${o.percent}%"
                    FaroOfflineMap.State.Error -> "No se ha podido guardar el mapa sin conexión. Se reintentará con internet."
                    FaroOfflineMap.State.None -> "El mapa de tu zona se guardará solo la próxima vez que tengas internet."
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        if (!locationPerms.allPermissionsGranted) {
            item {
                InfoCard(
                    title = "Activa la ubicación",
                    body = "Para verte en el mapa y compartir tu posición con el grupo, Faro necesita permiso de ubicación.",
                    action = "Permitir ubicación",
                    onAction = { locationPerms.launchMultiplePermissionRequest() },
                )
            }
        }

        item {
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Compartir mi ubicación", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (g.shareLocation) "El grupo puede ver dónde estás." else "El grupo no ve dónde estás.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = g.shareLocation, onCheckedChange = { FaroGroups.setShareLocation(g.id, it) })
            }
        }

        if (members.isEmpty()) {
            item {
                Text(
                    "Aún no se ha recibido la ubicación de nadie del grupo. Aparecerán aquí en cuanto la compartan y estéis conectados por la red o por internet.",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }
        } else {
            items(members, key = { it.authorId }) { m -> MemberRow(m, me, heading) }
        }

        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { if (sos) FaroLocation.sendSos(false) else confirmSos = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error),
                ) {
                    Text(
                        if (sos) "Ya estoy bien (cancelar aviso)" else "Pedir ayuda",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (onCreateSub != null) {
                    TextButton(onClick = onCreateSub, modifier = Modifier.fillMaxWidth()) {
                        Text("Crear un subgrupo dentro de «${g.name}»")
                    }
                }
                TextButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
                    Text("Salir del grupo", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (confirmSos) {
        AlertDialog(
            onDismissRequest = { confirmSos = false },
            title = { Text("¿Pedir ayuda?") },
            text = { Text("Se enviará tu ubicación y el aviso «necesito ayuda» a todos tus grupos, aunque tengas la ubicación desactivada.") },
            confirmButton = {
                TextButton(onClick = { confirmSos = false; FaroLocation.sendSos(true) }) {
                    Text("Pedir ayuda", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmSos = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun MemberRow(m: MemberPosition, me: Location?, heading: Float?) {
    val target = Location("faro").apply { latitude = m.position.lat; longitude = m.position.lon }
    val distance = me?.distanceTo(target)
    val bearing = me?.bearingTo(target)
    val needsHelp = m.position.status == FaroProtocol.Position.Status.NEED_HELP
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(
                1.5.dp,
                if (needsHelp) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(18.dp)
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(colorFor(m.authorId)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(m.name.take(1).uppercase(), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (needsHelp) "Necesita ayuda · ${ago(m.timestampMs)}"
                else "${ago(m.timestampMs)} · batería ${m.position.batteryPct} %",
                fontSize = 13.sp,
                color = if (needsHelp) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(distance?.let(::formatDistance) ?: "—", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (bearing != null) {
                val angle = bearing - (heading ?: 0f)
                Icon(
                    Icons.Filled.Navigation,
                    contentDescription = "Dirección",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp).rotate(angle),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Chat

@Composable
private fun GroupChat(g: FaroGroup) {
    val all by FaroGroups.messages.collectAsState()
    val msgs = all[g.id].orEmpty()
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (msgs.isEmpty()) {
                item {
                    Text(
                        "Escribe al grupo. Los mensajes llegan por la red cercana y solo los leen los miembros.",
                        modifier = Modifier.padding(20.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(msgs, key = { it.id }) { m -> Bubble(m) }
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Escribe un mensaje…") },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                shape = RoundedCornerShape(26.dp),
                maxLines = 4,
            )
            Spacer(Modifier.size(8.dp))
            IconButton(
                onClick = { FaroGroups.sendText(g.id, text); text = "" },
                enabled = text.isNotBlank(),
                modifier = Modifier.size(52.dp).clip(CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun Bubble(m: FaroMessage) {
    val color = if (m.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val shape = if (m.mine) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
        if (!m.mine) {
            Text(
                m.authorName,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(android.graphics.Color.parseColor(colorFor(m.authorId))),
                modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
            )
        }
        Box(
            Modifier.widthIn(max = 300.dp).border(1.5.dp, color.copy(alpha = 0.85f), shape).padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(m.text, color = color, fontSize = 15.sp, lineHeight = 21.sp)
        }
        Text(
            ago(m.timestampMs),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

// ---------------------------------------------------------------- Piezas comunes

@Composable
private fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        Text(text, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun InfoCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(body, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onAction) { Text(action) }
    }
}

/** Orientación del móvil en grados (0 = norte), solo mientras la pantalla está visible. */
@Composable
private fun rememberHeading(): Float? {
    val context = LocalContext.current
    var heading by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rot = FloatArray(9)
        val orient = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                SensorManager.getOrientation(rot, orient)
                val deg = ((Math.toDegrees(orient[0].toDouble()) + 360) % 360).toFloat()
                val prev = heading
                if (prev == null || abs(prev - deg) > 2f) heading = deg
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    return heading
}

private fun formatDistance(m: Float): String =
    if (m < 1000) "${(m / 10).roundToInt() * 10} m" else "%.1f km".format(m / 1000)

private fun ago(ts: Long): String {
    val mins = ((System.currentTimeMillis() - ts) / 60_000).coerceAtLeast(0)
    return when {
        mins < 1 -> "ahora"
        mins < 60 -> "hace $mins min"
        mins < 24 * 60 -> "hace ${mins / 60} h"
        else -> "hace ${mins / (24 * 60)} d"
    }
}
