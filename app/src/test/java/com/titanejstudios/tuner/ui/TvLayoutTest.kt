package com.titanejstudios.tuner.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import com.github.takahirom.roborazzi.captureRoboImage
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.integrations.Tmdb
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screens drawn at TV size (1920×1080 = 960×540 dp) on the build computer: buttons must be fully
 * on screen and rows mustn't be squashed. A picture of each is saved ("tv-screenshots" in the run).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class TvLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    /** Where things are drawn (for the failure message): visible bounds, then position and full size. */
    private fun where(vararg texts: String): String = texts.joinToString("\n") { t ->
        runCatching {
            val n = compose.onNodeWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNode()
            "  $t: visible ${n.boundsInRoot}, at ${n.positionInRoot}, size ${n.size}"
        }.getOrElse { "  $t: ${it.message?.lineSequence()?.firstOrNull()}" }
    }

    private var landmarks: Array<String> = emptyArray()

    /** The text is on the screen, whole, and not squashed flat. */
    private fun fullyOnScreen(text: String, minHeightPx: Float = 10f) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithText(text, substring = true).fetchSemanticsNode().boundsInRoot
        val detail = "screen $root\n" + where(*(landmarks + text))
        assertTrue("\"$text\" is cut off or off the screen: $b\n$detail", b.top >= root.top && b.bottom <= root.bottom + 0.5f && b.left >= root.left && b.right <= root.right + 0.5f)
        assertTrue("\"$text\" is squashed: ${b.height}px tall\n$detail", b.height >= minHeightPx)
    }

    private val longPlot = "Henry, son of Greg and Pam Focker, aims to marry Olivia, who appears to be his complete mismatch. " +
        "To Greg's shock, her unprecedented rise into his Circle of Trust sparks a battle of wills between the families. ".repeat(3)

    @Test
    fun filmInfoFitsATv() {
        val info = Tmdb.Info(
            "Focker In-Law", "2026", longPlot, 7.1, 105, null, null,
            listOf("Comedy", "Romance"), listOf("Robert De Niro", "Ben Stiller", "Ariana Grande", "Skyler Gisondo", "Owen Wilson"),
            trailerKey = "abc",
        )
        val parts = listOf("Meet the Parents", "Meet the Fockers", "Little Fockers", "Focker In-Law").mapIndexed { i, t ->
            TunerViewModel.SeriesPart(Tmdb.Part(t, (2000 + i * 4).toString(), null), null)
        }
        compose.setContent {
            FilmInfoCard(
                info = info, status = null, imdb = 6.4, collection = "Meet the Parents Collection" to parts,
                trailerAutoplay = false, thumb = 0,
                onClose = {}, onTrailer = { _, _ -> }, onThumb = {}, onWhereToWatch = {}, onPart = {},
            )
        }
        compose.waitForIdle()
        landmarks = arrayOf("Focker In-Law", "Henry, son", "With Robert", "Close", "Where to watch", "Meet the Parents Collection")
        shot("film_info")
        // The buttons are on screen without scrolling.
        fullyOnScreen("Trailer")
        fullyOnScreen("Close")
        fullyOnScreen("IMDb 6.4")
        // The collection can be scrolled to, at full size (it was squashed to a sliver).
        // (Down from the buttons on the remote does the same: the card scrolls.)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("1. Meet the Parents", substring = true))
        compose.waitForIdle()
        compose.onNodeWithText("1. Meet the Parents").assertIsDisplayed()
        fullyOnScreen("1. Meet the Parents")
        shot("film_info_scrolled")
    }

    @Test
    fun longReleaseNotesScrollAndTheButtonStaysVisible() {
        val notes = (1..40).joinToString("\n") { "• Change number $it, with a few words about what's different" }
        compose.setContent { WhatsNewCard("v6.6.9", notes) {} }
        compose.waitForIdle()
        shot("whats_new")
        fullyOnScreen("What's new in Flicker 6.6.9")
        fullyOnScreen("Got it")
    }

    @Test
    fun longMenusFitATv() {
        val items = (1..30).map { MenuItem("Channel $it HD", "UK | Sports · 20:00–22:00") {} }
        compose.setContent { MenuDialog(title = "Newcastle v Sunderland", subtitle = "On 30 of your channels – which one?", items = items, onDismiss = {}) }
        compose.waitForIdle()
        shot("menu_long")
        fullyOnScreen("Newcastle v Sunderland")
        fullyOnScreen("Channel 1 HD")
    }
}
