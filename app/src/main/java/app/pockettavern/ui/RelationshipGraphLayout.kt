package app.pockettavern.ui

import app.pockettavern.domain.ScenarioPerson
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Display order of the roster's organization groups; also fixes their colours. */
internal val RelationshipGroupOrder = listOf("庄园管理", "安保体系", "生活服务", "合作与盟友", "亲密关系", "外部与对立", "未分类 / 待明确")

/**
 * Geometry for the roster's two Canvas diagrams, in dp. Kept free of Compose
 * so overlap and bounds can be checked on the JVM; the Canvas only scales by
 * density and paints.
 */
internal object RelationshipGraphLayout {
    enum class Kind { ROOT, GROUP, PERSON }

    /**
     * A hit box in dp. Tree nodes are rounded boxes. Network nodes are a
     * circle of [radius] with the name under it, or above it when
     * [labelAbove] (upper half of the rings, so incoming spokes miss the
     * name). The network root has no outside label: its name is inside.
     */
    data class Node(
        val id: String, val label: String, val subtitle: String, val group: String, val kind: Kind,
        val x: Float, val y: Float, val width: Float, val height: Float, val radius: Float = 0f,
        val labelAbove: Boolean = false,
    ) {
        val centerX: Float get() = x + width / 2f
        val centerY: Float get() = when {
            radius <= 0f -> y + height / 2f
            labelAbove -> y + height - radius
            else -> y + radius
        }
        fun contains(px: Float, py: Float): Boolean = px >= x && px <= x + width && py >= y && py <= y + height
    }

    data class Edge(val from: String, val to: String, val label: String = "", val peer: Boolean = false)

    /**
     * [stacked] trees put the root above the group column instead of in a
     * column of its own. [originX]/[originY] is the network's ring centre,
     * which peer curves bend away from.
     */
    data class Layout(
        val width: Float, val height: Float, val nodes: List<Node>, val edges: List<Edge>,
        val stacked: Boolean = false, val originX: Float = 0f, val originY: Float = 0f,
    ) {
        private val byId = nodes.associateBy { it.id }
        fun node(id: String): Node? = byId[id]
        fun hit(x: Float, y: Float): Node? = nodes.lastOrNull { it.contains(x, y) }
    }

    data class TreeMetrics(
        val rootWidth: Float = 68f, val rootHeight: Float = 44f,
        val groupWidth: Float = 88f, val groupHeight: Float = 52f,
        val personHeight: Float = 42f, val minPersonWidth: Float = 112f,
        val rowGap: Float = 6f, val groupGap: Float = 14f, val columnGap: Float = 22f, val padding: Float = 6f,
        val trunkIndent: Float = 14f,
    )

    /**
     * [labelWidth] is the name's drawing width; [slot] is the centre-to-centre
     * spacing neighbours on a ring need so their horizontal labels never
     * collide whatever the angle between them.
     */
    data class NetworkMetrics(
        val radius: Float, val rootRadius: Float, val labelGap: Float, val labelHeight: Float, val labelWidth: Float,
        val slot: Float, val firstRing: Float, val ringStep: Float, val margin: Float = 6f,
    ) {
        val boxWidth: Float get() = labelWidth + 4f

        companion object {
            /**
             * Spacing follows the measured label height so large font scales
             * do not stack names onto a neighbour. [compact] trades node size
             * for capacity when many people have relationships.
             */
            fun forLabel(labelHeight: Float, compact: Boolean = false): NetworkMetrics {
                val radius = if (compact) 12f else 15f
                val rootRadius = if (compact) 22f else 26f
                val labelWidth = if (compact) 44f else 52f
                val labelGap = 3f
                // Two nodes this far apart can be at any angle to each other
                // without a name touching the other's name or circle: if both
                // axis gaps were short, the distance would be below the hypot.
                val slot = maxOf(labelWidth + 4f, hypot(labelWidth, labelHeight + 4f) + 2f,
                    hypot(labelWidth / 2f + radius, radius * 2f + labelGap + labelHeight) + 2f)
                return NetworkMetrics(radius, rootRadius, labelGap, labelHeight, labelWidth, slot,
                    firstRing = maxOf(if (compact) 66f else 80f, hypot(labelWidth / 2f + rootRadius, radius + labelGap + labelHeight + rootRadius) + 2f),
                    // Points on rings `ringStep` apart are at least that far apart, so the same bound holds between rings.
                    ringStep = max(slot, radius * 2f + labelGap + labelHeight + 8f))
            }
        }
    }

    const val GROUP_PREFIX = "group:"

    fun personSubtitle(person: ScenarioPerson): String = person.role.ifBlank { person.relationship.ifBlank { "关系未记录" } }

    /**
     * Organization chart: root, one box per group, then that group's people
     * in rows, left to right. When the three columns do not fit, the root
     * moves above the group column so people keep the width. Groups in
     * [collapsed] keep their box but hide their rows.
     */
    fun tree(
        rootName: String, groups: List<Pair<String, List<ScenarioPerson>>>, collapsed: Set<String>,
        availableWidth: Float, m: TreeMetrics = TreeMetrics(),
    ): Layout {
        val stacked = availableWidth < m.padding * 2f + m.rootWidth + m.groupWidth + m.columnGap * 2f + m.minPersonWidth
        val groupWidth = if (stacked) min(m.groupWidth, max(76f, availableWidth * .32f)) else m.groupWidth
        val minPersonWidth = if (stacked) min(m.minPersonWidth, max(96f, availableWidth * .5f)) else m.minPersonWidth
        val groupX = if (stacked) m.padding + m.trunkIndent else m.padding + m.rootWidth + m.columnGap
        val personX = groupX + groupWidth + m.columnGap
        val personWidth = max(minPersonWidth, availableWidth - personX - m.padding)
        val width = personX + personWidth + m.padding
        val rootWidth = if (stacked) max(m.rootWidth, min(width - m.padding * 2f, 180f)) else m.rootWidth
        val nodes = mutableListOf(Node(rootName, rootName, "核心", "", Kind.ROOT, m.padding, m.padding, rootWidth, m.rootHeight))
        val edges = mutableListOf<Edge>()
        var top = if (stacked) m.padding + m.rootHeight + m.groupGap else m.padding
        groups.forEach { (group, people) ->
            val shown = if (group in collapsed) emptyList() else people
            val rows = if (shown.isEmpty()) 0f else shown.size * m.personHeight + (shown.size - 1) * m.rowGap
            val groupId = GROUP_PREFIX + group
            nodes += Node(groupId, group, if (group in collapsed) "${people.size} 人 · 已收起" else "${people.size} 人",
                group, Kind.GROUP, groupX, top, groupWidth, m.groupHeight)
            edges += Edge(rootName, groupId)
            shown.forEachIndexed { index, person ->
                nodes += Node(person.name, person.name, personSubtitle(person), group, Kind.PERSON,
                    personX, top + index * (m.personHeight + m.rowGap), personWidth, m.personHeight)
                edges += Edge(groupId, person.name)
            }
            top += max(m.groupHeight, rows) + m.groupGap
        }
        val height = max(top - m.groupGap + m.padding, m.rootHeight + m.padding * 2)
        return Layout(width, height, nodes, edges, stacked = stacked)
    }

    /**
     * Radial network: the root sits in the centre and everyone named by an
     * edge is placed on concentric rings, grouped so colours cluster. The
     * canvas widens (and the caller scrolls) only when the rings cannot fit
     * [availableWidth]; its height hugs the nodes actually placed.
     */
    fun network(
        rootName: String, relationships: List<RelationshipEdge>, people: List<ScenarioPerson>,
        groupOf: (ScenarioPerson) -> String, availableWidth: Float, m: NetworkMetrics = NetworkMetrics.forLabel(16f),
    ): Layout {
        val edges = relationships.filter { it.from != it.to && it.from.isNotBlank() && it.to.isNotBlank() }
        if (edges.isEmpty()) return Layout(availableWidth, 0f, emptyList(), emptyList())
        val byName = people.associateBy { it.name }
        fun group(name: String) = byName[name]?.let(groupOf) ?: RelationshipGroupOrder.last()
        val names = LinkedHashSet<String>().apply { edges.forEach { add(it.from); add(it.to) } }
        val hasRoot = rootName in names
        val others = names.filter { it != rootName }
            .sortedBy { name -> RelationshipGroupOrder.indexOf(group(name)).let { if (it < 0) RelationshipGroupOrder.size else it } }

        val rings = mutableListOf<Pair<Float, Int>>()
        var remaining = others.size
        var radius = if (hasRoot) m.firstRing else max(m.firstRing * 0.6f, m.slot)
        while (remaining > 0) {
            val count = min(ringCapacity(radius, m.slot), remaining)
            rings += radius to count
            remaining -= count
            radius += m.ringStep
        }
        val maxRadius = rings.lastOrNull()?.first ?: 0f
        val labelBand = m.labelGap + m.labelHeight
        val width = max(availableWidth, (maxRadius + max(m.boxWidth / 2f, m.rootRadius) + m.margin) * 2f)
        val cx = width / 2f

        // Placed around a provisional centre at y = 0, then shifted so the
        // canvas starts at the top-most box rather than at the full ring.
        val placed = mutableListOf<Node>()
        if (hasRoot) placed += Node(rootName, rootName, "核心", "", Kind.ROOT, cx - m.rootRadius, -m.rootRadius,
            m.rootRadius * 2f, m.rootRadius * 2f, m.rootRadius)
        var index = 0
        var inner = emptyList<Double>()
        rings.forEach { (r, count) ->
            val angles = when {
                inner.isEmpty() -> List(count) { -PI / 2.0 + 2.0 * PI * it / count }
                // A smaller outer ring sits in the gaps of the inner one, so
                // every spoke passes between inner nodes. Gaps are at least
                // one inner step apart, and the outer ring is larger, so the
                // spacing still clears `slot`.
                count <= inner.size -> {
                    val sorted = inner.sorted()
                    val gaps = sorted.indices.map { i -> (sorted[i] + (sorted.getOrNull(i + 1) ?: (sorted[0] + 2.0 * PI))) / 2.0 }
                    List(count) { gaps[it * gaps.size / count] }
                }
                else -> clearOffset(inner, count).let { offset -> List(count) { offset + 2.0 * PI * it / count } }
            }
            angles.forEach { angle ->
                val name = others[index++]
                val px = cx + (r * cos(angle)).toFloat()
                val py = (r * sin(angle)).toFloat()
                val above = sin(angle) < -0.3
                placed += Node(name, name, group(name), group(name), Kind.PERSON, px - m.boxWidth / 2f,
                    if (above) py - m.radius - labelBand else py - m.radius, m.boxWidth, m.radius * 2f + labelBand, m.radius, above)
            }
            inner = angles
        }
        val top = placed.minOf { it.y } - m.margin
        val bottom = placed.maxOf { it.y + it.height } + m.margin
        return Layout(width, bottom - top, placed.map { it.copy(y = it.y - top) },
            edges.map { Edge(it.from, it.to, it.label, peer = it.from != rootName && it.to != rootName) },
            originX = cx, originY = -top)
    }

    /**
     * Rotation for a ring of [count] evenly spaced nodes that keeps them as
     * far as possible from the [inner] ring's angles, so a spoke to an outer
     * node does not run straight through an inner one.
     */
    private fun clearOffset(inner: List<Double>, count: Int): Double {
        val step = 2.0 * PI / count
        var best = -PI / 2.0
        var bestGap = -1.0
        for (i in 0 until 48) {
            val offset = -PI / 2.0 + step * i / 48.0
            val gap = (0 until count).minOf { j -> inner.minOf { a -> angularDistance(offset + step * j, a) } }
            if (gap > bestGap + 1e-9) { best = offset; bestGap = gap }
        }
        return best
    }

    private fun angularDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % (2.0 * PI)
        return min(d, 2.0 * PI - d)
    }

    /** Most nodes whose [slot]-wide boxes fit around a ring of radius [r] without touching. */
    internal fun ringCapacity(r: Float, slot: Float): Int =
        if (slot >= 2f * r) 1 else max(1, floor(PI / asin((slot / (2f * r)).toDouble())).toInt())
}
