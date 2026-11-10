package com.bitchat.android.faro.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.ChatViewModel

/**
 * Sala «Gente cerca»: chat abierto con los móviles que tienes alrededor por Bluetooth, con el
 * mismo diseño que los chats de grupo. Lo puede leer cualquiera que esté cerca.
 */
@Composable
internal fun NearbyRoomScreen(nav: FaroNav, vm: ChatViewModel) {
    val all by vm.messages.collectAsStateWithLifecycle()
    val peers by vm.connectedPeers.collectAsStateWithLifecycle()
    val nickname by vm.nickname.collectAsStateWithLifecycle()
    val msgs = all.filter { !it.isPrivate && it.channel == null }
    val n = peers.count { it != vm.myPeerID }
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.scrollToItem(msgs.size - 1) }

    Column(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
            Box(Modifier.size(40.dp).border(1.5.dp, Color(0xFF555555), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.WifiTethering, null, tint = Color(0xFFBBBBBB), modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Gente cerca", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    (if (n == 1) "1 móvil cerca" else "$n móviles cerca") + " · sala abierta",
                    fontSize = 12.sp, color = FaroColors.Muted,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(FaroColors.Line))
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Sala abierta · la puede leer cualquiera que esté cerca",
                fontSize = 12.5.sp, color = FaroColors.Muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            )
        }
        items(msgs, key = { it.id }) { m -> NearbyBubble(m, mine = m.senderPeerID == vm.myPeerID || (m.senderPeerID == null && m.sender == nickname)) }
        item { Gap(6.dp) }
    }
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().card(FaroColors.Line2).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.WarningAmber, null, tint = FaroColors.Warn, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Cualquiera cerca puede leer esta sala. No pongas datos personales.", fontSize = 12.5.sp, color = Color(0xFFD6D6D6))
    }
    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // Sin comandos de la app original: lo que empiece por «/» se envía como texto normal.
        val send = { val t = text.trim().trimStart('/'); if (t.isNotEmpty()) { vm.sendMessage(t); text = "" } }
        OutlinedTextField(
            value = text, onValueChange = { text = it }, placeholder = { Text("Escribe a quien esté cerca") },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(26.dp), maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = FaroColors.Line2, focusedBorderColor = FaroColors.Accent),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(if (text.isNotBlank()) FaroColors.Accent else FaroColors.Line2)
                .clickable(enabled = text.isNotBlank()) { send() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar", tint = FaroColors.AccentInk, modifier = Modifier.size(20.dp)) }
    }
    }
}

@Composable
private fun NearbyBubble(m: BitchatMessage, mine: Boolean) {
    if (m.sender == "system") {
        Text(m.content, fontSize = 12.5.sp, color = FaroColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        return
    }
    val color = if (mine) FaroColors.Accent else Color(0xFFE8E8E8)
    val shape = if (mine) RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(Modifier.widthIn(max = 290.dp).border(1.5.dp, color, shape).padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (!mine) Text(m.sender, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = hex(colorFor(m.senderPeerID ?: m.sender)))
            Text(m.content, fontSize = 15.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(m.timestamp),
                fontSize = 11.sp, color = FaroColors.Dim, modifier = Modifier.align(Alignment.End),
            )
        }
    }
}
