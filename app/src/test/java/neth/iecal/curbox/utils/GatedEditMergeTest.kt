package neth.iecal.curbox.utils

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppGroupConfig
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.TimeInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatedEditMergeTest {
    private val gson = Gson()

    private fun json(s: String) = JsonParser.parseString(s)

    @Test
    fun stringArraysMergeAsSets() {
        val merged = GatedEditMerge.merge(json("""["a","b"]"""), json("""["b","d"]"""), json("""["a","b","c"]"""))
        assertEquals(json("""["b","c","d"]"""), merged)
    }

    @Test
    fun objectsOnlyTakeTheKeysTheEditChanged() {
        val merged = GatedEditMerge.merge(
            json("""{"x":1,"y":1}"""), json("""{"x":2,"y":1}"""), json("""{"x":1,"y":5}""")
        )
        assertEquals(json("""{"x":2,"y":5}"""), merged)
    }

    @Test
    fun listsWithIdsCarryAddsEditsAndDeletesOneByOne() {
        val before = json("""[{"id":"a","v":1},{"id":"b","v":1}]""")
        val after = json("""[{"id":"a","v":2},{"id":"c","v":1}]""")
        val target = json("""[{"id":"a","v":1,"w":9},{"id":"b","v":0},{"id":"z","v":0}]""")
        assertEquals(
            json("""[{"id":"a","v":2,"w":9},{"id":"z","v":0},{"id":"c","v":1}]"""),
            GatedEditMerge.merge(before, after, target)
        )
    }

    @Test
    fun otherValuesTakeTheEditWhole() {
        assertEquals(json("[3,4]"), GatedEditMerge.merge(json("[1,2]"), json("[3,4]"), json("[1,9]")))
        assertEquals(json("true"), GatedEditMerge.merge(json("false"), json("true"), json("false")))
    }

    // The case that motivated this: group A has an app removed and that change is waiting.

    private val groupA = AppGroup(
        id = "A", name = "A", selectedPackages = listOf("a", "b", "c"), isActive = true,
        config = AppGroupConfig(schedule = schedule(9, 17))
    )
    private val groupB = AppGroup(
        id = "B", name = "B", selectedPackages = listOf("x"), isActive = true, config = AppGroupConfig()
    )
    private val live = listOf(groupA, groupB)
    private val waiting = listOf(groupA.copy(selectedPackages = listOf("a", "b")), groupB)

    private fun schedule(from: Int, to: Int) =
        AppTimeConfig(isEveryday = true, everydayIntervals = mutableListOf(TimeInterval(from, 0, to, 0)))

    /** What the gate does: carry the edit (waiting -> edited) over to live, then judge it. */
    private fun appliesNow(edited: List<AppGroup>): Boolean {
        val merged = GatedEditMerge.merge(
            gson.toJsonTree(waiting), gson.toJsonTree(edited), gson.toJsonTree(live)
        )!!
        val groups: List<AppGroup> = gson.fromJson(merged, object : TypeToken<List<AppGroup>>() {}.type)
        // The waiting removal must not leak into what goes live.
        assertEquals(listOf("a", "b", "c"), groups.firstOrNull { it.id == "A" }?.selectedPackages ?: listOf("a", "b", "c"))
        return RestrictionComparator.isSameOrStricter(
            GatedSettingsField.APP_GROUPS,
            Settings(blockedAppGroups = live),
            Settings(blockedAppGroups = groups)
        )
    }

    @Test
    fun tighteningAnotherGroupAppliesNow() {
        assertTrue(appliesNow(listOf(waiting[0], groupB.copy(selectedPackages = listOf("x", "y")))))
    }

    @Test
    fun tighteningTheSameGroupAppliesNow() {
        val wider = waiting[0].copy(config = AppGroupConfig(schedule = schedule(8, 18)))
        assertTrue(appliesNow(listOf(wider, groupB)))
    }

    @Test
    fun addingAGroupAppliesNow() {
        val c = AppGroup(id = "C", name = "C", selectedPackages = listOf("q"), isActive = true, config = AppGroupConfig())
        assertTrue(appliesNow(waiting + c))
    }

    @Test
    fun anotherLooseningStillWaits() {
        val narrower = waiting[0].copy(config = AppGroupConfig(schedule = schedule(10, 17)))
        assertFalse(appliesNow(listOf(narrower, groupB)))
        assertFalse(appliesNow(listOf(waiting[0])))
    }
}
