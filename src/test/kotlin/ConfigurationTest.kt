import io.github.nullpops.properties.Configuration
import io.github.nullpops.properties.ConfigurationItem
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConfigurationTest {

    @Test
    fun `items preserve insertion order`() {
        val cfg = Configuration("App")
        val a = ConfigurationItem(cfg, name = "A", key = "order.a", defaultValue = 1, reset = true)
        val b = ConfigurationItem(cfg, name = "B", key = "order.b", defaultValue = 2, reset = true)
        val c = ConfigurationItem(cfg, name = "C", key = "order.c", defaultValue = 3, reset = true)

        val names = cfg.items.map { it.name }
        assertEquals(listOf("A", "B", "C"), names)
        // sanity: item() retrieves by key and type inference is ok
        assertSame(a, cfg.item<Int>("order.a"))
        assertSame(c, cfg.item<Int>("order.c"))
    }

    @Test
    fun `duplicate keys in same configuration throw`() {
        val cfg = Configuration("DupTest")
        ConfigurationItem(cfg, name = "First", key = "dup.key", defaultValue = "x", reset = true)
        val ex = assertThrows<IllegalArgumentException> {
            ConfigurationItem(cfg, name = "Second", key = "dup.key", defaultValue = "y", reset = true)
        }
        assertTrue(ex.message!!.contains("Duplicate config key 'dup.key'"))
    }

    @Test
    fun `item and itemOrNull behave as documented`() {
        val cfg = Configuration("Lookup")
        val item = ConfigurationItem(cfg, name = "Port", key = "net.port", defaultValue = 8080, reset = true)

        // existing
        val found: ConfigurationItem<Int> = cfg.item("net.port")
        assertSame(item, found)

        // missing -> null
        val missing: ConfigurationItem<Int>? = cfg.itemOrNull("nope.key")
        assertNull(missing)

        // missing -> throws with helpful message
        val ex = assertThrows<IllegalStateException> { cfg.item<Int>("nope.key") }
        assertTrue(ex.message!!.contains("Config item not found for key 'nope.key'"))
        assertTrue(ex.message!!.contains("Lookup"))
    }

    @Test
    fun `getStringValue returns default when unset and stored value after set`() {
        val cfg = Configuration("Strings")
        val item = ConfigurationItem(cfg, name = "ApiUrl", key = "api.url", defaultValue = "https://example.test", reset = true)

        // default path (unset)
        assertEquals("https://example.test", item.getStringValue())

        // after set
        item.set("https://prod.example")
        assertEquals("https://prod.example", item.getStringValue())
    }

    @Test
    fun `typed get returns default when unset and stored value after set`() {
        val cfg = Configuration("Typed")
        val retries = ConfigurationItem(cfg, name = "Retries", key = "net.retries", defaultValue = 3, reset = true)
        val enabled = ConfigurationItem(cfg, name = "Enabled", key = "feature.enabled", defaultValue = false, reset = true)
        val list = ConfigurationItem(cfg, name = "Hosts", key = "hosts", defaultValue = listOf("a", "b"), reset = true)

        // defaults
        assertEquals(3, retries.get<Int>())
        assertEquals(false, enabled.get<Boolean>())
        assertEquals(listOf("a", "b"), list.get<List<String>>())

        // after set
        retries.set(5)
        enabled.set(true)
        list.set(listOf("x", "y", "z"))

        assertEquals(5, retries.get<Int>())
        assertEquals(true, enabled.get<Boolean>())
        assertEquals(listOf("x", "y", "z"), list.get<List<String>>())
    }

    @Test
    fun `toggle only valid for boolean and flips value`() {
        val cfg = Configuration("Toggle")
        val flag = ConfigurationItem(cfg, name = "Flag", key = "flag", defaultValue = false, reset = true)

        // flips false -> true -> false
        flag.toggle()
        assertTrue(flag.get<Boolean>())
        flag.toggle()
        assertFalse(flag.get<Boolean>())

        // non-boolean -> throws
        val notBool = ConfigurationItem(cfg, name = "Count", key = "count", defaultValue = 1, reset = true)
        val ex = assertThrows<IllegalArgumentException> { notBool.toggle() }
        assertTrue(ex.message!!.contains("toggle() only valid for Boolean"))
    }

    @Test
    fun `toString masks secret values`() {
        val cfg = Configuration("Secrets")
        val pub = ConfigurationItem(cfg, name = "Public", key = "pub.key", defaultValue = "abc123", secret = false, reset = true)
        val sec = ConfigurationItem(cfg, name = "Secret", key = "sec.key", defaultValue = "s3cr3t", secret = true, reset = true)

        // when unset, getStringValue() returns default; masking should apply only to secret
        val pubStr = pub.toString()
        val secStr = sec.toString()

        assertTrue(pubStr.contains("abc123"))
        assertFalse(pubStr.contains("********"))

        assertTrue(secStr.contains("********"))
        assertFalse(secStr.contains("s3cr3t"))
    }

    @Test
    fun `explicit add on already-registered item throws due to duplicate key`() {
        val cfg = Configuration("ExplicitAdd")
        val auto = ConfigurationItem(cfg, name = "Auto", key = "auto.key", defaultValue = 1, reset = true)

        // This item is already registered in its init block
        val alreadyAdded = ConfigurationItem(cfg, name = "Manual", key = "manual.key", defaultValue = 2, reset = true)

        // Calling add() again should fail because the key is already present
        val ex = assertThrows<IllegalArgumentException> { cfg.add(alreadyAdded) }
        assertTrue(ex.message!!.contains("Duplicate config key 'manual.key'"))
        // Sanity: existing items still retrievable
        assertSame(auto, cfg.item<Int>("auto.key"))
        assertSame(alreadyAdded, cfg.item<Int>("manual.key"))
    }

}
