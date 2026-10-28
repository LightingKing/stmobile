@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.pockettavern.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.ScenarioPerson
import app.pockettavern.ui.RelationshipGraphLayout.Kind
import app.pockettavern.ui.RelationshipGraphLayout.Layout
import app.pockettavern.ui.RelationshipGraphLayout.Node
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private val GroupColors = mapOf(
    "庄园管理" to Color(0xFF3B6FD8), "安保体系" to Color(0xFFD2691E), "生活服务" to Color(0xFF1E9E5A),
    "合作与盟友" to Color(0xFF7B4FD6), "亲密关系" to Color(0xFFD33F86), "外部与对立" to Color(0xFF9C6B10),
    "未分类 / 待明确" to Color(0xFF6B7A90),
)

internal fun relationshipGroupColor(group: String): Color = GroupColors[group] ?: Color(0xFF6B7A90)

private class GraphText(val measurer: TextMeasurer, val name: TextStyle, val group: TextStyle, val small: TextStyle)

/**
 * The roster's two diagrams, both painted on a Canvas: a left-to-right
 * organization chart (root → groups → people) and a radial relationship
 * network. Tapping a person highlights their lines and shows a summary with
 * a jump to their card; tapping a group folds it.
 */
@Composable
internal fun RelationshipDiagrams(
    rootName: String,
    groups: List<Pair<String, List<ScenarioPerson>>>,
    people: List<ScenarioPerson>,
    edges: List<RelationshipEdge>,
    groupOf: (ScenarioPerson) -> String,
    onOpenPerson: (ScenarioPerson) -> Unit,
    background: Color,
    modifier: Modifier = Modifier,
    initialMode: Int = 0,
    initialSelection: String? = null,
) {
    var mode by rememberSaveable { mutableIntStateOf(initialMode) }
    var selected by rememberSaveable { mutableStateOf(initialSelection) }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val text = remember(measurer, typography) {
        GraphText(measurer, typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            typography.labelMedium.copy(fontWeight = FontWeight.Bold), typography.labelSmall)
    }
    val lines = remember(text, density) {
        listOf(text.name, text.group, text.small).map { style -> with(density) { measurer.measure("测", style).size.height.toDp().value } }
    }
    val (nameLine, groupLine, smallLine) = lines
    // Fixed column widths would truncate everything at large font scales;
    // grow them a little. Narrow screens then stack the root (see tree()).
    val widen = min(density.fontScale, 1.4f).coerceAtLeast(1f)
    val treeMetrics = RelationshipGraphLayout.TreeMetrics(
        rootWidth = 68f * widen, rootHeight = nameLine + smallLine + 12f,
        groupWidth = 88f * widen, groupHeight = groupLine * 2f + smallLine + 12f,
        personHeight = nameLine + smallLine + 12f, minPersonWidth = 112f * widen,
    )
    val colors = MaterialTheme.colorScheme
    val selectedPerson = people.firstOrNull { it.name == selected }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = mode == 0, onClick = { mode = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("组织架构") }
            SegmentedButton(selected = mode == 1, onClick = { mode = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("关系网络 · ${edges.size}") }
        }
        GroupLegend(groups)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val available = maxWidth.value
            if (mode == 0) {
                val layout = remember(rootName, groups, collapsed, available, treeMetrics) {
                    RelationshipGraphLayout.tree(rootName, groups, collapsed, available, treeMetrics)
                }
                GraphCanvas(layout, available, "组织架构图：${people.size} 人，${groups.size} 个分组；全部人物详情见下方人物卡", onTap = { node ->
                    when (node?.kind) {
                        Kind.GROUP -> collapsed = if (node.group in collapsed) collapsed - node.group else collapsed + node.group
                        Kind.PERSON -> selected = if (selected == node.id) null else node.id
                        else -> selected = null
                    }
                }) { drawTree(layout, selected, text, colors) }
            } else {
                val metrics = remember(smallLine) { RelationshipGraphLayout.NetworkMetrics.forLabel(smallLine) }
                val layout = remember(rootName, edges, people, available, metrics) {
                    val regular = RelationshipGraphLayout.network(rootName, edges, people, groupOf, available, metrics)
                    if (regular.width <= available) regular else {
                        val compact = RelationshipGraphLayout.network(rootName, edges, people, groupOf, available,
                            RelationshipGraphLayout.NetworkMetrics.forLabel(smallLine, compact = true))
                        if (compact.width < regular.width) compact else regular
                    }
                }
                if (layout.nodes.isEmpty()) {
                    Text("还没有原文明确登记的关系。点右上角 ✨ 从历史对话整理，或编辑人物填写“与主角关系”后，这里会画出关系网。",
                        Modifier.padding(horizontal = 6.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                } else {
                    GraphCanvas(layout, available, "关系网络图：${layout.nodes.size} 人，${layout.edges.size} 条明确关系；关系明细见图下列表", onTap = { node ->
                        selected = if (node == null || node.kind == Kind.ROOT || selected == node.id) null else node.id
                    }) { drawNetwork(layout, selected, text, colors, background) }
                }
            }
        }
        if (selectedPerson != null) {
            SelectedPersonSummary(selectedPerson, edges, onOpen = { onOpenPerson(selectedPerson) })
        } else {
            Text(if (mode == 0) "点人物查看详情和关系，点分组可收起或展开" else "点人物只看他的关系线；箭头指向关系对象",
                Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            if (mode == 1) edges.forEach { edge ->
                Text("${edge.from} → ${edge.to} · ${edge.label}", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun GraphCanvas(layout: Layout, available: Float, description: String, onTap: (Node?) -> Unit, draw: DrawScope.() -> Unit) {
    // A diagram a little wider than the screen is shrunk to fit (down to
    // 75%, where names are still legible); anything wider scrolls sideways.
    val scale = if (layout.width > available) max(.75f, available / layout.width) else 1f
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxWidth().then(if (layout.width * scale > available + 0.5f) Modifier.horizontalScroll(scroll) else Modifier)) {
        Canvas(
            Modifier.size((layout.width * scale).dp, (layout.height * scale).dp)
                .pointerInput(layout, scale) { detectTapGestures { offset -> onTap(layout.hit(offset.x.toDp().value / scale, offset.y.toDp().value / scale)) } }
                .semantics { contentDescription = description },
        ) { scale(scale, scale, pivot = Offset.Zero) { draw() } }
    }
}

@Composable
private fun GroupLegend(groups: List<Pair<String, List<ScenarioPerson>>>) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        groups.forEach { (group, members) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(Modifier.size(9.dp), shape = CircleShape, color = relationshipGroupColor(group)) {}
                Text("$group ${members.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SelectedPersonSummary(person: ScenarioPerson, edges: List<RelationshipEdge>, onOpen: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(person.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = onOpen) { Text("查看人物卡") }
            }
            Text("岗位：${person.role.ifBlank { "未记录" }}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("当前：${person.activity.ifBlank { "未记录" }}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val related = edges.filter { it.from == person.name || it.to == person.name }
            if (related.isEmpty()) Text("关系：原文未明确登记", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            related.forEach { edge ->
                Text("${edge.from} → ${edge.to}：${edge.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun measureLine(text: GraphText, value: String, style: TextStyle, maxWidthPx: Float, maxLines: Int = 1): TextLayoutResult =
    text.measurer.measure(value, style, overflow = TextOverflow.Ellipsis, maxLines = maxLines,
        constraints = Constraints(maxWidth = max(1, maxWidthPx.toInt())))

private fun DrawScope.drawTree(layout: Layout, selected: String?, text: GraphText, colors: ColorScheme) {
    fun Float.px() = this * density
    val chosen = selected?.let(layout::node)?.takeIf { it.kind == Kind.PERSON }
    val chosenGroup = chosen?.let { RelationshipGraphLayout.GROUP_PREFIX + it.group }
    layout.edges.forEach { edge ->
        val from = layout.node(edge.from) ?: return@forEach
        val to = layout.node(edge.to) ?: return@forEach
        val active = chosen != null && (edge.to == chosen.id || edge.to == chosenGroup)
        val ex = to.x.px(); val ey = to.centerY.px()
        val path = if (layout.stacked && from.kind == Kind.ROOT) {
            // Stacked root sits above the groups: a trunk down its left edge, then a rounded turn into each group.
            val tx = (from.x + 8f).px(); val sy = (from.y + from.height).px()
            Path().apply { moveTo(tx, sy); cubicTo(tx, ey, tx, ey, ex, ey) }
        } else {
            val sx = (from.x + from.width).px(); val sy = from.centerY.px(); val mx = (sx + ex) / 2f
            Path().apply { moveTo(sx, sy); cubicTo(mx, sy, mx, ey, ex, ey) }
        }
        val color = if (active) colors.primary else if (to.kind == Kind.GROUP) relationshipGroupColor(to.group).copy(alpha = .7f) else relationshipGroupColor(to.group).copy(alpha = .45f)
        drawPath(path, color, style = Stroke(width = (if (active) 2.6f else 1.4f).px()))
    }
    val corner = CornerRadius(10f.px())
    val pad = 8f.px()
    layout.nodes.forEach { node ->
        val topLeft = Offset(node.x.px(), node.y.px())
        val size = Size(node.width.px(), node.height.px())
        val inner = size.width - pad * 2f
        when (node.kind) {
            Kind.ROOT, Kind.GROUP -> {
                val fill = if (node.kind == Kind.ROOT) colors.primary else relationshipGroupColor(node.group)
                val ink = if (node.kind == Kind.ROOT) colors.onPrimary else Color.White
                drawRoundRect(fill, topLeft, size, corner)
                // “未分类 / 待明确” breaks at the slash instead of mid-word.
                val title = measureLine(text, node.label.replace(" / ", "\n"), (if (node.kind == Kind.ROOT) text.name else text.group).copy(color = ink, textAlign = TextAlign.Center),
                    inner, maxLines = if (node.kind == Kind.GROUP) 2 else 1)
                val sub = measureLine(text, node.subtitle, text.small.copy(color = ink.copy(alpha = .86f)), inner)
                val top = topLeft.y + (size.height - title.size.height - sub.size.height) / 2f
                drawText(title, topLeft = Offset(topLeft.x + (size.width - title.size.width) / 2f, top))
                drawText(sub, topLeft = Offset(topLeft.x + (size.width - sub.size.width) / 2f, top + title.size.height))
            }
            Kind.PERSON -> {
                val accent = relationshipGroupColor(node.group)
                val isChosen = node.id == selected
                drawRoundRect(lerp(colors.surface, accent, .13f), topLeft, size, corner)
                drawRoundRect(if (isChosen) colors.primary else accent.copy(alpha = .75f), topLeft, size, corner,
                    style = Stroke(width = (if (isChosen) 2.6f else 1.1f).px()))
                drawRoundRect(accent, topLeft, Size(4f.px(), size.height), CornerRadius(2f.px()))
                val title = measureLine(text, node.label, text.name.copy(color = colors.onSurface), inner)
                val sub = measureLine(text, node.subtitle, text.small.copy(color = colors.onSurfaceVariant), inner)
                val top = topLeft.y + (size.height - title.size.height - sub.size.height) / 2f
                drawText(title, topLeft = Offset(topLeft.x + pad, top))
                drawText(sub, topLeft = Offset(topLeft.x + pad, top + title.size.height))
            }
        }
    }
}

private fun DrawScope.drawNetwork(layout: Layout, selected: String?, text: GraphText, colors: ColorScheme, background: Color) {
    fun Float.px() = this * density
    val focus = selected?.takeIf { layout.node(it) != null }
    val neighbours = if (focus == null) emptySet() else layout.edges.filter { it.from == focus || it.to == focus }.flatMap { listOf(it.from, it.to) }.toSet()
    val showLabels = focus != null || layout.edges.size <= 8
    val labels = mutableListOf<Triple<Offset, String, Color>>()
    val origin = Offset(layout.originX.px(), layout.originY.px())
    // Peer curves keep at least this far from the centre so they never run over the protagonist.
    val clearance = ((layout.nodes.firstOrNull { it.kind == Kind.ROOT }?.radius ?: 0f) + 22f).px()
    layout.edges.forEach { edge ->
        val a = layout.node(edge.from) ?: return@forEach
        val b = layout.node(edge.to) ?: return@forEach
        val active = focus == null || edge.from == focus || edge.to == focus
        val base = if (edge.peer) colors.tertiary else colors.outline
        val color = when {
            focus != null && active -> colors.primary
            active -> base.copy(alpha = .8f)
            else -> base.copy(alpha = .14f)
        }
        val ax = a.centerX.px(); val ay = a.centerY.px(); val bx = b.centerX.px(); val by = b.centerY.px()
        val length = hypot(bx - ax, by - ay).coerceAtLeast(1f)
        val mid = Offset((ax + bx) / 2f, (ay + by) / 2f)
        // Peer relationships are told apart from spokes by colour. They stay
        // straight unless the chord would graze the protagonist; then they
        // bow outward just enough to clear it (a larger bow runs into the
        // ring nodes between the two people).
        val control = if (!edge.peer) mid else {
            var nx = -(by - ay) / length; var ny = (bx - ax) / length
            if (nx * (mid.x - origin.x) + ny * (mid.y - origin.y) < 0f) { nx = -nx; ny = -ny }
            val bend = max(0f, (clearance - hypot(mid.x - origin.x, mid.y - origin.y)) * 2f)
            Offset(mid.x + nx * bend, mid.y + ny * bend)
        }
        fun toward(x: Float, y: Float, target: Offset, distance: Float): Offset {
            val d = hypot(target.x - x, target.y - y).coerceAtLeast(1f)
            return Offset(x + (target.x - x) / d * distance, y + (target.y - y) / d * distance)
        }
        val start = toward(ax, ay, if (edge.peer) control else Offset(bx, by), a.radius.px())
        val end = toward(bx, by, if (edge.peer) control else Offset(ax, ay), b.radius.px() + 2f.px())
        val path = Path().apply {
            moveTo(start.x, start.y)
            // Quadratic through `control`, written as the equivalent cubic.
            cubicTo(start.x + (control.x - start.x) * 2f / 3f, start.y + (control.y - start.y) * 2f / 3f,
                end.x + (control.x - end.x) * 2f / 3f, end.y + (control.y - end.y) * 2f / 3f, end.x, end.y)
        }
        drawPath(path, color, style = Stroke(width = (if (focus != null && active) 2.4f else 1.3f).px()))
        val tail = if (edge.peer) control else start
        val d = hypot(end.x - tail.x, end.y - tail.y).coerceAtLeast(1f)
        val ux = (end.x - tail.x) / d; val uy = (end.y - tail.y) / d
        val arrow = 7f.px(); val spread = 4f.px()
        drawPath(Path().apply {
            moveTo(end.x, end.y)
            lineTo(end.x - ux * arrow - uy * spread, end.y - uy * arrow + ux * spread)
            lineTo(end.x - ux * arrow + uy * spread, end.y - uy * arrow - ux * spread)
            close()
        }, color)
        if (showLabels && active && edge.label.isNotBlank()) {
            labels += Triple(Offset(start.x * .25f + control.x * .5f + end.x * .25f, start.y * .25f + control.y * .5f + end.y * .25f), edge.label, color)
        }
    }
    layout.nodes.forEach { node ->
        val dim = focus != null && node.id !in neighbours
        val isRoot = node.kind == Kind.ROOT
        val fill = (if (isRoot) colors.primary else relationshipGroupColor(node.group)).let { if (dim) it.copy(alpha = .3f) else it }
        val center = Offset(node.centerX.px(), node.centerY.px())
        val radius = node.radius.px()
        drawCircle(fill, radius, center)
        if (node.id == focus) drawCircle(colors.primary, radius + 3.5f.px(), center, style = Stroke(width = 2.5f.px()))
        if (isRoot) {
            // The protagonist's name sits inside the centre circle: an outside
            // label would be crossed by every spoke.
            val name = measureLine(text, node.label, text.group.copy(color = colors.onPrimary), radius * 2f - 6f.px())
            drawText(name, topLeft = Offset(center.x - name.size.width / 2f, center.y - name.size.height / 2f))
            return@forEach
        }
        val initial = node.label.takeIf { it.isNotEmpty() }?.let { String(Character.toChars(it.codePointAt(0))) }.orEmpty()
        val glyph = measureLine(text, initial, text.group.copy(color = Color.White.copy(alpha = if (dim) .7f else 1f)), radius * 2f)
        drawText(glyph, topLeft = Offset(center.x - glyph.size.width / 2f, center.y - glyph.size.height / 2f))
        val name = measureLine(text, node.label, text.small.copy(color = colors.onSurface.copy(alpha = if (dim) .4f else 1f),
            fontWeight = if (node.id == focus) FontWeight.Bold else null), (node.width - 4f).px())
        val gap = 3f.px()
        val nameTop = if (node.labelAbove) center.y - radius - gap - name.size.height else center.y + radius + gap
        val nameLeft = center.x - name.size.width / 2f
        // Lines may pass behind a name; a backdrop in the canvas colour keeps it legible.
        drawRoundRect(background.copy(alpha = .88f), Offset(nameLeft - 2f.px(), nameTop), Size(name.size.width + 4f.px(), name.size.height.toFloat()), CornerRadius(4f.px()))
        drawText(name, topLeft = Offset(nameLeft, nameTop))
    }
    labels.forEach { (at, label, color) ->
        val layoutResult = measureLine(text, label, text.small.copy(color = colors.onSurface), 84f.px())
        val padX = 5f.px(); val padY = 2f.px()
        val topLeft = Offset(at.x - layoutResult.size.width / 2f - padX, at.y - layoutResult.size.height / 2f - padY)
        val size = Size(layoutResult.size.width + padX * 2f, layoutResult.size.height + padY * 2f)
        drawRoundRect(colors.surface, topLeft, size, CornerRadius(8f.px()))
        drawRoundRect(color, topLeft, size, CornerRadius(8f.px()), style = Stroke(width = 1f.px()))
        drawText(layoutResult, topLeft = Offset(topLeft.x + padX, topLeft.y + padY))
    }
}
