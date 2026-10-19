// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SuwayomiAdministrationTest {
    @Test
    fun `extension details use package identity and retain every source language`() = runBlocking<Unit> {
        val session = mockk<SuwayomiSession>()
        coEvery { session.execute(any(), any()) } returns Json.parseToJsonElement("""{"extension":{"source":{"nodes":[{"id":"7","name":"Provider","lang":"en","iconUrl":"/icon.png","supportsLatest":true},{"id":"8","name":"Provider","lang":"fr","iconUrl":"/icon.png","supportsLatest":false}]}}}""").jsonObject
        val sources = session.extensionSources("provider.package")
        assertEquals(listOf("en", "fr"), sources.map { it.lang })
        coVerify {
            session.execute(match { it.contains("extension(pkgName:\$pkgName)") }, match { it["pkgName"] == JsonPrimitive("provider.package") })
        }
    }

    @Test
    fun `extension actions enforce server permissions before sending mutation`() = runBlocking<Unit> {
        val session = mockk<SuwayomiSession>()
        coEvery { session.user() } returns ServerUser(1, "Reader", emptyList(), emptyList())
        for (action in listOf("install", "update", "uninstall")) {
            assertThrows(SuwayomiException::class.java) { runBlocking { session.changeExtension("provider.package", action) } }
        }
        coVerify(exactly = 0) { session.execute(any(), any()) }
    }

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

    @Test
    fun `download reordering uses chapter identity and zero based queue position`() = runBlocking<Unit> {
        val session = mockk<SuwayomiSession>()
        coEvery { session.execute(any(), any()) } returns Json.parseToJsonElement("""{"reorderChapterDownload":{"clientMutationId":null}}""").jsonObject
        session.downloadAction("reorder", 47, 0)
        coVerify { session.execute(match { it.contains("reorderChapterDownload") }, match { it.toString() == "{\"input\":{\"chapterId\":47,\"to\":0}}" }) }
    }
}
// KMK <--
