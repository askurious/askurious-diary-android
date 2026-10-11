package de.askurious.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The server files a kept page needs (OfflinePages.resourcePaths, cssPaths). */
class OfflinePagesTest {
    @Test
    fun absoluteStaticAndMediaPathsAreFoundOnceInOrder() {
        val html = """
            <link rel="stylesheet" href="/static/participant.css?v=3a&amp;x=1">
            <script src='/static/participant.js?v=3a' defer></script>
            <img SRC="/media/12/pic.png" alt="">
            <script src="/static/participant.js?v=3a"></script>
            <a href="/privacy">Privacy</a>
            <a href="https://example.org/static/x.css">x</a>
            <img src="//cdn.example.org/static/y.png">
            <img src="data:image/png;base64,AAA">
            <a href="/static/../secret">no</a>
        """.trimIndent()
        assertEquals(
            listOf("/static/participant.css?v=3a&x=1", "/static/participant.js?v=3a", "/media/12/pic.png"),
            OfflinePages.resourcePaths(html),
        )
    }

    @Test
    fun styleSheetUrlsAreResolvedAgainstTheSheet() {
        val css = """
            @font-face { src: url("fonts/inter.woff2") format("woff2"), url('/static/fonts/inter.woff'); }
            .a { background: url(../img/bg.png); }
            .b { background: url(data:image/svg+xml;utf8,<svg/>); }
            .c { background: url(https://example.org/x.png); }
        """.trimIndent()
        assertEquals(
            listOf("/static/css/fonts/inter.woff2", "/static/fonts/inter.woff", "/static/img/bg.png"),
            OfflinePages.cssPaths(css, "/static/css/participant.css?v=1"),
        )
    }
}
