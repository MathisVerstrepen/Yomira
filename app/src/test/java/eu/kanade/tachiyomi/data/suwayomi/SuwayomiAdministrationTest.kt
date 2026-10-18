// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SuwayomiAdministrationTest {
    @Test
    fun `changed source preference position cannot write a different setting`() = runBlocking<Unit> {
        val session = mockk<SuwayomiSession>()
        coEvery { session.user() } returns ServerUser(1, "User", listOf("MANAGE_SOURCE_PREFERENCES"), emptyList())
        coEvery { session.execute(any(), any()) } returns Json.parseToJsonElement("""{"source":{"preferences":[{"__typename":"SwitchPreference","key":"new-key"}]}}""").jsonObject
        val original = ServerPreference("SwitchPreference", key = "old-key")
        assertThrows(SuwayomiException::class.java) { runBlocking { session.changeSourcePreference(7, 0, original, "switchState", JsonPrimitive(true)) } }
        coVerify(exactly = 0) { session.execute(match { it.startsWith("mutation") }, any()) }
    }

    @Test
    fun `account without source permission cannot fetch or mutate shared preferences`() = runBlocking<Unit> {
        val session = mockk<SuwayomiSession>()
        coEvery { session.user() } returns ServerUser(1, "User", emptyList(), emptyList())
        assertThrows(SuwayomiException::class.java) { runBlocking { session.changeSourcePreference(7, 0, ServerPreference("SwitchPreference"), "switchState", JsonPrimitive(true)) } }
        coVerify(exactly = 0) { session.execute(any(), any()) }
    }
}
// KMK <--
