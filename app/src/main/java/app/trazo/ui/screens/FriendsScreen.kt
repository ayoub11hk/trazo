package app.trazo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.trazo.AppGraph
import app.trazo.MainActivity
import app.trazo.core.social.Freshness
import app.trazo.core.social.InviteCode
import app.trazo.core.social.LocationFreshness
import app.trazo.data.remote.ResolvedCode
import app.trazo.data.remote.isoToMillis
import app.trazo.data.repo.Friend
import app.trazo.data.repo.GroupInfo
import app.trazo.ui.components.Avatar
import app.trazo.ui.components.EmptyState
import app.trazo.ui.components.ErrorBanner
import app.trazo.ui.components.FreshnessBadge
import app.trazo.ui.components.SectionHeader
import app.trazo.ui.components.SettingCard
import app.trazo.ui.theme.Brand
import kotlinx.coroutines.delay

@Composable
fun FriendsScreen(graph: AppGraph, nav: NavController, activity: MainActivity) {
    val ctx = LocalContext.current
    val social by graph.social.state.collectAsState()
    val serverSettings by graph.profile.settings.collectAsState()
    val positions by graph.live.positions.collectAsState()
    val pendingLink by activity.pendingLink.collectAsState()
    val runner = rememberRunner()
    var codeInput by remember { mutableStateOf("") }
    var resolved by remember { mutableStateOf<ResolvedCode?>(null) }
    var resolvedCode by remember { mutableStateOf("") }
    var confirmRegenerate by remember { mutableStateOf(false) }
    var createGroup by remember { mutableStateOf(false) }
    var openGroup by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(10_000) } }
    LaunchedEffect(Unit) { runCatching { graph.social.refresh(); graph.profile.refresh() } }

    fun lookup(text: String) {
        val parsed = parseInvite(text)
        if (parsed == null) { runner.error = "Ese código no es válido. Tiene 8 caracteres, por ejemplo K7Q2-9MXA."; return }
        runner.run {
            val r = graph.social.resolve(parsed.second)
                ?: throw IllegalStateException("No existe ningún código así (o ha caducado).")
            resolvedCode = parsed.second
            resolved = r
        }
    }

    // A tapped trazo:// link lands here.
    LaunchedEffect(pendingLink) {
        val l = pendingLink ?: return@LaunchedEffect
        activity.pendingLink.value = null
        codeInput = l
        lookup(l)
    }

    Scaffold(topBar = {
        TabTopBar("Amigos") {
            IconButton({ nav.navigate("convoys") }) { Icon(Icons.Filled.Groups, "Convoyes") }
            IconButton({ runner.run { graph.social.refresh() } }) { Icon(Icons.Filled.Refresh, "Actualizar") }
        }
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item {
                runner.error?.let { ErrorBanner(it, Modifier.padding(bottom = 8.dp)) }
                MyCodeCard(
                    code = serverSettings?.inviteCode,
                    onCopy = { c -> copyText(ctx, "Código de Trazo", InviteCode.format(c)) },
                    onShare = { c -> shareText(ctx, "Mi código de Trazo", inviteMessage(InviteCode.Kind.FRIEND, c, null)) },
                    onRegenerate = { confirmRegenerate = true },
                )
                Spacer(Modifier.height(12.dp))
                SettingCard {
                    Column(Modifier.padding(16.dp)) {
                        Text("Añadir con un código", style = MaterialTheme.typography.titleSmall)
                        Text("Sirve para amigos, grupos y convoyes.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(codeInput, { codeInput = it.take(80) }, Modifier.weight(1f),
                                placeholder = { Text("XXXX-XXXX") }, singleLine = true)
                            Spacer(Modifier.width(8.dp))
                            Button({ lookup(codeInput) }, enabled = codeInput.isNotBlank() && !runner.busy) { Text("Buscar") }
                        }
                    }
                }
            }

            val incoming = social.requests.filter { it.incoming }
            val outgoing = social.requests.filter { !it.incoming }
            if (incoming.isNotEmpty() || social.convoyInvites.isNotEmpty()) {
                item { SectionHeader("Solicitudes") }
                items(incoming, key = { "in-" + it.id }) { r ->
                    PersonRow(r.otherName, r.otherAvatar, "Quiere ser tu amigo") {
                        IconButton({ runner.run { graph.social.respond(r.id, false) } }) { Icon(Icons.Filled.Close, "Rechazar") }
                        FilledTonalButton({ runner.run { graph.social.respond(r.id, true) } }) {
                            Icon(Icons.Filled.Check, null); Spacer(Modifier.width(4.dp)); Text("Aceptar")
                        }
                    }
                }
                items(social.convoyInvites, key = { "cv-" + it.id }) { inv ->
                    val from = social.people[inv.fromId]?.displayName ?: "Alguien"
                    PersonRow(from, social.people[inv.fromId]?.avatarUrl, "Te invita a un convoy") {
                        IconButton({ runner.run { graph.convoys.respondInvite(inv.id, false); graph.social.refresh() } }) {
                            Icon(Icons.Filled.Close, "Rechazar")
                        }
                        FilledTonalButton({
                            runner.run(onDone = { nav.navigate("convoy/${inv.convoyId}") }) {
                                graph.convoys.respondInvite(inv.id, true); graph.social.refresh()
                            }
                        }) { Text("Unirme") }
                    }
                }
            }
            if (outgoing.isNotEmpty()) {
                item { SectionHeader("Enviadas") }
                items(outgoing, key = { "out-" + it.id }) { r ->
                    PersonRow(r.otherName, r.otherAvatar, "Pendiente de respuesta") {
                        TextButton({ runner.run { graph.social.cancelRequest(r.id) } }) { Text("Cancelar") }
                    }
                }
            }

            item { SectionHeader("Amigos (${social.friends.size})") }
            if (social.loaded && social.friends.isEmpty()) {
                item {
                    EmptyState(Icons.Outlined.PersonAdd, "Aún no tienes amigos en Trazo",
                        "Comparte tu código o escribe el de un amigo. Nadie puede verte sin que aceptes.")
                }
            }
            items(social.friends, key = { "f-" + it.profile.id }) { f ->
                FriendRow(f, positions[f.profile.id], now) { nav.navigate("friend/${f.profile.id}") }
            }

            item {
                SectionHeader("Grupos", action = "Crear", onAction = { createGroup = true })
                if (social.groups.isEmpty()) {
                    Text("Los grupos permiten ver en el mapa a todos sus miembros sin ser amigos uno a uno.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(social.groups, key = { "g-" + it.group.id }) { g ->
                Row(
                    Modifier.fillMaxWidth().clickable { openGroup = g.group.id }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(g.group.name, null, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(g.group.name, style = MaterialTheme.typography.titleMedium)
                        Text("${g.members.size} miembros", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    resolved?.let { r ->
        val (title, body, action) = when (r.kind) {
            "friend" -> Triple("Enviar solicitud", "¿Enviar una solicitud de amistad a ${r.name}? Solo os veréis cuando la acepte.", "Enviar")
            "group" -> Triple("Unirse al grupo", "¿Unirte al grupo «${r.name}»? Sus miembros verán tu ubicación cuando la compartas. Puedes desactivarlo dentro del grupo.", "Unirme")
            else -> Triple("Unirse al convoy", "¿Unirte al convoy «${r.name}»? Mientras dure, sus miembros verán tu posición.", "Unirme")
        }
        ConfirmDialog(title, body, action, onDismiss = { resolved = null }) {
            val code = resolvedCode
            runner.run(onDone = { codeInput = "" }) {
                when (r.kind) {
                    "friend" -> {
                        val res = graph.social.sendRequest(code)
                        val msg = when (res.status) {
                            "accepted" -> "Ya sois amigos: ${res.displayName ?: r.name} también te había invitado."
                            "already_friends" -> "Ya sois amigos."
                            else -> "Solicitud enviada a ${res.displayName ?: r.name}."
                        }
                        toast(ctx, msg)
                    }
                    "group" -> { graph.social.joinGroup(code); toast(ctx, "Te has unido a ${r.name}.") }
                    "convoy" -> { val id = graph.convoys.join(code); nav.navigate("convoy/$id") }
                }
            }
        }
    }
    if (confirmRegenerate) ConfirmDialog(
        "¿Cambiar tu código?",
        "El código actual dejará de funcionar. Tus amigos actuales no se verán afectados.",
        "Cambiar", onDismiss = { confirmRegenerate = false },
    ) { runner.run { graph.profile.regenerateInviteCode() } }
    if (createGroup) TextInputDialog("Nuevo grupo", "Nombre del grupo", confirm = "Crear", onDismiss = { createGroup = false }) { name ->
        runner.run { val r = graph.social.createGroup(name); openGroup = r.groupId }
    }
    openGroup?.let { gid ->
        social.groups.firstOrNull { it.group.id == gid }?.let { g -> GroupDialog(graph, g, onDismiss = { openGroup = null }) }
    }
}

@Composable
private fun MyCodeCard(code: String?, onCopy: (String) -> Unit, onShare: (String) -> Unit, onRegenerate: () -> Unit) {
    SettingCard {
        Column(Modifier.padding(16.dp)) {
            Text("TU CÓDIGO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    code?.let { InviteCode.format(it) } ?: "········",
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                    color = Brand.Volt, modifier = Modifier.weight(1f),
                )
                if (code != null) {
                    IconButton({ onCopy(code) }) { Icon(Icons.Filled.ContentCopy, "Copiar") }
                    IconButton(onRegenerate) { Icon(Icons.Outlined.Autorenew, "Cambiar código") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button({ code?.let(onShare) }, Modifier.fillMaxWidth(), enabled = code != null) {
                Icon(Icons.Filled.Share, null); Spacer(Modifier.width(8.dp)); Text("Invitar a un amigo")
            }
        }
    }
}

@Composable
private fun PersonRow(name: String, avatar: String?, subtitle: String, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(name, avatar, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
private fun FriendRow(f: Friend, live: app.trazo.data.remote.LiveLocationDto?, now: Long, onClick: () -> Unit) {
    val t = live?.let { isoToMillis(it.recordedAt) ?: isoToMillis(it.updatedAt) }
    val fresh = when {
        live == null || live.lat == null -> if (live?.sharing == false) Freshness.PAUSED else Freshness.HIDDEN
        else -> LocationFreshness.classify(t, now, paused = !live.sharing || live.state == "paused")
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(f.profile.displayName, f.profile.avatarUrl, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.profile.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FreshnessBadge(fresh, t?.let { LocationFreshness.ageLabel(it, now) } ?: "sin ubicación compartida")
            f.vehicle?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DirectionsCar, null, Modifier.padding(end = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (live?.state == "driving" && fresh == Freshness.LIVE) {
            Text("En ruta", style = MaterialTheme.typography.labelMedium, color = Brand.Live)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun GroupDialog(graph: AppGraph, g: GroupInfo, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val runner = rememberRunner()
    val me = graph.backend.userIdOrNull()
    val mine = g.members.firstOrNull { it.userId == me }
    val owner = g.group.ownerId == me
    var code by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    LaunchedEffect(g.group.id) { runCatching { code = graph.social.groupCode(g.group.id) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(g.group.name) },
        text = {
            Column {
                runner.error?.let { ErrorBanner(it); Spacer(Modifier.height(8.dp)) }
                Text("Código: ${code?.let { InviteCode.format(it) } ?: "…"}", fontFamily = FontFamily.Monospace)
                Row {
                    TextButton({ code?.let { shareText(ctx, "Grupo de Trazo", inviteMessage(InviteCode.Kind.GROUP, it, g.group.name)) } }) {
                        Icon(Icons.Filled.Share, null); Spacer(Modifier.width(4.dp)); Text("Invitar")
                    }
                    if (owner) TextButton({ runner.run { code = graph.social.regenerateGroupCode(g.group.id) } }) { Text("Nuevo código") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Compartir mi ubicación con el grupo", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(mine?.shareLocation ?: true, { v -> runner.run { graph.social.setGroupSharing(g.group.id, v) } })
                }
                Spacer(Modifier.height(8.dp))
                Text("Miembros", style = MaterialTheme.typography.titleSmall)
                g.members.forEach { m ->
                    val p = g.profiles[m.userId]
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p?.displayName ?: "?", p?.avatarUrl, 32.dp)
                        Spacer(Modifier.width(8.dp))
                        Text((p?.displayName ?: "Miembro") + if (m.role == "owner") " · admin" else "", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        if (owner && m.userId != me) {
                            TextButton({ runner.run { graph.social.removeGroupMember(g.group.id, m.userId) } }) { Text("Quitar") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Cerrar") } },
        dismissButton = {
            OutlinedButton({ confirmLeave = true }) { Text("Salir del grupo") }
        },
    )
    if (confirmLeave) ConfirmDialog(
        "¿Salir de ${g.group.name}?",
        if (owner) "Eres el administrador. Si sales, el grupo pasará a otro miembro o se eliminará si no queda nadie." else "Dejarás de ver a sus miembros y ellos a ti.",
        "Salir", destructive = true, onDismiss = { confirmLeave = false },
    ) { runner.run(onDone = onDismiss) { graph.social.leaveGroup(g.group.id) } }
}
