@file:Suppress("unused")

package io.github.nullpops.properties

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.github.nullpops.events.ConfigChanged
import nullpops.events.GlobalEventBus
import nullpops.logger.Logger
import java.io.BufferedReader
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Backing store for config key/value pairs.
 * NOTE: We intentionally keep values as Strings to remain compatible with the
 * previous behavior where raw Strings are stored as-is, and non-Strings are JSON.
 */
class Properties : ConcurrentHashMap<String, String>()

/**
 * Drop-in PropertiesManager with:
 * - Safe, explicit configuration of the directory (no lateinit trap).
 * - Atomic save (write to .tmp then move).
 * - Thread-safety via RW lock + concurrent map.
 * - Type-safe get/set with sane defaults and detailed logging.
 *
 * Default location (if you don't call [configure]): {user.home}/.config/nullpops/
 */
object PropertiesManager {
    // ---- Public surface -----------------------------------------------------

    /** Pretty-printing JSON for human-diffable files. */
    @JvmField val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    @JvmField val logger = Logger("ConfigManager")

    /**
     * Configure where the config lives. Call once during app boot.
     * If not called, defaults to: {user.home}/.config/nullpops/
     */
    @Synchronized
    fun configure(baseDir: Path = defaultBaseDir(), fileName: String = "properties.json") {
        if (initialized) return
        this.baseDir = baseDir.toAbsolutePath().normalize()
        this.propertiesPath = this.baseDir.resolve(fileName)
        ensureDirs()
        loadInternal()
        initialized = true
        logger.info("Config initialized at $propertiesPath (${properties.size} keys)")
    }

    /** Get a typed value; strings come back verbatim; everything else is parsed from JSON. */
    inline fun <reified T> get(key: String, default: T): T = lock.read {
        ensure()
        val raw = properties[key] ?: return default
        return try {
            when (T::class) {
                String::class -> raw as T
                else -> gson.fromJson(raw, T::class.java) ?: default
            }
        } catch (e: Exception) {
            logger.error("Error parsing key '$key' with value '$raw': ${e.message}")
            default
        }
    }

    /** Convenience for Strings (avoids specifying the type parameter). */
    fun getString(key: String, default: String): String = get(key, default)

    /** Returns a registered config item by key */
    @Suppress("UNCHECKED_CAST")
    fun <T> getItem(key: String): ConfigurationItem<T>? = getGeneric(key) as ConfigurationItem<T>?

    fun getGeneric(key: String): ConfigurationItem<*>? = lock.read {
        configurationItems.firstOrNull { it.key == key }
    }

    /**
     * Set a value. We only write + emit ConfigChanged if the value actually changed.
     * Strings are stored raw; other types are stored as JSON (compat with old behavior).
     */
    fun <T> set(key: String, value: T, saveNow: Boolean = true) {
        val changed = lock.write { updateValueUnsafe(key, value) }
        if (changed) {
            GlobalEventBus.post(ConfigChanged(getItem<T>(key)))
            if (saveNow) save()
        }
    }

    fun unset(key: String) {
        clearValueUnsafe(key)
    }

    /** Save the current state to disk (atomic when possible). */
    fun save() = lock.read {
        ensure()
        if (properties.isEmpty()) return@read
        val json = gson.toJson(properties)

        try {
            // Write atomically: file.tmp -> file
            val tmp = propertiesPath.resolveSibling("${propertiesPath.fileName}.tmp")
            Files.newBufferedWriter(tmp, UTF_8).use { it.write(json) }
            Files.move(
                tmp,
                propertiesPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: IOException) {
            logger.error("Failed to save config at $propertiesPath: ${e.message}")
        }
    }

    /** Add a known ConfigItem (optional pattern you were using). */
    fun register(item: ConfigurationItem<*>) = lock.write { configurationItems.add(item) }

    // ---- Internals ----------------------------------------------------------

    // Mutable state guarded by lock
    val lock = ReentrantReadWriteLock()
    val properties: Properties = Properties()
    val configurationItems = ArrayList<ConfigurationItem<*>>()

    @Volatile private var initialized = false
    private lateinit var baseDir: Path
    private lateinit var propertiesPath: Path

    fun ensure() {
        if (!initialized) configure()
    }

    private fun defaultBaseDir(): Path {
        val home = System.getProperty("user.home")
        // XDG-ish default: ~/.config/nullpops
        return Paths.get(home).resolve(".config").resolve("nullpops")
    }

    private fun ensureDirs() {
        try {
            Files.createDirectories(baseDir)
        } catch (e: IOException) {
            throw IllegalStateException("Cannot create config directory: $baseDir", e)
        }
    }

    private fun loadInternal() {
        val start = Instant.now()
        val target = pickExistingFile()
        if (target == null) {
            logger.info("No existing config found; starting empty at $propertiesPath")
            return
        }

        try {
            Files.newBufferedReader(target, UTF_8).use { reader: BufferedReader ->
                properties.clear()
                @Suppress("UNCHECKED_CAST")
                val loaded: Properties = gson.fromJson(reader, Properties::class.java)
                    ?: Properties()
                properties.putAll(loaded)
            }
            val ms = Duration.between(start, Instant.now()).toMillis()
            logger.info("Loaded ${properties.size} config properties from $target (${ms}ms)")
            // If we loaded the legacy file with no extension, migrate to the json filename on next save
        } catch (e: Exception) {
            logger.error("Failed to load config from $target: ${e.message}")
        }
    }

    private fun pickExistingFile(): Path? = when {
        Files.exists(propertiesPath) -> propertiesPath
        else -> null
    }

    private fun <T> updateValueUnsafe(key: String, value: T): Boolean {
        ensure()
        val serialized = when (value) {
            is String -> value // Keep raw strings raw for backward compatibility
            else -> gson.toJson(value)
        }
        val old = properties.put(key, serialized)
        return old != serialized
    }

    private fun clearValueUnsafe(key: String) {
        ensure()
        properties.remove(key)
    }

    @JvmSynthetic
    internal fun resetForTests(baseDir: Path, fileName: String = "properties.json") {
        lock.write {
            properties.clear()
            configurationItems.clear()
            initialized = false
        }
        configure(baseDir, fileName)
    }
}

/* -------------------------- Helpers --------------------------- */

/** Syntactic sugar: config["foo", "bar"] */
operator fun<T> PropertiesManager.get(key: String, default: String): String =
    get<T>(key, default)

/** Syntactic sugar: config["foo"] = 42 */
operator fun <T> PropertiesManager.set(key: String, value: T) =
    set(key, value, saveNow = true)