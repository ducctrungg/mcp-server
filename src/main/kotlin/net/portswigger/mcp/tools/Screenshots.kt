package net.portswigger.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.JLabel
import javax.swing.JTabbedPane
import javax.swing.JTable

/**
 * Paints [component] (border and children included) into an offscreen image.
 * Must run on the EDT. Returns null when the component has no size to capture.
 */
internal fun captureComponent(component: Component?): BufferedImage? {
    if (component == null || component.width <= 0 || component.height <= 0) return null
    val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
        component.paint(g)
    } finally {
        g.dispose()
    }
    return image
}

internal fun BufferedImage.toPngImageContent(): ImageContent {
    val bytes = ByteArrayOutputStream().also { ImageIO.write(this, "png", it) }.toByteArray()
    check(bytes.isNotEmpty()) { "PNG encoding failed" }
    return ImageContent(data = Base64.getEncoder().encodeToString(bytes), mimeType = "image/png")
}

/**
 * Selects the first tab titled [title] (showing panes preferred over hidden ones), reveals it by
 * selecting every enclosing tabbed pane, and lays the touched panes out synchronously so a paint
 * on the same EDT call sees the new selection. Must run on the EDT.
 * Returns the tab's content component (what the tab shows, without the tab strip), or null when no tab matches.
 */
internal fun selectTabByTitle(root: Component?, title: String): Component? {
    val panes = mutableListOf<JTabbedPane>().also { collectTabbedPanes(root, it) }
    val pane = panes.sortedByDescending { it.isShowing }.firstOrNull { selectIfMatches(it, title) } ?: return null

    val touched = mutableListOf(pane)
    var child: Component = pane
    for (parent in generateSequence(pane.parent) { it.parent }) {
        if (parent is JTabbedPane) {
            val index = parent.indexOfComponent(child)
            if (index >= 0) {
                parent.selectedIndex = index
                touched.add(parent)
            }
        }
        child = parent
    }
    // setSelectedIndex only revalidates lazily; a never-shown tab has no bounds until laid out.
    touched.asReversed().forEach { it.invalidate(); it.validate() }
    return pane.selectedComponent
}

private fun collectTabbedPanes(component: Component?, out: MutableList<JTabbedPane>) {
    if (component == null) return
    if (component is JTabbedPane) out.add(component)
    if (component is Container) component.components.forEach { collectTabbedPanes(it, out) }
}

private fun selectIfMatches(pane: JTabbedPane, title: String): Boolean {
    val wanted = title.trim()
    for (i in 0 until pane.tabCount) {
        if (pane.getTitleAt(i)?.trim() == wanted || tabComponentText(pane.getTabComponentAt(i))?.trim() == wanted) {
            pane.selectedIndex = i
            return true
        }
    }
    return false
}

private fun tabComponentText(component: Component?): String? {
    if (component is JLabel) component.text?.takeIf { it.isNotBlank() }?.let { return it }
    if (component is Container) {
        for (child in component.components) {
            tabComponentText(child)?.let { return it }
        }
    }
    return null
}

/**
 * Selects the first row under [root] whose "#" column (first column when no such header) equals [id]
 * and scrolls it into view. Showing tables are searched before hidden ones. Must run on the EDT.
 * Returns false when no row matches, e.g. because the history filter hides it.
 */
internal fun selectTableRowById(root: Component?, id: Int): Boolean {
    val tables = mutableListOf<JTable>().also { collectTables(root, it) }
    val wanted = id.toString()
    for (table in tables.sortedByDescending { it.isShowing }) {
        val column = (0 until table.columnCount).firstOrNull { table.getColumnName(it).trim() == "#" } ?: 0
        for (row in 0 until table.rowCount) {
            if (table.getValueAt(row, column)?.toString()?.trim() == wanted) {
                table.setRowSelectionInterval(row, row)
                table.scrollRectToVisible(table.getCellRect(row, column, true))
                return true
            }
        }
    }
    return false
}

private fun collectTables(component: Component?, out: MutableList<JTable>) {
    if (component == null) return
    if (component is JTable) out.add(component)
    if (component is Container) component.components.forEach { collectTables(it, out) }
}
