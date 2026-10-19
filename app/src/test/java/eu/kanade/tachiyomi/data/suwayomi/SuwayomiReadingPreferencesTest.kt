// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

class SuwayomiReadingPreferencesTest {
    @Test
    fun `upload only contains the versioned reading allowlist`() = runBlocking {
        val reader = mockk<ReaderPreferences>()
        val mode = mockk<Preference<Int>>(relaxed = true)
        val crop = mockk<Preference<Boolean>>(relaxed = true)
        every { reader.defaultReadingMode() } returns mode
        every { mode.get() } returns 1
        every { reader.cropBorders() } returns crop
        every { reader.cropBordersWebtoon() } returns crop
        every { reader.cropBordersContinuousVertical() } returns crop
        every { crop.get() } returns true
        val session = mockk<SuwayomiSession>()
        val request = slot<JsonObject>()
        coEvery { session.execute(any(), capture(request)) } returns JsonObject(emptyMap())
        SuwayomiReadingPreferences(reader).upload(session)
        val keys = request.captured.getValue("input").jsonObject.getValue("metas").jsonArray.map { it.jsonObject.getValue("key").jsonPrimitive.content }.toSet()
        assertEquals(setOf("komikku.reader.v1.defaultMode", "komikku.reader.v1.cropPager", "komikku.reader.v1.cropWebtoon", "komikku.reader.v1.cropContinuous"), keys)
    }

    @Test
    fun `apply ignores device controls and invalid values from metadata`() = runBlocking {
        val reader = mockk<ReaderPreferences>()
        val mode = mockk<Preference<Int>>(relaxed = true)
        val crop = mockk<Preference<Boolean>>(relaxed = true)
        every { reader.defaultReadingMode() } returns mode
        every { reader.cropBorders() } returns crop
        every { reader.cropBordersWebtoon() } returns crop
        every { reader.cropBordersContinuousVertical() } returns crop
        val session = mockk<SuwayomiSession>()
        coEvery { session.execute(any(), any()) } returns Json.parseToJsonElement("""{"metas":{"nodes":[{"key":"komikku.reader.v1.defaultMode","value":"999"},{"key":"komikku.reader.v1.cropPager","value":"true"},{"key":"komikku.reader.v1.orientation","value":"4"},{"key":"komikku.reader.v1.brightness","value":"100"}],"totalCount":4,"pageInfo":{"hasNextPage":false}}}""").jsonObject
        SuwayomiReadingPreferences(reader).apply(session)
        verify(exactly = 0) { mode.set(any()) }
        verify(exactly = 1) { crop.set(true) }
        verify(exactly = 0) { reader.defaultOrientationType() }
    }
}
// KMK <--
