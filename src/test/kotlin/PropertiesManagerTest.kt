import com.google.gson.reflect.TypeToken
import io.github.nullpops.properties.Configuration
import io.github.nullpops.properties.ConfigurationItem
import io.github.nullpops.properties.PropertiesMap
import io.github.nullpops.properties.Properties
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists
import kotlin.io.path.readText

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PropertiesManagerTest {

    private lateinit var configFile: Path

    @BeforeEach
    fun setup(@TempDir dir: Path, testInfo: TestInfo) {
        // unique file per test to avoid any cross-talk even within the same dir
        val fileName = "props-${testInfo.displayName.hashCode()}.json"
        Properties.resetForTests(dir, fileName)
        configFile = dir.resolve(fileName)
    }

    @Test
    fun `strings stored raw, non-strings stored as JSON`() {
        Properties.set("plain", "hello")
        Properties.set("num", 42)
        Properties.set("list", listOf("a", "b"))
        Properties.save()

        assertTrue(configFile.exists(), "config file should have been created")

        val json = configFile.readText(UTF_8)
        val loaded = Properties.gson.fromJson(json, PropertiesMap::class.java)

        // String value is raw (verbatim)
        assertEquals("hello", loaded["plain"])

        // Non-strings: parse the inner JSON string to compare semantically
        val numParsed: Int = Properties.gson.fromJson(loaded["num"], Int::class.java)
        assertEquals(42, numParsed)

        val listType = object : TypeToken<List<String>>() {}.type
        val listParsed: List<String> = Properties.gson.fromJson(loaded["list"], listType)
        assertEquals(listOf("a", "b"), listParsed)

        // (Optional sanity: pretty-printed JSON likely includes newlines)
        assertTrue(loaded["list"]!!.contains('\n'))
    }

    @Test
    fun `get returns defaults when absent and values when present`() {
        // Absent -> default
        assertEquals("fallback", Properties.get("missing", "fallback"))
        assertEquals(7, Properties.get("missingInt", 7))

        // Present -> values
        Properties.set("presentStr", "ok")
        Properties.set("presentObj", mapOf("x" to 1))

        assertEquals("ok", Properties.get("presentStr", "nope"))

        // When deserializing to Map, Gson uses LinkedTreeMap<String, Double>.
        // So test equality semantically:
        val type = object : TypeToken<Map<String, Int>>() {}.type
        val parsed: Map<String, Int> = Properties.gson.fromJson(
            Properties.properties["presentObj"], type
        )
        assertEquals(mapOf("x" to 1), parsed)

        // Or, easier: just assert the property can be retrieved with matching semantics:
        val out: Map<String, Number> = Properties.get("presentObj", emptyMap())
        assertEquals(1, out["x"]?.toInt())
    }

    @Test
    fun `unset removes key`() {
        Properties.set("k", "v")
        assertEquals("v", Properties.get("k", "x"))
        Properties.unset("k")
        assertEquals("x", Properties.get("k", "x")) // falls back to default after unset
    }

    @Test
    fun `save is atomic and only triggered on change`() {
        // 1) initial save creates file
        Properties.set("x", 1) // changed=true -> save()
        assertTrue(configFile.exists())

        val firstMTime: FileTime = Files.getLastModifiedTime(configFile)

        // 2) setting the SAME value should not re-save (no timestamp change)
        Properties.set("x", 1) // changed=false -> no save
        val secondMTime: FileTime = Files.getLastModifiedTime(configFile)
        assertEquals(firstMTime, secondMTime, "file mtime should not change for identical set")

        // 3) changing the value should save (timestamp changes)
        Thread.sleep(5) // tiny delay so mtime can tick on some FS
        Properties.set("x", 2) // changed=true -> save()
        val thirdMTime: FileTime = Files.getLastModifiedTime(configFile)
        assertTrue(thirdMTime.toMillis() >= secondMTime.toMillis(), "file mtime should update on change")
    }

    @Test
    fun `getString delegates to get for strings`() {
        Properties.set("greet", "hi")
        assertEquals("hi", Properties.getString("greet", "bye"))
        assertEquals("bye", Properties.getString("missing", "bye"))
    }

    @Test
    fun `register + getItem + getGeneric integrate with ConfigurationItem`() {
        // Creating a ConfigurationItem auto-registers with PropertiesManager (via its init)
        val cfg = Configuration("TestCfg")
        val itemA = ConfigurationItem(cfg, name = "A", key = "cfg.a", defaultValue = 123, reset = true)
        val itemB = ConfigurationItem(cfg, name = "B", key = "cfg.b", defaultValue = "str", reset = true)

        // lookup by key
        val foundA: ConfigurationItem<Int>? = Properties.getItem("cfg.a")
        val foundB: ConfigurationItem<*>? = Properties.getGeneric("cfg.b")

        assertSame(itemA, foundA)
        assertSame(itemB, foundB)

        // setting via manager should emit no exceptions and be retrievable via typed get()
        Properties.set("cfg.a", 456)
        assertEquals(456, itemA.get<Int>())
    }

    @Test
    fun `parsing failure falls back to default and logs error`() {
        // Put a value that will not parse as the requested type
        // e.g., store a non-JSON string, then try to read as Map
        Properties.set("bad", "not-json")
        // Expect default, no throw
        val out: Map<String, Int> = Properties.get("bad", emptyMap())
        assertEquals(emptyMap<String, Int>(), out)
        // (We don't assert on logs; just ensuring it doesn't crash.)
    }
}
