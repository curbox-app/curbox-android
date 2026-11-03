package neth.iecal.curbox.blockers

import neth.iecal.curbox.blockers.uihider.script.Budget
import neth.iecal.curbox.blockers.uihider.script.Interpreter
import neth.iecal.curbox.blockers.uihider.script.Parser
import neth.iecal.curbox.blockers.uihider.script.RuntimeApi
import neth.iecal.curbox.hardcoded.ReelAppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TikTokDetectorTest {
    private fun node(top: Double = 0.0, bottom: Double = 2208.0, visible: Boolean = true) =
        mapOf<String, Any?>(
            "visible" to visible, "left" to 0.0, "right" to 1080.0,
            "top" to top, "bottom" to bottom, "w" to 1080.0, "h" to bottom - top
        )

    private fun detect(nodes: Map<String, List<Map<String, Any?>>>): Any? {
        val runtime = object : RuntimeApi {
            override fun provideGlobals(): Map<String, Any?> = mapOf(
                "screen" to mapOf("width" to 1080.0, "height" to 2400.0)
            )

            override fun callFunction(name: String, args: List<Any?>, named: Map<String, Any?>): Any? {
                val id = (named["id"] as? String)?.substringAfter(":id/")
                return when (name) {
                    "findAll" -> nodes[id].orEmpty()
                    "find" -> nodes[id]?.firstOrNull()
                    else -> error("Unexpected function: $name")
                }
            }
        }
        return Interpreter(runtime, Budget()).run(Parser.parse(
            ReelAppConfig.reelData.getValue(ReelAppConfig.TIKTOK_PACKAGE).scriptSource
        ))
    }

    @Test
    fun currentFeedDoesNotRequireRemovedWrapperAndSkipsHiddenNeighbours() {
        assertEquals("", detect(mapOf("long_press_layout" to listOf(
            node(bottom = 0.0, visible = false), node()
        ))))
    }

    @Test
    fun sharedVideoIsMarkedOnlyWithVisibleReplyBelowVideo() {
        assertEquals(ReelAppConfig.TIKTOK_DM_VIDEO_MARKER, detect(mapOf(
            "long_press_layout" to listOf(node()),
            "udm" to listOf(node(top = 2212.0, bottom = 2319.0))
        )))
        assertEquals("", detect(mapOf(
            "long_press_layout" to listOf(node()),
            "udm" to listOf(node(top = 2212.0, bottom = 2319.0, visible = false))
        )))
        assertEquals("", detect(mapOf(
            "long_press_layout" to listOf(node()),
            "udm" to listOf(node(top = 100.0, bottom = 200.0))
        )))
    }

    @Test
    fun chatAndInvitationWithoutFullScreenVideoAreNotVideoFeeds() {
        assertNull(detect(emptyMap()))
        assertNull(detect(mapOf("long_press_layout" to listOf(node(visible = false)))))
        assertNull(detect(mapOf("long_press_layout" to listOf(node(bottom = 500.0)))))
    }

    @Test
    fun olderWrapperStillSupportsHiddenVideoNode() {
        assertEquals("", detect(mapOf(
            "view_pager_layout_wrapper" to listOf(node()),
            "long_press_layout" to listOf(node(visible = false))
        )))
    }
}
