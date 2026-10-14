package com.aether.sandbox.core

import android.content.Context
import android.content.pm.ApplicationInfo
import org.xmlpull.v1.XmlPullParser

/** Public resource APIs decode binary AXML; never guesses from component ordering. */
object ManifestLauncher {
    private const val ANDROID = "http://schemas.android.com/apk/res/android"
    fun resolve(host: Context, app: ApplicationInfo): String? {
        val assets = host.packageManager.getResourcesForApplication(app).assets
        assets.openXmlResourceParser("AndroidManifest.xml").use { xml ->
            var packageName = ""
            var applicationEnabled = true
            var component: String? = null
            var componentEnabled = false
            var filterDepth = -1
            var main = false
            var launcher = false
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG) when (xml.name) {
                    "manifest" -> packageName = xml.getAttributeValue(null, "package").orEmpty()
                    "application" -> applicationEnabled = xml.getAttributeBooleanValue(ANDROID, "enabled", true)
                    "activity", "activity-alias" -> {
                        val name = xml.getAttributeValue(ANDROID, "name").orEmpty()
                        component = if (name.startsWith(".")) packageName + name else if ('.' !in name) "$packageName.$name" else name
                        componentEnabled = applicationEnabled && xml.getAttributeBooleanValue(ANDROID, "enabled", true)
                            && xml.getAttributeBooleanValue(ANDROID, "exported", true)
                    }
                    "intent-filter" -> if (component != null) { filterDepth = xml.depth; main = false; launcher = false }
                    "action" -> if (filterDepth >= 0 && xml.getAttributeValue(ANDROID, "name") == "android.intent.action.MAIN") main = true
                    "category" -> if (filterDepth >= 0 && xml.getAttributeValue(ANDROID, "name") == "android.intent.category.LAUNCHER") launcher = true
                }
                if (xml.eventType == XmlPullParser.END_TAG) when (xml.name) {
                    "intent-filter" -> {
                        if (xml.depth == filterDepth && componentEnabled && main && launcher) return component
                        filterDepth = -1
                    }
                    "activity", "activity-alias" -> { component = null; filterDepth = -1 }
                }
                xml.next()
            }
        }
        return null
    }
}
