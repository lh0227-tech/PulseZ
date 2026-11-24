package cc.pulsez.app.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.view.View
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.ui.chat.CHAT_COMPOSER_INPUT_TEST_TAG
import cc.pulsez.app.ui.group.GROUP_COMPOSER_INPUT_TEST_TAG
import cc.pulsez.app.ui.theme.PulsezTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AppRoot-to-chat inset integration check. All app files are redirected under a unique Robolectric
 * test directory; this test neither opens nor clears the app's default `pulsez.db`.
 *
 * The inset numbers are synthetic and only prove Compose layout response. They do not emulate a
 * device IME implementation or establish that a reported device issue is fixed.
 */
@RunWith(RobolectricTestRunner::class)
// AndroidX preferencesDataStore keeps one INSTANCE per property delegate and binds it to the
// first applicationContext that accesses it. Give this integration test its own Robolectric
// instrumentation configuration/classloader so an earlier test's SettingsStore singleton cannot
// redirect this test's writes. The extra package is an isolation marker; no app class lives there.
// AndroidX PreferenceDataStoreDelegate.kt (1.1.1)
// https://android.googlesource.com/platform/frameworks/support/%2B/4d1afbcddb04c4da9ff788d4ceb6add33f0e45ea/datastore/datastore-preferences/src/androidMain/kotlin/androidx/datastore/preferences/PreferenceDataStoreDelegate.kt
// Robolectric SandboxManager.SandboxKey uses InstrumentationConfiguration; @Config adds these
// instrumented packages to that configuration.
// https://github.com/robolectric/robolectric/blob/robolectric-4.14.1/robolectric/src/main/java/org/robolectric/internal/SandboxManager.java
// https://robolectric.org/javadoc/4.14/org/robolectric/annotation/Config.html
@Config(
    sdk = [34],
    application = Application::class,
    instrumentedPackages = ["cc.pulsez.app.ui.insetsisolated"],
)
class AppRootInsetsIntegrationTest {

    @get:Rule
    val compose = createComposeRule()

    private var isolatedRoot: File? = null
    private var isolatedContext: IsolatedAppContext? = null
    private var container: AppContainer? = null
    private var composeHost: View? = null
    private var density = 1f

    @Test
    fun appRootSingleAndGroupRoutesKeepComposersAboveSyntheticIme() = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val root = File(baseContext.cacheDir, "pulsez-app-root-insets-${UUID.randomUUID()}")
            .apply { check(mkdirs()) { "could not create isolated Robolectric fixture directory" } }
        isolatedRoot = root

        val testContext = IsolatedAppContext(baseContext, root)
        isolatedContext = testContext
        val app = AppContainer(
            testContext,
            secretReader = { null },
            secretRemover = {},
            secretWriter = { _, _ -> },
        )
        container = app

        // AppRoot otherwise shows the first-run agreement. This write goes through the isolated
        // ContextWrapper, whose applicationContext and files directories are test-only.
        app.settings.agree(SettingsStore.AGREEMENT_VERSION)
        val (singleCharacter, _) = app.characters.createWithThread(
            name = SINGLE_ROLE_NAME,
            persona = "Synthetic fixture persona",
        )
        assertFilesAreIsolated(baseContext, root, testContext, app)

        compose.setContent {
            val localView = LocalView.current
            val localDensity = LocalDensity.current.density
            SideEffect {
                composeHost = localView
                density = localDensity
            }
            PulsezTheme {
                AppRoot(container = app)
            }
        }

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(SINGLE_ROLE_NAME).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText(SINGLE_ROLE_NAME).assertHasClickAction().performClick()
        compose.waitForIdle()
        // Wait for both ChatScreen composer and ChatViewModel.load setting ChatTopBar's non-clickable title
        // (and after ThreadsScreen's clickable CharacterCard finishes AnimatedContent exit).
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(CHAT_COMPOSER_INPUT_TEST_TAG)
                .fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(SINGLE_ROLE_NAME)
                    .fetchSemanticsNodes()
                    .let { nodes ->
                        nodes.size == 1 && !nodes.single().config.contains(SemanticsActions.OnClick)
                    }
        }
        // Keep bars/cutout fixed while comparing closed and open IME positions in the same root.
        dispatchSyntheticInsets(imeVisible = false)
        assertComposerInsets(
            inputTag = CHAT_COMPOSER_INPUT_TEST_TAG,
            title = SINGLE_ROLE_NAME,
            routeName = "single chat",
        )

        // Create the group only after the single-chat route check. This keeps the initial home
        // screen uncluttered in Robolectric's 320x470dp default viewport; the group card is then
        // loaded through the same home refresh path used after returning from chat.
        val (memberTwo, _) = app.characters.createWithThread(
            name = SECOND_ROLE_NAME,
            persona = "Synthetic fixture persona",
        )
        app.chat.createGroupThread(
            characterIds = listOf(singleCharacter.id, memberTwo.id),
            title = GROUP_TITLE,
        )

        dispatchNeutralInsets()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(GROUP_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(GROUP_TITLE).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(GROUP_COMPOSER_INPUT_TEST_TAG)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dispatchSyntheticInsets(imeVisible = false)
        assertComposerInsets(
            inputTag = GROUP_COMPOSER_INPUT_TEST_TAG,
            title = null,
            routeName = "group chat",
        )
    }

    private fun assertComposerInsets(inputTag: String, title: String?, routeName: String) {
        compose.waitForIdle()
        val rootSize = compose.runOnIdle {
            val host = requireNotNull(composeHost) { "Compose host was not captured" }
            host.width to host.height
        }
        val widthPx = rootSize.first.toFloat()
        val heightPx = rootSize.second.toFloat()
        val pxPerDp = density

        val closedInput = compose.onNodeWithTag(inputTag).fetchSemanticsNode().boundsInRoot
        val closedSend = compose.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInRoot
        val topBarBounds = if (title != null) {
            compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        } else {
            compose.onNodeWithContentDescription("返回").fetchSemanticsNode().boundsInRoot
        }

        val navBottomPx = NAV_BAR_DP * pxPerDp
        val cutoutLeftPx = CUTOUT_LEFT_DP * pxPerDp
        val cutoutRightPx = CUTOUT_RIGHT_DP * pxPerDp
        val cutoutTopPx = CUTOUT_TOP_DP * pxPerDp
        val imeBottomPx = IME_BOTTOM_DP * pxPerDp
        val tolerancePx = LAYOUT_TOLERANCE_DP * pxPerDp

        assertTrue(
            "$routeName title clears the synthetic status-bar/cutout top inset",
            topBarBounds.top >= cutoutTopPx - tolerancePx,
        )
        assertTrue(
            "$routeName composer clears the synthetic left display cutout",
            closedInput.left >= cutoutLeftPx - tolerancePx,
        )
        assertTrue(
            "$routeName composer clears the synthetic right display cutout",
            closedInput.right <= widthPx - cutoutRightPx + tolerancePx,
        )
        assertTrue(
            "$routeName send button clears navigation bar while IME is closed",
            closedSend.bottom <= heightPx - navBottomPx + tolerancePx,
        )

        dispatchSyntheticInsets(imeVisible = true)
        compose.waitForIdle()
        val openInput = compose.onNodeWithTag(inputTag).fetchSemanticsNode().boundsInRoot
        val openSend = compose.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInRoot

        assertTrue(
            "$routeName input remains above the synthetic IME",
            openInput.bottom <= heightPx - imeBottomPx + tolerancePx,
        )
        assertTrue(
            "$routeName send button remains above the synthetic IME",
            openSend.bottom <= heightPx - imeBottomPx + tolerancePx,
        )

        // systemBars already reserves the 24dp navigation inset. imePadding should add only the
        // uncovered remainder (220dp - 24dp), rather than stacking another full keyboard height.
        val expectedShiftPx = (IME_BOTTOM_DP - NAV_BAR_DP) * pxPerDp
        assertTrue(
            "$routeName IME shift has no duplicate navigation-bar gap; tolerance=${LAYOUT_TOLERANCE_DP}dp",
            kotlin.math.abs((closedSend.bottom - openSend.bottom) - expectedShiftPx) <= tolerancePx,
        )
    }

    private fun dispatchSyntheticInsets(imeVisible: Boolean) {
        val view = composeHost ?: error("Compose host was not captured")
        val pxPerDp = density
        val imeInsetPx = if (imeVisible) (IME_BOTTOM_DP * pxPerDp).toInt() else 0
        val insets = WindowInsetsCompat.Builder()
            .setInsets(
                WindowInsetsCompat.Type.statusBars(),
                Insets.of(0, (STATUS_BAR_DP * pxPerDp).toInt(), 0, 0),
            )
            .setInsets(
                WindowInsetsCompat.Type.navigationBars(),
                Insets.of(0, 0, 0, (NAV_BAR_DP * pxPerDp).toInt()),
            )
            .setInsets(
                WindowInsetsCompat.Type.displayCutout(),
                Insets.of(
                    (CUTOUT_LEFT_DP * pxPerDp).toInt(),
                    (CUTOUT_TOP_DP * pxPerDp).toInt(),
                    (CUTOUT_RIGHT_DP * pxPerDp).toInt(),
                    0,
                ),
            )
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeInsetPx))
            .setVisible(WindowInsetsCompat.Type.statusBars(), true)
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .setVisible(WindowInsetsCompat.Type.displayCutout(), true)
            .setVisible(WindowInsetsCompat.Type.ime(), imeVisible)
            .build()
        compose.runOnIdle { ViewCompat.dispatchApplyWindowInsets(view, insets) }
        compose.waitForIdle()
    }

    private fun dispatchNeutralInsets() {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.NONE)
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.NONE)
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.NONE)
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
            .setVisible(WindowInsetsCompat.Type.statusBars(), false)
            .setVisible(WindowInsetsCompat.Type.navigationBars(), false)
            .setVisible(WindowInsetsCompat.Type.displayCutout(), false)
            .setVisible(WindowInsetsCompat.Type.ime(), false)
            .build()
        val view = composeHost ?: error("Compose host was not captured")
        compose.runOnIdle { ViewCompat.dispatchApplyWindowInsets(view, insets) }
        compose.waitForIdle()
    }

    private fun assertFilesAreIsolated(
        baseContext: Context,
        root: File,
        testContext: IsolatedAppContext,
        app: AppContainer,
    ) {
        val rootPath = root.canonicalFile
        val actualDatabase = File(app.database.openHelper.writableDatabase.path).canonicalFile
        val defaultDatabase = baseContext.getDatabasePath("pulsez.db").canonicalFile

        assertTrue(
            "Room database uses a random filename in Robolectric's isolated databases directory",
            actualDatabase.parentFile == defaultDatabase.parentFile &&
                actualDatabase.name.startsWith(root.name),
        )
        assertTrue("Room did not open the Robolectric default pulsez.db", actualDatabase != defaultDatabase)
        assertTrue("ContextWrapper returns the unique application context", testContext.applicationContext === testContext)
        assertTrue("settings/preferences are written under the unique test root", root.walkTopDown().any {
            it.isFile && it.name.contains("pulsez_settings")
        })
        assertTrue("all app-private files resolve under the unique test root", testContext.filesDir.canonicalPath.startsWith(rootPath.path))
        assertTrue("all app-private cache files resolve under the unique test root", testContext.cacheDir.canonicalPath.startsWith(rootPath.path))
    }

    @After
    fun tearDown() {
        runCatching { container?.database?.close() }
        val root = isolatedRoot ?: return
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val baseCache = baseContext.cacheDir.canonicalFile
        val target = root.canonicalFile
        // Delete only the random child created by this test, never a fixed app database or directory.
        if (target.parentFile == baseCache && target.name.startsWith("pulsez-app-root-insets-")) {
            target.deleteRecursively()
        }
        val isolatedDbName = "${root.name}.db"
        val databaseDirectory = baseContext.getDatabasePath("pulsez.db").parentFile?.canonicalFile
        val databaseFile = baseContext.getDatabasePath(isolatedDbName).canonicalFile
        if (databaseDirectory != null && databaseFile.parentFile == databaseDirectory &&
            databaseFile.name.startsWith("pulsez-app-root-insets-")
        ) {
            listOf("", "-journal", "-wal", "-shm").forEach { suffix ->
                File(databaseFile.absolutePath + suffix).delete()
            }
        }
        container = null
        isolatedContext = null
        isolatedRoot = null
    }

    private class IsolatedAppContext(
        base: Context,
        private val testRoot: File,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        private fun isolatedDatabaseName(name: String): String =
            if (name == "pulsez.db") "${testRoot.name}.db" else "${testRoot.name}-$name"

        override fun getDatabasePath(name: String): File = super.getDatabasePath(isolatedDatabaseName(name))

        @Suppress("UNUSED_PARAMETER")
        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
        ): SQLiteDatabase = super.openOrCreateDatabase(isolatedDatabaseName(name), mode, factory)

        @Suppress("UNUSED_PARAMETER")
        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase = super.openOrCreateDatabase(isolatedDatabaseName(name), mode, factory, errorHandler)

        override fun getFilesDir(): File = File(testRoot, "files").apply { mkdirs() }

        override fun getNoBackupFilesDir(): File = File(testRoot, "no-backup").apply { mkdirs() }

        override fun getCacheDir(): File = File(testRoot, "cache").apply { mkdirs() }

        override fun getDir(name: String, mode: Int): File = File(testRoot, "app-$name").apply { mkdirs() }
    }

    private companion object {
        const val SINGLE_ROLE_NAME = "Root Insets Single Fixture"
        const val SECOND_ROLE_NAME = "Root Insets Group Member"
        const val GROUP_TITLE = "Root Insets Group Fixture"

        const val STATUS_BAR_DP = 26f
        const val NAV_BAR_DP = 24f
        const val CUTOUT_LEFT_DP = 48f
        const val CUTOUT_RIGHT_DP = 40f
        const val CUTOUT_TOP_DP = 56f
        const val IME_BOTTOM_DP = 220f
        const val LAYOUT_TOLERANCE_DP = 8f
    }
}
