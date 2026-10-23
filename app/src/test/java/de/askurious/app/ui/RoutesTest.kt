package de.askurious.app.ui

import de.askurious.app.BuildConfig
import de.askurious.app.data.Api
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The deep links of the join screen (JOIN_LINKS) give the code and the server that joinCode and joinServer read, and
 * they, the App Link filters of the manifest and the https servers of KNOWN_SERVERS name the same hosts.
 */
class RoutesTest {
    private fun link(pattern: String) = pattern.replace("{code}", "k7qm2x-t3f9a")

    @Test
    fun everyJoinLinkGivesItsCode() {
        for (pattern in JOIN_LINKS) assertEquals(pattern, "k7qm2x-t3f9a", joinCode(link(pattern)))
    }

    @Test
    fun everyAppLinkNamesItsHostTheSchemeLinkNone() {
        for (host in APP_LINK_HOSTS) {
            val pattern = JOIN_LINKS.single { it.startsWith("https://$host/") }
            assertEquals("https://$host", joinServer(link(pattern), allowHttp = false))
            assertTrue(pattern, isJoinLink(link(pattern)))
            assertTrue(pattern, Api.isKnown("https://$host"))
        }
        assertNull(joinServer(link(JOIN_LINKS.last()), allowHttp = false))
    }

    @Test
    fun theMainAddressesOfTheBrandsAreAppLinks() {
        for (host in listOf("askurious.de", "ask.askurious.de", "polltergeist.de", "ask.polltergeist.de")) {
            assertTrue(host, "https://$host/j/{code}" in JOIN_LINKS)
        }
    }

    @Test
    fun manifestKnownServersAndJoinLinksNameTheSameHosts() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val ns = "http://schemas.android.com/apk/res/android"
        val filters = doc.getElementsByTagName("intent-filter").let { l -> (0 until l.length).map { l.item(it) as Element } }
        val appLinks = filters.filter { f ->
            val data = f.getElementsByTagName("data").let { l -> (0 until l.length).map { l.item(it) as Element } }
            data.any { it.getAttributeNS(ns, "scheme") == "https" }
        }
        val hosts = appLinks.map { f ->
            assertEquals("every App Link filter is verified", "true", f.getAttributeNS(ns, "autoVerify"))
            val data = f.getElementsByTagName("data").let { l -> (0 until l.length).map { l.item(it) as Element } }
            val own = data.map { it.getAttributeNS(ns, "host") }.filter { it.isNotEmpty() }
            assertEquals("one host per filter, so one host failing verification costs no other its App Link", 1, own.size)
            assertEquals("/j/", data.map { it.getAttributeNS(ns, "pathPrefix") }.single { it.isNotEmpty() })
            own.single()
        }
        assertEquals(APP_LINK_HOSTS.sorted(), hosts.sorted())
        val known = Api.servers(BuildConfig.KNOWN_SERVERS).filter { it.startsWith("https://") }.map { it.removePrefix("https://") }
        assertEquals(known.sorted(), hosts.sorted())
    }
}
