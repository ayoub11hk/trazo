package app.trazo.data.repo

import app.trazo.data.remote.Backend
import app.trazo.data.remote.ConvoyInviteDto
import app.trazo.data.remote.CreatedWithCode
import app.trazo.data.remote.FriendshipDto
import app.trazo.data.remote.GroupDto
import app.trazo.data.remote.GroupMemberDto
import app.trazo.data.remote.LeaderboardRow
import app.trazo.data.remote.millisToIso
import app.trazo.data.remote.ProfileDto
import app.trazo.data.remote.ResolvedCode
import app.trazo.data.remote.SendRequestResult
import app.trazo.data.remote.VehicleDto
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class Friend(
    val profile: ProfileDto,
    /** What I share with this friend. */
    val mine: FriendshipDto,
    /** What this friend shares with me (null if not readable). */
    val theirs: FriendshipDto?,
    val vehicle: VehicleDto?,
)

@Serializable
data class PendingRequest(
    val id: String,
    val incoming: Boolean,
    @SerialName("other_id") val otherId: String,
    @SerialName("other_name") val otherName: String,
    @SerialName("other_avatar") val otherAvatar: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

data class GroupInfo(val group: GroupDto, val members: List<GroupMemberDto>, val profiles: Map<String, ProfileDto>)

data class SocialSnapshot(
    val friends: List<Friend> = emptyList(),
    val requests: List<PendingRequest> = emptyList(),
    val groups: List<GroupInfo> = emptyList(),
    val convoyInvites: List<ConvoyInviteDto> = emptyList(),
    /** Every profile/vehicle visible to me (friends, group mates, convoy mates). */
    val people: Map<String, ProfileDto> = emptyMap(),
    val vehicles: Map<String, VehicleDto> = emptyMap(),
    val loaded: Boolean = false,
)

sealed interface SocialEvent {
    data class RequestReceived(val request: PendingRequest) : SocialEvent
    data class RequestAccepted(val name: String) : SocialEvent
    data class ConvoyInvite(val invite: ConvoyInviteDto, val fromName: String?) : SocialEvent
}

class SocialRepository(private val backend: Backend, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(SocialSnapshot())
    val state: StateFlow<SocialSnapshot> = _state
    private val _events = MutableSharedFlow<SocialEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SocialEvent> = _events
    private var channel: RealtimeChannel? = null
    private var listenJob: Job? = null

    suspend fun refresh() {
        val c = backend.client()
        val me = backend.requireUserId()
        val friendships = c.from("friendships").select().decodeList<FriendshipDto>()
        val mine = friendships.filter { it.userId == me }
        val theirs = friendships.filter { it.friendId == me }.associateBy { it.userId }
        val people = c.from("profiles").select().decodeList<ProfileDto>().associateBy { it.id }
        val vehicles = c.from("vehicles").select().decodeList<VehicleDto>().associateBy { it.id }
        val requests = c.postgrest.rpc("pending_requests").decodeList<PendingRequest>()
        val groups = c.from("groups").select().decodeList<GroupDto>()
        val members = c.from("group_members").select().decodeList<GroupMemberDto>().groupBy { it.groupId }
        val invites = c.from("convoy_invites").select { filter { eq("to_id", me); eq("status", "pending") } }
            .decodeList<ConvoyInviteDto>()
        val friends = mine.mapNotNull { f ->
            val p = people[f.friendId] ?: return@mapNotNull null
            Friend(p, f, theirs[f.friendId], p.activeVehicleId?.let { vehicles[it] })
        }.sortedBy { it.profile.displayName.lowercase() }
        _state.value = SocialSnapshot(
            friends = friends,
            requests = requests,
            groups = groups.map { g -> GroupInfo(g, members[g.id].orEmpty(), people) },
            convoyInvites = invites,
            people = people,
            vehicles = vehicles,
            loaded = true,
        )
    }

    fun clear() {
        stopListening()
        _state.value = SocialSnapshot()
    }

    /** Live updates for requests, friendships and convoy invites while the app or tracking runs. */
    fun startListening() {
        if (listenJob != null) return
        listenJob = scope.launch {
            runCatching {
                val c = backend.client()
                val me = backend.requireUserId()
                val ch = c.channel("social-$me")
                val req = ch.postgresChangeFlow<PostgresAction>(schema = "public") { table = "friend_requests" }
                val fr = ch.postgresChangeFlow<PostgresAction>(schema = "public") { table = "friendships" }
                val inv = ch.postgresChangeFlow<PostgresAction>(schema = "public") { table = "convoy_invites" }
                merge(req, fr, inv).onEach { action ->
                    val before = _state.value
                    runCatching { refresh() }
                    val after = _state.value
                    if (action is PostgresAction.Insert) {
                        after.requests.filter { r -> r.incoming && before.requests.none { it.id == r.id } }
                            .forEach { _events.tryEmit(SocialEvent.RequestReceived(it)) }
                        after.convoyInvites.filter { i -> before.convoyInvites.none { it.id == i.id } }
                            .forEach { _events.tryEmit(SocialEvent.ConvoyInvite(it, after.people[it.fromId]?.displayName)) }
                        after.friends.filter { f -> before.friends.none { it.profile.id == f.profile.id } && before.loaded }
                            .forEach { _events.tryEmit(SocialEvent.RequestAccepted(it.profile.displayName)) }
                    }
                }.launchIn(this)
                ch.subscribe()
                channel = ch
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        val ch = channel
        channel = null
        if (ch != null) scope.launch { runCatching { backend.client().realtime.removeChannel(ch) } }
    }

    suspend fun myInviteCode(): String = backend.client().postgrest.rpc("my_invite_code").decodeAs<String>()

    suspend fun resolve(code: String): ResolvedCode? =
        backend.client().postgrest.rpc("resolve_invite_code", buildJsonObject { put("p_code", code) })
            .decodeList<ResolvedCode>().firstOrNull()

    suspend fun sendRequest(code: String): SendRequestResult {
        val r = backend.client().postgrest.rpc("send_friend_request", buildJsonObject { put("p_code", code) })
            .decodeList<SendRequestResult>().first()
        refresh()
        return r
    }

    suspend fun respond(requestId: String, accept: Boolean) {
        backend.client().postgrest.rpc("respond_friend_request", buildJsonObject {
            put("p_request", requestId); put("p_accept", accept)
        })
        refresh()
    }

    suspend fun cancelRequest(requestId: String) {
        backend.client().postgrest.rpc("cancel_friend_request", buildJsonObject { put("p_request", requestId) })
        refresh()
    }

    suspend fun removeFriend(friendId: String) {
        backend.client().postgrest.rpc("remove_friend", buildJsonObject { put("p_friend", friendId) })
        refresh()
    }

    /** Changes only what *I* share with this friend. */
    suspend fun setSharing(friendId: String, shareLocation: Boolean? = null, shareTrips: Boolean? = null) {
        val me = backend.requireUserId()
        backend.client().from("friendships").update(buildJsonObject {
            shareLocation?.let { put("share_location", it) }
            shareTrips?.let { put("share_trips", it) }
        }) { filter { eq("user_id", me); eq("friend_id", friendId) } }
        refresh()
    }

    suspend fun createGroup(name: String): CreatedWithCode {
        val r = backend.client().postgrest.rpc("create_group", buildJsonObject { put("p_name", name) })
            .decodeList<CreatedWithCode>().first()
        refresh()
        return r
    }

    suspend fun groupCode(groupId: String): String =
        backend.client().postgrest.rpc("group_invite_code", buildJsonObject { put("p_group", groupId) }).decodeAs<String>()

    suspend fun regenerateGroupCode(groupId: String): String =
        backend.client().postgrest.rpc("regenerate_group_code", buildJsonObject { put("p_group", groupId) }).decodeAs<String>()

    suspend fun joinGroup(code: String): String {
        val id = backend.client().postgrest.rpc("join_group", buildJsonObject { put("p_code", code) }).decodeAs<String>()
        refresh()
        return id
    }

    suspend fun leaveGroup(groupId: String) {
        backend.client().postgrest.rpc("leave_group", buildJsonObject { put("p_group", groupId) })
        refresh()
    }

    suspend fun removeGroupMember(groupId: String, userId: String) {
        backend.client().postgrest.rpc("remove_group_member", buildJsonObject { put("p_group", groupId); put("p_user", userId) })
        refresh()
    }

    suspend fun setGroupSharing(groupId: String, share: Boolean) {
        val me = backend.requireUserId()
        backend.client().from("group_members").update(buildJsonObject { put("share_location", share) }) {
            filter { eq("group_id", groupId); eq("user_id", me) }
        }
        refresh()
    }

    /** Me plus friends who share trips and statistics with me; simulated trips never count. */
    suspend fun leaderboard(sinceMs: Long, untilMs: Long): List<LeaderboardRow> =
        backend.client().postgrest.rpc("leaderboard", buildJsonObject {
            put("p_since", millisToIso(sinceMs)); put("p_until", millisToIso(untilMs))
        }).decodeList<LeaderboardRow>()
}
