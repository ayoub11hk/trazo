package app.trazo.data.repo

import app.trazo.data.local.VehicleDao
import app.trazo.data.local.VehicleEntity
import app.trazo.data.remote.Backend
import app.trazo.data.remote.VehicleDto
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Garage: unlimited vehicles, stored locally first and synced. */
class VehicleRepository(private val dao: VehicleDao, private val backend: Backend) {
    fun observe(): Flow<List<VehicleEntity>> = dao.observeAll()
    suspend fun all() = dao.all()
    suspend fun get(id: String) = dao.get(id)

    suspend fun save(
        id: String?, name: String, make: String?, model: String?, year: Int?, color: String, bodyType: String,
    ): String {
        val vid = id ?: UUID.randomUUID().toString()
        val existing = id?.let { dao.get(it) }
        dao.upsert(
            VehicleEntity(
                id = vid, ownerId = existing?.ownerId ?: backend.userIdOrNull(), name = name.trim().take(40),
                make = make?.trim()?.ifEmpty { null }, model = model?.trim()?.ifEmpty { null }, year = year,
                color = color, bodyType = bodyType, photoUrl = existing?.photoUrl, dirty = true,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return vid
    }

    suspend fun delete(id: String) {
        val v = dao.get(id) ?: return
        dao.upsert(v.copy(deleted = true, dirty = false, updatedAt = System.currentTimeMillis()))
    }

    suspend fun sync() {
        val c = backend.client()
        val me = backend.requireUserId()
        for (v in dao.tombstones()) {
            c.from("vehicles").delete { filter { eq("id", v.id) } }
            dao.purge(v.id)
        }
        for (v in dao.dirty()) {
            c.from("vehicles").upsert(
                VehicleDto(v.id, me, v.name, v.make, v.model, v.year, v.color, v.bodyType, v.photoUrl),
            ) { onConflict = "id" }
            if (v.ownerId == null) dao.upsert(v.copy(ownerId = me))
            dao.markClean(v.id, v.updatedAt)
        }
    }

    suspend fun pull() {
        val me = backend.requireUserId()
        val remote = backend.client().from("vehicles").select { filter { eq("owner_id", me) } }.decodeList<VehicleDto>()
        for (r in remote) {
            if (dao.get(r.id) == null) dao.upsert(
                VehicleEntity(r.id, r.ownerId, r.name, r.make, r.model, r.year, r.color, r.bodyType, r.photoUrl, dirty = false),
            )
        }
    }

    suspend fun clearLocal() = dao.purgeAll()
}
