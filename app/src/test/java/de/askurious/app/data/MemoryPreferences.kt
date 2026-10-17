package de.askurious.app.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/** Android's interface with real in-memory apply semantics; production stores and JSON are used unchanged. */
internal class MemoryPreferences {
    private val values = ConcurrentHashMap<String, String>()
    @Volatile var afterRead: (() -> Unit)? = null
    val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)) { proxy, method, args ->
        when (method.name) {
            "getString" -> (values[args!![0] as String] ?: args[1]).also { afterRead?.invoke() }
            "edit" -> editor()
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> "MemoryPreferences"
            else -> error("Unexpected preferences call: ${method.name}")
        }
    } as SharedPreferences

    private fun editor(): SharedPreferences.Editor {
        val changes = mutableMapOf<String, String>()
        return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { changes[args!![0] as String] = args[1] as String; proxy }
                "apply" -> { values.putAll(changes); null }
                "commit" -> { values.putAll(changes); true }
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
    }
}
