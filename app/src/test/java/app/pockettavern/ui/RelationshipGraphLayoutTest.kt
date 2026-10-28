package app.pockettavern.ui

import app.pockettavern.domain.ScenarioPerson
import app.pockettavern.ui.RelationshipGraphLayout.Kind
import app.pockettavern.ui.RelationshipGraphLayout.NetworkMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

class RelationshipGraphLayoutTest {
    private fun person(name: String, role: String = "", relationship: String = "") = ScenarioPerson(name = name, role = role, relationship = relationship)

    private val groups = listOf(
        "庄园管理" to listOf(person("顾砚舟", "庄园管家")),
        "安保体系" to listOf(person("王六堡", "安保队长"), person("阿冷", "门岗")),
        "生活服务" to (1..5).map { person("服务$it", "起居接待") },
    )

    private fun overlaps(a: RelationshipGraphLayout.Node, b: RelationshipGraphLayout.Node) =
        a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height

    @Test fun treeLinksRootGroupsAndPeopleLeftToRightWithoutOverlap() {
        val layout = RelationshipGraphLayout.tree("谷若尘", groups, emptySet(), availableWidth = 360f)
        val root = layout.node("谷若尘")!!
        assertEquals(Kind.ROOT, root.kind)
        assertEquals(1 + groups.size + 8, layout.nodes.size)
        groups.forEach { (group, people) ->
            val box = layout.node(RelationshipGraphLayout.GROUP_PREFIX + group)!!
            assertTrue(box.x > root.x + root.width)
            assertTrue(layout.edges.contains(RelationshipGraphLayout.Edge("谷若尘", box.id)))
            people.forEach { member ->
                val node = layout.node(member.name)!!
                assertTrue(node.x > box.x + box.width)
                assertEquals(group, node.group)
                assertTrue(layout.edges.contains(RelationshipGraphLayout.Edge(box.id, member.name)))
            }
        }
        layout.nodes.forEachIndexed { i, a -> layout.nodes.drop(i + 1).forEach { b -> assertFalse("${a.id} overlaps ${b.id}", overlaps(a, b)) } }
        layout.nodes.forEach { assertTrue(it.x + it.width <= layout.width && it.y + it.height <= layout.height) }
        assertEquals("安保队长", layout.node("王六堡")!!.subtitle)
    }

    @Test fun treeUsesAvailableWidthAndOnlyGrowsWhenTooNarrow() {
        assertEquals(400f, RelationshipGraphLayout.tree("主角", groups, emptySet(), 400f).width, 0.01f)
        assertTrue(RelationshipGraphLayout.tree("主角", groups, emptySet(), 200f).width > 200f)
    }

    @Test fun narrowTreeStacksRootAboveGroupsSoPeopleKeepTheWidth() {
        val layout = RelationshipGraphLayout.tree("谷若尘", groups, emptySet(), availableWidth = 300f)
        assertTrue(layout.stacked)
        assertEquals(300f, layout.width, 0.01f)
        val root = layout.node("谷若尘")!!
        val firstGroup = layout.nodes.first { it.kind == Kind.GROUP }
        assertTrue(root.y + root.height <= firstGroup.y && root.x <= firstGroup.x)
        layout.nodes.forEachIndexed { i, a -> layout.nodes.drop(i + 1).forEach { b -> assertFalse("${a.id} overlaps ${b.id}", overlaps(a, b)) } }
        assertFalse(RelationshipGraphLayout.tree("谷若尘", groups, emptySet(), availableWidth = 360f).stacked)
    }

    @Test fun collapsedGroupKeepsItsBoxButHidesPeopleAndShrinks() {
        val open = RelationshipGraphLayout.tree("主角", groups, emptySet(), 360f)
        val folded = RelationshipGraphLayout.tree("主角", groups, setOf("生活服务"), 360f)
        val box = folded.node(RelationshipGraphLayout.GROUP_PREFIX + "生活服务")!!
        assertTrue(box.subtitle.contains("5 人") && box.subtitle.contains("已收起"))
        assertNull(folded.node("服务1"))
        assertTrue(folded.edges.none { it.to == "服务1" })
        assertTrue(folded.height < open.height)
    }

    @Test fun networkKeepsFortyPeopleApartInsideTheCanvas() {
        listOf(NetworkMetrics.forLabel(16f), NetworkMetrics.forLabel(16f, compact = true), NetworkMetrics.forLabel(34f)).forEach { metrics ->
            val people = (1..40).map { person("人物$it", relationship = "雇员") }
            val edges = people.map { RelationshipEdge("谷若尘", it.name, "雇员") } + RelationshipEdge("人物1", "人物2", "朋友")
            val layout = RelationshipGraphLayout.network("谷若尘", edges, people, { "生活服务" }, availableWidth = 360f, m = metrics)
            assertEquals(41, layout.nodes.size)
            val root = layout.node("谷若尘")!!
            assertEquals(layout.width / 2f, root.centerX, 0.01f)
            assertTrue(layout.edges.single { it.from == "人物1" }.peer)
            assertFalse(layout.edges.first { it.from == "谷若尘" }.peer)
            val labelHalf = metrics.labelWidth / 2f
            fun label(n: RelationshipGraphLayout.Node): FloatArray {
                val top = if (n.labelAbove) n.centerY - n.radius - metrics.labelGap - metrics.labelHeight else n.centerY + n.radius + metrics.labelGap
                return floatArrayOf(n.centerX - labelHalf, top, n.centerX + labelHalf, top + metrics.labelHeight)
            }
            fun square(n: RelationshipGraphLayout.Node) = floatArrayOf(n.centerX - n.radius, n.centerY - n.radius, n.centerX + n.radius, n.centerY + n.radius)
            fun hits(a: FloatArray, b: FloatArray) = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]
            val labelled = layout.nodes.filter { it.kind == Kind.PERSON }
            assertTrue(labelled.any { it.labelAbove } && labelled.any { !it.labelAbove })
            labelled.forEach { a -> layout.nodes.filter { it.id != a.id }.forEach { b ->
                assertFalse("${a.id} label covers ${b.id}", hits(label(a), square(b)))
            } }
            layout.nodes.forEach { n ->
                assertTrue("${n.id} outside canvas", n.x >= 0f && n.y >= 0f && n.x + n.width <= layout.width + 0.01f && n.y + n.height <= layout.height + 0.01f)
            }
            layout.nodes.forEachIndexed { i, a ->
                layout.nodes.drop(i + 1).forEach { b ->
                    assertTrue("${a.id} and ${b.id} circles touch", hypot(a.centerX - b.centerX, a.centerY - b.centerY) >= a.radius + b.radius + 4f)
                    if (a.kind == Kind.PERSON && b.kind == Kind.PERSON) assertFalse("${a.id} and ${b.id} labels overlap", hits(label(a), label(b)))
                }
            }
        }
    }

    @Test fun spokeToOuterRingDoesNotRunThroughAnInnerNode() {
        val people = (1..9).map { person("人$it") }
        val layout = RelationshipGraphLayout.network("主角", people.map { RelationshipEdge("主角", it.name, "雇员") }, people, { "生活服务" }, 360f)
        val root = layout.node("主角")!!
        val outer = layout.nodes.maxBy { hypot(it.centerX - root.centerX, it.centerY - root.centerY) }
        layout.nodes.filter { it.kind == Kind.PERSON && it.id != outer.id }.forEach { n ->
            // Distance from n's centre to the root→outer segment.
            val dx = outer.centerX - root.centerX; val dy = outer.centerY - root.centerY
            val t = (((n.centerX - root.centerX) * dx + (n.centerY - root.centerY) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
            val gap = hypot(n.centerX - (root.centerX + t * dx), n.centerY - (root.centerY + t * dy))
            assertTrue("spoke to ${outer.id} crosses ${n.id}", gap > n.radius + 4f)
        }
    }

    @Test fun networkWidensOnlyWhenRingsCannotFit() {
        val few = (1..5).map { person("人$it") }
        val small = RelationshipGraphLayout.network("主角", few.map { RelationshipEdge("主角", it.name, "朋友") }, few, { "合作与盟友" }, 360f)
        assertEquals(360f, small.width, 0.01f)
        val many = (1..60).map { person("人$it") }
        val wide = RelationshipGraphLayout.network("主角", many.map { RelationshipEdge("主角", it.name, "朋友") }, many, { "合作与盟友" }, 360f)
        assertTrue(wide.width > 360f)
    }

    @Test fun peerOnlyNetworkHasNoRootAndDropsSelfEdges() {
        val people = listOf(person("林岚"), person("顾砚舟"))
        val layout = RelationshipGraphLayout.network("谷若尘", listOf(RelationshipEdge("林岚", "顾砚舟", "朋友"), RelationshipEdge("林岚", "林岚", "自己")), people, { "合作与盟友" }, 360f)
        assertNull(layout.node("谷若尘"))
        assertEquals(1, layout.edges.size)
        assertTrue(layout.edges.single().peer)
        assertEquals(0f, RelationshipGraphLayout.network("谷若尘", emptyList(), people, { "" }, 360f).height, 0f)
    }

    @Test fun networkClustersPeopleByGroupOrder() {
        val people = listOf(person("甲", "访客"), person("乙", "管家"), person("丙", "门岗"))
        val group = mapOf("甲" to "外部与对立", "乙" to "庄园管理", "丙" to "安保体系")
        val layout = RelationshipGraphLayout.network("主角", people.map { RelationshipEdge("主角", it.name, "x") }, people, { group.getValue(it.name) }, 360f)
        assertEquals(listOf("乙", "丙", "甲"), layout.nodes.filter { it.kind == Kind.PERSON }.map { it.id })
    }

    @Test fun hitTestReturnsTheTappedNode() {
        val layout = RelationshipGraphLayout.tree("主角", groups, emptySet(), 360f)
        val node = layout.node("阿冷")!!
        assertEquals("阿冷", layout.hit(node.centerX, node.centerY)?.id)
        assertNull(layout.hit(-5f, -5f))
    }

    @Test fun ringCapacityKeepsNeighbouringSlotsApart() {
        listOf(57f, 80f, 136f, 300f).forEach { r ->
            val n = RelationshipGraphLayout.ringCapacity(r, 56f)
            if (n > 1) assertTrue(2.0 * r * sin(PI / n) >= 56.0)
            assertTrue(2.0 * r * sin(PI / (n + 1)) < 56.0)
        }
    }
}
