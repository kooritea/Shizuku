package moe.shizuku.manager.home

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class UserScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val code: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("code", code)
            put("createdAt", createdAt)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): UserScript {
            return UserScript(
                id = json.getString("id"),
                name = json.getString("name"),
                code = json.getString("code"),
                createdAt = json.optLong("createdAt", 0L)
            )
        }
    }
}

object UserScriptManager {

    private const val PREF_NAME = "user_scripts"
    private const val KEY_SCRIPTS = "scripts"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getAll(context: Context): List<UserScript> {
        val json = getPreferences(context).getString(KEY_SCRIPTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { UserScript.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getById(context: Context, id: String): UserScript? {
        return getAll(context).find { it.id == id }
    }

    fun add(context: Context, script: UserScript) {
        val scripts = getAll(context).toMutableList()
        scripts.add(script)
        save(context, scripts)
    }

    fun update(context: Context, script: UserScript) {
        val scripts = getAll(context).toMutableList()
        val index = scripts.indexOfFirst { it.id == script.id }
        if (index >= 0) {
            scripts[index] = script
            save(context, scripts)
        }
    }

    fun delete(context: Context, id: String) {
        val scripts = getAll(context).toMutableList()
        scripts.removeAll { it.id == id }
        save(context, scripts)
    }

    private fun save(context: Context, scripts: List<UserScript>) {
        val array = JSONArray()
        scripts.forEach { array.put(it.toJson()) }
        getPreferences(context).edit().putString(KEY_SCRIPTS, array.toString()).apply()
    }
}
