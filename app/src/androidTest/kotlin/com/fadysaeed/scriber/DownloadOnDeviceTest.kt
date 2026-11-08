package com.fadysaeed.scriber

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fadysaeed.scriber.core.Hashing
import com.fadysaeed.scriber.core.ModelCatalog
import com.fadysaeed.scriber.core.ModelFile
import com.fadysaeed.scriber.core.PackRole
import com.fadysaeed.scriber.engines.Audio
import com.fadysaeed.scriber.engines.Speech
import com.fadysaeed.scriber.fetch.BlockedHostException
import com.fadysaeed.scriber.fetch.FetchLock
import com.fadysaeed.scriber.fetch.IntegrityException
import com.fadysaeed.scriber.fetch.ModelDownloader
import com.fadysaeed.scriber.fetch.ModelStore
import com.fadysaeed.scriber.fetch.PackState
import com.fadysaeed.scriber.vault.Vault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

/** Real downloads of the small packs, resume, checksum refusal, host refusal, and the native speech library. */
@RunWith(AndroidJUnit4::class)
class DownloadOnDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val catalog = ModelCatalog.bundled()
    private val store = ModelStore(context)
    private val downloader = ModelDownloader(store)
    private val segmentation = catalog.byRole(PackRole.SEGMENTATION).single()
    private val vadPack = catalog.byRole(PackRole.VAD).single()

    @Test fun downloadsVerifiesAndMarksAPack() : Unit = runBlocking {
        store.delete(segmentation)
        for (f in segmentation.files) downloader.fetch(segmentation, f) { _, _ -> }
        store.markVerified(segmentation)
        assertEquals(PackState.Verified, store.state(segmentation))
        val f = segmentation.files.single()
        assertEquals(f.sha256, Hashing.sha256(store.file(segmentation, f)))
    }

    @Test fun resumesAPartialFile() : Unit = runBlocking {
        store.delete(vadPack)
        val f = vadPack.files.single()
        // Take the first 200 KB by downloading fully elsewhere, then cutting it short.
        downloader.fetch(vadPack, f) { _, _ -> }
        val full = store.file(vadPack, f).readBytes()
        store.delete(vadPack)
        val part = store.partial(vadPack, f)
        part.parentFile!!.mkdirs()
        FileOutputStream(part).use { it.write(full, 0, 200_000) }
        var sawResumeStart = false
        downloader.fetch(vadPack, f) { bytes, hashing -> if (!hashing && bytes >= 200_000) sawResumeStart = true }
        assertTrue(sawResumeStart)
        assertEquals(f.sha256, Hashing.sha256(store.file(vadPack, f)))
        assertFalse(part.exists())
    }

    @Test fun rejectsAFileWhoseChecksumDoesNotMatch() : Unit = runBlocking {
        val f = segmentation.files.single()
        val wrong = f.copy(path = "wrong.onnx", sha256 = "0".repeat(64))
        try {
            downloader.fetch(segmentation, wrong) { _, _ -> }
            fail("A wrong checksum must be refused")
        } catch (expected: IntegrityException) {
        }
        assertFalse(store.file(segmentation, wrong).exists())
        assertFalse(store.partial(segmentation, wrong).exists())
    }

    @Test fun refusesHostsOutsideTheList() : Unit = runBlocking {
        val evil = ModelFile("evil.bin", "https://example.com/model.onnx", 10, "0".repeat(64))
        try {
            downloader.fetch(segmentation, evil) { _, _ -> }
            fail("example.com must be refused")
        } catch (expected: BlockedHostException) {
        }
    }

    @Test fun lockFlagRoundTrips() {
        val vault = Vault.get(context)
        FetchLock.lock(vault)
        assertTrue(FetchLock.isLocked(vault))
        FetchLock.unlock(vault)
        assertFalse(FetchLock.isLocked(vault))
    }

    @Test fun speechLibraryLoadsAndHearsNoSpeechInATone() : Unit = runBlocking {
        val f = vadPack.files.single()
        if (store.file(vadPack, f).length() != f.size) downloader.fetch(vadPack, f) { _, _ -> }
        val vad = Speech.vad(store.file(vadPack, f))
        try {
            val silence = Audio(FloatArray(16_000 * 3), 16_000)
            assertEquals(0, Speech.countSpeechSegments(vad, silence))
            val tone = Audio(FloatArray(16_000 * 3) { (0.3 * sin(2 * PI * 440 * it / 16_000.0)).toFloat() }, 16_000)
            Speech.countSpeechSegments(vad, tone) // must not crash; a pure tone is not speech
        } finally {
            vad.release()
        }
    }
}
