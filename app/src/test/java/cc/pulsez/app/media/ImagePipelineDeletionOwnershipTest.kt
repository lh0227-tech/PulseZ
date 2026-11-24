package cc.pulsez.app.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.data.db.AttachmentStatus
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.AttachmentRepository
import cc.pulsez.app.data.repo.CharacterDeletionRepository
import cc.pulsez.app.data.repo.CharacterRepository
import cc.pulsez.app.data.repo.ChatRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Offline end-to-end coverage for camera/picker drafts flowing through role deletion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImagePipelineDeletionOwnershipTest {

    private lateinit var context: Context
    private lateinit var db: PulsezDatabase
    private lateinit var store: AttachmentStore
    private lateinit var storeRoot: File
    private lateinit var sourceDir: File
    private lateinit var attachments: AttachmentRepository
    private lateinit var characters: CharacterRepository
    private lateinit var chat: ChatRepository
    private lateinit var pipeline: ImagePipeline
    private var previousStore: AttachmentStore? = null

    @Before
    fun setUp() {
        previousStore = AttachmentStore.installedOrNull()
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        PulsezDatabase.installRoleDeletionGuards(db.openHelper.writableDatabase)
        storeRoot = File(context.cacheDir, "pipeline-delete-store-${System.nanoTime()}").apply { mkdirs() }
        sourceDir = File(context.cacheDir, "pipeline-delete-source-${System.nanoTime()}").apply { mkdirs() }
        store = AttachmentStore(storeRoot)
        AttachmentStore.install(store)
        attachments = AttachmentRepository(db)
        characters = CharacterRepository(db)
        chat = ChatRepository(db)
        pipeline = ImagePipeline(context, attachments)
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        previousStore?.let { AttachmentStore.install(it) } ?: AttachmentStore.resetForTest()
        storeRoot.deleteRecursively()
        sourceDir.deleteRecursively()
    }

    @Test
    fun cameraAndPickedDraftsAreDeletedWithTheirSingleChatWhileSharedAndLegacyFilesStay() =
        kotlinx.coroutines.runBlocking {
            val (deletedRole, singleThread) = characters.createWithThread("pipeline target", "synthetic persona")
            val (keptRole, _) = characters.createWithThread("pipeline kept", "synthetic persona")
            val group = chat.createGroupThread(listOf(deletedRole.id, keptRole.id), "shared fixture")
            val groupMessage = chat.append(
                threadId = group.id,
                segmentId = requireNotNull(group.activeSegmentId),
                role = "user",
                text = "synthetic shared group message",
                tokens = 1,
            )
            val jpeg = makeJpeg()

            val cameraFile = File(sourceDir, "capture.jpg").apply { writeBytes(jpeg) }
            val cameraDraft = pipeline.importCamera(cameraFile, ownerThreadId = singleThread.id).attachment

            // Android accepts content:// in openInputStream; Robolectric registers its synthetic input stream.
            // Basis: https://developer.android.com/reference/android/content/ContentResolver(android.net.Uri)
            // and https://robolectric.org/javadoc/latest/org/robolectric/shadows/ShadowContentResolver.html
            val pickedUri = Uri.parse("content://test.gallery/item/1")
            Shadows.shadowOf(context.contentResolver)
                .registerInputStream(pickedUri, ByteArrayInputStream(jpeg))
            val pickedDraft = pipeline.importPicked(pickedUri, ownerThreadId = singleThread.id).attachment

            assertEquals(singleThread.id, cameraDraft.ownerThreadId)
            assertEquals(singleThread.id, pickedDraft.ownerThreadId)
            assertEquals(AttachmentStatus.READY, cameraDraft.status)
            assertEquals(AttachmentStatus.READY, pickedDraft.status)
            assertTrue(store.exists(cameraDraft.localPath))
            assertTrue(store.exists(pickedDraft.localPath))
            assertFalse("相机临时文件由管线清理", cameraFile.exists())

            val groupDraft = attachments.createDraft(
                store = store,
                kind = "IMAGE",
                mime = "image/jpeg",
                bytes = jpeg,
                ownerThreadId = group.id,
            )
            attachments.markReady(groupDraft.id)
            attachments.bindToMessage(listOf(groupDraft.id), groupMessage.id)

            // Legacy rows with no ownerThreadId cannot be attributed safely and must remain untouched.
            val legacyDraft = attachments.createDraft(
                store = store,
                kind = "IMAGE",
                mime = "image/jpeg",
                bytes = jpeg,
                id = "legacy-unowned-image-draft",
            )
            attachments.markReady(legacyDraft.id)

            val result = CharacterDeletionRepository(db).deleteCharacters(listOf(deletedRole.id), store)

            assertEquals(1, result.deletedCount)
            assertEquals(0, result.pendingAttachmentFiles)
            assertNull(db.threadDao().byId(singleThread.id))
            assertNull(db.attachmentDao().byId(cameraDraft.id))
            assertNull(db.attachmentDao().byId(pickedDraft.id))
            assertFalse(store.exists(cameraDraft.localPath))
            assertFalse(store.exists(pickedDraft.localPath))
            assertNotNull(db.characterDao().byId(keptRole.id))
            assertEquals(listOf(groupMessage.id), db.messageDao().allForThread(group.id).map { it.id })
            assertEquals(listOf(keptRole.id), db.threadMemberDao().membersOf(group.id).map { it.characterId })
            assertEquals(groupMessage.id, db.attachmentDao().byId(groupDraft.id)?.messageId)
            assertEquals(group.id, db.attachmentDao().byId(groupDraft.id)?.ownerThreadId)
            assertTrue(store.exists(groupDraft.localPath))
            assertNull(db.attachmentDao().byId(legacyDraft.id)?.ownerThreadId)
            assertNull(db.attachmentDao().byId(legacyDraft.id)?.messageId)
            assertTrue(store.exists(legacyDraft.localPath))
        }

    @Test
    fun chatViewModelPassesTheCurrentThreadToBothImageImportPaths() {
        val source = File(CHAT_VIEW_MODEL_PATH).readText(Charsets.UTF_8)
        val picked = source.substringAfter("fun onImagePicked(uri: Uri)")
            .substringBefore("fun onCameraCaptured(file: File)")
        val camera = source.substringAfter("fun onCameraCaptured(file: File)")
            .substringBefore("fun onCameraCancelled(file: File)")
        assertTrue(picked.contains("val ownerThreadId = threadId"))
        assertTrue(picked.contains("imagePipeline.importPicked(uri, ownerThreadId = ownerThreadId)"))
        assertTrue(camera.contains("val ownerThreadId = threadId"))
        assertTrue(camera.contains("imagePipeline.importCamera(file, ownerThreadId = ownerThreadId)"))
    }

    private fun makeJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().also { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val CHAT_VIEW_MODEL_PATH = "src/main/java/cc/pulsez/app/ui/chat/ChatViewModel.kt"
    }
}
