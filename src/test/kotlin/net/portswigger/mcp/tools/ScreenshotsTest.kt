package net.portswigger.mcp.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.Base64
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable

class ScreenshotsTest {

    @Test
    fun `captureComponent should paint component pixels`() {
        val panel = JPanel()
        panel.background = Color.RED
        panel.setSize(20, 10)

        val image = captureComponent(panel)

        assertNotNull(image)
        assertEquals(20, image!!.width)
        assertEquals(10, image.height)
        assertEquals(Color.RED.rgb, image.getRGB(5, 5))
    }

    @Test
    fun `captureComponent should reject unsized components`() {
        assertNull(captureComponent(JPanel()))
        assertNull(captureComponent(null))
    }

    @Test
    fun `toPngImageContent should return decodable png data`() {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)

        val content = image.toPngImageContent()

        assertEquals("image/png", content.mimeType)
        val bytes = Base64.getDecoder().decode(content.data)
        assertTrue(
            bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte(),
            "Expected PNG magic in decoded data"
        )
    }

    @Test
    fun `selectTabByTitle should select a tab by title and return its content`() {
        val pane = JTabbedPane()
        val beta = JPanel()
        pane.addTab("alpha", JPanel())
        pane.addTab("beta", beta)

        assertSame(beta, selectTabByTitle(pane, "beta"))
        assertEquals(1, pane.selectedIndex)
    }

    @Test
    fun `selectTabByTitle should match custom tab component labels`() {
        val pane = JTabbedPane()
        val gamma = JPanel()
        pane.addTab(null, gamma)
        pane.setTabComponentAt(0, JPanel().apply { add(JLabel("")); add(JLabel("gamma")) })

        assertSame(gamma, selectTabByTitle(pane, "gamma"))
        assertEquals(0, pane.selectedIndex)
    }

    @Test
    fun `selectTabByTitle should reveal the tab inside hidden enclosing panes`() {
        val inner = JTabbedPane()
        val beta = JPanel()
        inner.addTab("alpha", JPanel())
        inner.addTab("beta", beta)
        val outer = JTabbedPane()
        outer.addTab("first", JPanel())
        outer.addTab("second", inner)
        outer.selectedIndex = 0

        assertSame(beta, selectTabByTitle(outer, "beta"))
        assertEquals(1, outer.selectedIndex)
        assertEquals(1, inner.selectedIndex)
    }

    @Test
    fun `selectTabByTitle should not relayout panes that already show the tab`() {
        val beta = JPanel()
        val pane = object : JTabbedPane() {
            var validated = 0
            override fun validate() { validated++; super.validate() }
        }
        pane.addTab("alpha", JPanel())
        pane.addTab("beta", beta)
        pane.selectedIndex = 1
        pane.validated = 0

        assertSame(beta, selectTabByTitle(pane, "beta"))
        assertEquals(0, pane.validated)
    }

    @Test
    fun `selectTabByTitle should return null when nothing matches`() {
        val pane = JTabbedPane()
        pane.addTab("alpha", JPanel())

        assertNull(selectTabByTitle(pane, "nope"))
        assertNull(selectTabByTitle(null, "alpha"))
        assertEquals(0, pane.selectedIndex)
    }

    @Test
    fun `selectTableRowById should select the row and return the editor half of the split pane`() {
        val table = JTable(arrayOf(arrayOf<Any>("x", 10), arrayOf<Any>("y", 2090)), arrayOf<Any>("Host", "#"))
        val editors = JPanel()
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(table), editors)
        val panel = JPanel().apply { add(split) }

        assertSame(editors, selectTableRowById(panel, 2090))
        assertEquals(1, table.selectedRow)
        assertNull(selectTableRowById(panel, 7))
        assertEquals(1, table.selectedRow)
    }

    @Test
    fun `selectTableRowById should fall back to the table without a split pane`() {
        val table = JTable(arrayOf(arrayOf<Any>(5)), arrayOf<Any>("#"))

        assertSame(table, selectTableRowById(JPanel().apply { add(table) }, 5))
    }
}
