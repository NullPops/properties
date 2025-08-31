package io.github.nullpops.properties

/**
 * A logical grouping of configuration items.
 *
 * - Preserves declaration order for UI/menus via [items] (insertion-ordered).
 * - Enforces unique keys per config.
 */
open class Configuration(val name: String? = null) {
    private val _items = LinkedHashMap<String, ConfigurationItem<*>>() // ordered + fast lookup

    /** Public, ordered view of the items. */
    val items: List<ConfigurationItem<*>> get() = _items.values.toList()

    /**
     * Get a [ConfigurationItem] by key or throw with a helpful error.
     * Prefer [itemOrNull] when you expect absence.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> item(key: String): ConfigurationItem<T> =
        _items[key] as? ConfigurationItem<T>
            ?: error("Config item not found for key '$key' in config '${name ?: "<unnamed>"}'")

    /** Get a [ConfigurationItem] by key or null if not present. */
    @Suppress("UNCHECKED_CAST")
    fun <T> itemOrNull(key: String): ConfigurationItem<T>? =
        _items[key] as? ConfigurationItem<T>

    /** Internal add; enforces unique keys. Called by [ConfigurationItem] during init. */
    internal fun addInternal(item: ConfigurationItem<*>) {
        require(!_items.containsKey(item.key)) {
            "Duplicate config key '${item.key}' in config '${name ?: "<unnamed>"}'"
        }
        _items[item.key] = item
    }

    /**
     * Optional explicit add if you instantiate items without the primary ctor.
     * (Normally unnecessary—[ConfigurationItem] registers itself with both this [Configuration]
     * and [Properties] in its init block.)
     */
    fun add(item: ConfigurationItem<*>) = addInternal(item)
}

/**
 * A single configuration entry.
 *
 * - Strings are stored raw; other types are JSON (matching ConfigManager behavior).
 * - Automatically registers with [Properties] and its owning [Configuration] on creation.
 */
class ConfigurationItem<T>(
    val configuration: Configuration,
    val name: String,               // Display label
    val key: String,                // Stable storage key
    val defaultValue: T,
    val secret: Boolean = false,    // If true, mask value in toString/logging
    val reset: Boolean = false,
    val description: String? = null // Optional human-readable help text
) {
    init {
        // Register globally and attach to owning config (enforces unique keys there)
        Properties.register(this)
        configuration.addInternal(this)

        if (reset)
            Properties.unset(key)
    }

    /** Get current value as String (raw if String, JSON otherwise). */
    fun getStringValue(): String = Properties.getString(key, defaultValue.toString())

    /**
     * Typed getter. Must be called with the correct type parameter for this item:
     * `val enabled = myItem.get<Boolean>()`
     */
    inline fun <reified R> get(): R =
        Properties.get(key, defaultValue as R)

    /** Typed setter. Persists immediately by default. */
    fun set(value: T, saveNow: Boolean = true) {
        Properties.set(key, value, saveNow)
    }

    fun unset() {
        Properties.unset(key)
    }

    /** Convenience: toggle only for boolean items. */
    fun toggle() {
        require(defaultValue is Boolean) {
            "toggle() only valid for Boolean config items (key='$key')"
        }
        val current = Properties.get<Boolean>(key, defaultValue)
        Properties.set(key, !current)
    }

    override fun toString(): String {
        val masked = if (secret) "********" else getStringValue()
        return "ConfigItem(name='$name', key='$key', value=$masked)"
    }

    fun String.key(): String {
        return "${configuration.name}.$this"
    }
}
