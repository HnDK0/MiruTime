package dev.mirutime.core.engine.binding

import dev.mirutime.core.engine.LuaSession
import dev.mirutime.core.engine.withoutBom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction

// JSON bindings via kotlinx.serialization JsonElement -> LuaValue.
object JsonBindings {

    fun install(session: LuaSession) {
        val globals = session.globals
        globals.set("json_parse", object : OneArgFunction() {
            // NoveLA LuaSourceLoader.kt:1313-1317: any failure → NIL, so Lua
            // code can fall back (`or {}`) without a pcall.
            override fun call(str: LuaValue): LuaValue = try {
                Json.parseToJsonElement(str.checkjstring().withoutBom()).toLuaValue()
            } catch (e: Exception) {
                LuaValue.NIL
            }
        })
        // json_stringify(value): the Lua->JSON direction is already the
        // json_encode global (CryptoBindings) — delegate so both serializers
        // stay identical. NoveLA returns nil when stringification fails.
        globals.set("json_stringify", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = try {
                globals.get("json_encode").call(arg)
            } catch (e: Exception) {
                LuaValue.NIL
            }
        })
    }

    // 2^53: every integer up to it has an exact double.
    private const val MAX_EXACT_DOUBLE = 9_007_199_254_740_992L

    // An integer literal (no fraction, no exponent) that a double cannot hold.
    private fun isBeyondExactDouble(content: String): Boolean {
        val digits = if (content.startsWith('-')) content.substring(1) else content
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return false
        val value = content.toLongOrNull() ?: return true
        return value > MAX_EXACT_DOUBLE || value < -MAX_EXACT_DOUBLE
    }

    private fun JsonElement.toLuaValue(): LuaValue = when (this) {
        is JsonNull -> LuaValue.NIL
        is JsonPrimitive -> when {
            isString -> LuaValue.valueOf(content)
            // Booleans before numbers: BooleanPrimitive is also a string
            // content ("true"/"false").
            content == "true" || content == "false" -> LuaValue.valueOf(content == "true")
            // Lua 5.2 numbers are doubles, so an integer beyond 2^53 cannot be held
            // exactly (the id 7123456789012345678 reads back as 7.1234567890123e+18).
            // Its digits stay a string, which Lua still coerces in arithmetic.
            isBeyondExactDouble(content) -> LuaValue.valueOf(content)
            else -> {
                val num: Double? = content.toDoubleOrNull()
                if (num != null) LuaValue.valueOf(num) else LuaValue.valueOf(content)
            }
        }
        is JsonArray -> LuaValue.listOf(map { it.toLuaValue() }.toTypedArray())
        is JsonObject -> LuaTable().also { table ->
            forEach { (key, value) -> table.set(key, value.toLuaValue()) }
        }
    }
}
