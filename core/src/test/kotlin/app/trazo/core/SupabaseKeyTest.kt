package app.trazo.core

import app.trazo.core.backend.SupabaseKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupabaseKeyTest {
    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray()) + "." +
            enc.encodeToString(payload.toByteArray()) + ".c2lnbmF0dXJl"
    }

    @Test fun anonJwtIsPublic() =
        assertEquals(SupabaseKey.Kind.PUBLIC, SupabaseKey.classify(jwt("""{"iss":"supabase","ref":"abc","role":"anon"}""")))

    @Test fun serviceRoleJwtIsSecret() =
        assertEquals(SupabaseKey.Kind.SECRET, SupabaseKey.classify(jwt("""{"iss":"supabase","role":"service_role"}""")))

    @Test fun newKeyFormats() {
        assertEquals(SupabaseKey.Kind.PUBLIC, SupabaseKey.classify("sb_publishable_AbCdEf123456789_xyz"))
        assertEquals(SupabaseKey.Kind.SECRET, SupabaseKey.classify("sb_secret_AbCdEf123456789_xyz"))
    }

    @Test fun garbageIsUnknown() {
        assertEquals(SupabaseKey.Kind.UNKNOWN, SupabaseKey.classify("hola"))
        assertEquals(SupabaseKey.Kind.UNKNOWN, SupabaseKey.classify("a.b.c"))
    }

    @Test fun projectUrl() {
        assertTrue(SupabaseKey.isProjectUrl("https://abcdxyz.supabase.co"))
        assertFalse(SupabaseKey.isProjectUrl("https://"))
        assertFalse(SupabaseKey.isProjectUrl("http://abcdxyz.supabase.co"))
    }
}
