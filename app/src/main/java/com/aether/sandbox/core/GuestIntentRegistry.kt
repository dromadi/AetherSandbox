package com.aether.sandbox.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.PatternMatcher
import org.xmlpull.v1.XmlPullParser

/** Manifest-based matching for the guest's own Activities. External resolution remains with Android. */
class GuestIntentRegistry(private val context: Context, private val entries: List<Pair<ActivityInfo, List<IntentFilter>>>) {
    fun resolve(intent: Intent, defaultOnly: Boolean = false): ActivityInfo? = query(intent, defaultOnly).firstOrNull()?.activityInfo
    fun query(intent: Intent, defaultOnly: Boolean = false): List<ResolveInfo> {
        val component = intent.component
        val pkg = entries.firstOrNull()?.first?.packageName
        if (component != null) return entries.filter { it.first.name == component.className && component.packageName == pkg }
            .filter { it.first.enabled && it.first.applicationInfo.enabled }.map { ResolveInfo().apply { activityInfo = it.first } }
        if (intent.`package` != null && intent.`package` != pkg) return emptyList()
        return entries.filter { it.first.enabled && it.first.applicationInfo.enabled }.mapNotNull { (info, filters) ->
            filters.filter { !defaultOnly || it.hasCategory(Intent.CATEGORY_DEFAULT) }.mapNotNull { filter ->
                val match = filter.match(intent.action, intent.resolveTypeIfNeeded(context.contentResolver), intent.scheme,
                    intent.data, intent.categories, "AetherIntent")
                if (match < 0) null else ResolveInfo().apply { activityInfo = info; priority = filter.priority; this.match = match; isDefault = filter.hasCategory(Intent.CATEGORY_DEFAULT) }
            }.maxByOrNull { it.priority }
        }.sortedWith(compareByDescending<ResolveInfo> { it.priority }.thenByDescending { it.match })
    }
    companion object {
        private const val NS = "http://schemas.android.com/apk/res/android"
        fun read(context: Context, app: ApplicationInfo, activities: Collection<ActivityInfo>): GuestIntentRegistry {
            val byName = activities.associateBy { it.name }
            val filters = linkedMapOf<ActivityInfo, MutableList<IntentFilter>>()
            context.packageManager.getResourcesForApplication(app).assets.openXmlResourceParser("AndroidManifest.xml").use { xml ->
                var owner: ActivityInfo? = null
                var filter: IntentFilter? = null
                while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType == XmlPullParser.START_TAG) when (xml.name) {
                        "activity", "activity-alias" -> {
                            val name = xml.getAttributeValue(NS, "name").orEmpty()
                            owner = byName[if (name.startsWith('.')) app.packageName + name else if ('.' !in name) "${app.packageName}.$name" else name]
                        }
                        "intent-filter" -> if (owner != null) filter = IntentFilter().apply { priority = xml.getAttributeIntValue(NS, "priority", 0) }
                        "action" -> xml.getAttributeValue(NS, "name")?.let { filter?.addAction(it) }
                        "category" -> xml.getAttributeValue(NS, "name")?.let { filter?.addCategory(it) }
                        "data" -> filter?.let { f ->
                            xml.getAttributeValue(NS, "mimeType")?.let(f::addDataType)
                            xml.getAttributeValue(NS, "scheme")?.let(f::addDataScheme)
                            xml.getAttributeValue(NS, "host")?.let { f.addDataAuthority(it, xml.getAttributeValue(NS, "port")) }
                            for ((key, type) in listOf("path" to PatternMatcher.PATTERN_LITERAL, "pathPrefix" to PatternMatcher.PATTERN_PREFIX, "pathPattern" to PatternMatcher.PATTERN_SIMPLE_GLOB)) {
                                xml.getAttributeValue(NS, key)?.let { f.addDataPath(it, type) }
                            }
                        }
                    }
                    if (xml.eventType == XmlPullParser.END_TAG) when (xml.name) {
                        "intent-filter" -> { if (owner != null && filter != null) filters.getOrPut(owner!!) { mutableListOf() }.add(filter!!); filter = null }
                        "activity", "activity-alias" -> owner = null
                    }
                    xml.next()
                }
            }
            return GuestIntentRegistry(context, activities.map { it to filters[it].orEmpty() })
        }
    }
}
