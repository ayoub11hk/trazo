package app.trazo.data.repo

import app.trazo.data.remote.Backend
import app.trazo.data.remote.ConvoyDto
import app.trazo.data.remote.ConvoyMemberDto
import app.trazo.data.remote.CreatedWithCode
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ConvoyInfo(val convoy: ConvoyDto, val members: List<ConvoyMemberDto>) {
    val active: Boolean get() = convoy.status == "active"
}

class ConvoyRepository(private val backend: Backend) {
    private val _convoys = MutableStateFlow<List<ConvoyInfo>>(emptyList())
    val convoys: StateFlow<List<ConvoyInfo>> = _convoys

    /** The active convoy I am currently in (trips recorded now are tagged with it). */
    val activeConvoyId: String?
        get() = _convoys.value.firstOrNull { info ->
            info.active && info.members.any { it.userId == myId && it.leftAt == null }
        }?.convoy?.id

    private var myId: String? = null

    suspend fun refresh() {
        val c = backend.client()
        myId = backend.requireUserId()
        val convoys = c.from("convoys").select { order("created_at", Order.DESCENDING) }.decodeList<ConvoyDto>()
        val members = c.from("convoy_members").select().decodeList<ConvoyMemberDto>().groupBy { it.convoyId }
        _convoys.value = convoys.filter { members[it.id]?.any { m -> m.userId == myId } == true }
            .map { ConvoyInfo(it, members[it.id].orEmpty()) }
    }

    fun clear() { _convoys.value = emptyList() }

    suspend fun create(name: String): CreatedWithCode {
        val r = backend.client().postgrest.rpc("create_convoy", buildJsonObject { put("p_name", name) })
            .decodeList<CreatedWithCode>().first()
        refresh()
        return r
    }

    suspend fun code(convoyId: String): String =
        backend.client().postgrest.rpc("convoy_invite_code", buildJsonObject { put("p_convoy", convoyId) }).decodeAs<String>()

    suspend fun join(code: String): String {
        val id = backend.client().postgrest.rpc("join_convoy", buildJsonObject { put("p_code", code) }).decodeAs<String>()
        refresh()
        return id
    }

    suspend fun invite(convoyId: String, friendId: String) {
        backend.client().postgrest.rpc("invite_to_convoy", buildJsonObject { put("p_convoy", convoyId); put("p_friend", friendId) })
    }

    suspend fun respondInvite(inviteId: String, accept: Boolean): String? {
        val r = backend.client().postgrest.rpc("respond_convoy_invite", buildJsonObject {
            put("p_invite", inviteId); put("p_accept", accept)
        }).decodeAs<String?>()
        refresh()
        return r
    }

    suspend fun leave(convoyId: String) {
        backend.client().postgrest.rpc("leave_convoy", buildJsonObject { put("p_convoy", convoyId) })
        refresh()
    }

    suspend fun end(convoyId: String) {
        backend.client().postgrest.rpc("end_convoy", buildJsonObject { put("p_convoy", convoyId) })
        refresh()
    }

    suspend fun setShareTrip(convoyId: String, share: Boolean) {
        val me = backend.requireUserId()
        backend.client().from("convoy_members").update(buildJsonObject { put("share_trip", share) }) {
            filter { eq("convoy_id", convoyId); eq("user_id", me) }
        }
        refresh()
    }
}
