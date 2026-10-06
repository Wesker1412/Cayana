package com.cayana.backup.snapshot

import com.cayana.source.SourceType
import com.cayana.ui.settings.repository.UserSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable user preferences included in backups.
 * Excludes all device-specific capabilities (permissions, SAF URIs, calendarId, MediaStore cursors, STT model path).
 */
data class PortableUserSettings(
    val onboardingCompleted: Boolean = false,
    val enabledSources: Set<String> = setOf("SCREENSHOT"),
    val notificationsEnabled: Boolean = true,
    val privateLoggingEnforced: Boolean = true,
    val isLocalFirstOnly: Boolean = true
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("onboardingCompleted", onboardingCompleted)
        val sourcesArray = JSONArray()
        enabledSources.forEach { sourcesArray.put(it) }
        obj.put("enabledSources", sourcesArray)
        obj.put("notificationsEnabled", notificationsEnabled)
        obj.put("privateLoggingEnforced", privateLoggingEnforced)
        obj.put("isLocalFirstOnly", isLocalFirstOnly)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): PortableUserSettings {
            val onboardingCompleted = obj.optBoolean("onboardingCompleted", false)
            val sourcesArray = obj.optJSONArray("enabledSources")
            val sources = mutableSetOf<String>()
            if (sourcesArray != null) {
                for (i in 0 until sourcesArray.length()) {
                    sources.add(sourcesArray.getString(i))
                }
            } else {
                sources.add("SCREENSHOT")
            }
            val notificationsEnabled = obj.optBoolean("notificationsEnabled", true)
            val privateLoggingEnforced = obj.optBoolean("privateLoggingEnforced", true)
            val isLocalFirstOnly = obj.optBoolean("isLocalFirstOnly", true)

            return PortableUserSettings(
                onboardingCompleted = onboardingCompleted,
                enabledSources = sources,
                notificationsEnabled = notificationsEnabled,
                privateLoggingEnforced = privateLoggingEnforced,
                isLocalFirstOnly = isLocalFirstOnly
            )
        }

        fun fromUserSettings(settings: UserSettings): PortableUserSettings {
            return PortableUserSettings(
                onboardingCompleted = settings.onboardingCompleted,
                enabledSources = settings.enabledSources.map { it.name }.toSet(),
                notificationsEnabled = settings.notificationsEnabled,
                privateLoggingEnforced = settings.privateLoggingEnforced,
                isLocalFirstOnly = settings.isLocalFirstOnly
            )
        }
    }
}
