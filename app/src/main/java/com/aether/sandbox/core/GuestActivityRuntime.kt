package com.aether.sandbox.core

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.ContextThemeWrapper
import com.aether.sandbox.data.SandboxRecord
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.WeakHashMap

/** Process-local bridge. AMS sees registered host stubs; the client instantiates guest Activities. */
class GuestActivityRuntime private constructor(
    private val owner: ProxyActivity,
    private val record: SandboxRecord,
    private val prepared: PreparedGuest,
    private val activities: Map<String, ActivityInfo>,
    private val original: Instrumentation,
) : Instrumentation() {
    private val pending = WeakHashMap<Activity, Pair<ActivityInfo, Intent>>()
    private var application: android.app.Application? = null
    private val stub = STUBS[owner.slot]

    fun attachApplication(app: android.app.Application) { application = app }

    fun launch(): Boolean {
        val launcher = prepared.components.launcher ?: return false
        owner.startActivity(route(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(record.packageName, launcher))))
        return true
    }

    private fun route(source: Intent): Intent {
        val component = source.component
        if (component?.packageName != record.packageName) {
            // Explicit external targets are outside the guest component registry.
            if (component != null) throw android.content.ActivityNotFoundException("Komponen luar guest belum didukung")
            throw android.content.ActivityNotFoundException("Intent implisit guest belum didukung")
        }
        val info = activities[component.className]
            ?: throw android.content.ActivityNotFoundException("Activity tidak terdaftar: ${component.className}")
        check(info.enabled && info.applicationInfo.enabled) { "Activity dinonaktifkan" }
        val supportedFlags = Intent.FLAG_ACTIVITY_NEW_TASK
        check(source.flags and supportedFlags.inv() == 0) { "Flag task khusus belum didukung" }
        return Intent(source).setComponent(ComponentName(owner, stub))
            .putExtra(EXTRA_COMPONENT, component.className).putExtra(ProxyActivity.EXTRA_ID, record.id)
    }

    override fun newActivity(cl: ClassLoader, className: String, intent: Intent): Activity {
        if (className != stub.name) return original.newActivity(cl, className, intent)
        check(intent.getStringExtra(ProxyActivity.EXTRA_ID) == record.id) { "Instance Activity tidak cocok" }
        val name = requireNotNull(intent.getStringExtra(EXTRA_COMPONENT))
        val info = requireNotNull(activities[name]) { "Activity guest tidak terdaftar" }
        val guestIntent = Intent(intent).setComponent(ComponentName(record.packageName, name)).apply {
            removeExtra(EXTRA_COMPONENT); removeExtra(ProxyActivity.EXTRA_ID)
            setExtrasClassLoader(prepared.context.classLoader)
        }
        val target = info.targetActivity ?: info.name
        return original.newActivity(prepared.context.classLoader, target, guestIntent).also {
            it.intent = guestIntent
            pending[it] = info to guestIntent
        }
    }

    override fun callActivityOnCreate(activity: Activity, state: Bundle?) {
        pending.remove(activity)?.let { (sourceInfo, guestIntent) ->
            activity.intent = guestIntent
            state?.classLoader = prepared.context.classLoader
            val app = requireNotNull(application) { "Application guest belum siap" }
            val context = prepared.context.forActivity(activity.baseContext).also { it.application = app }
            field(ContextWrapper::class.java, "mBase").set(activity, context)
            field(ContextThemeWrapper::class.java, "mResources").set(activity, null)
            field(ContextThemeWrapper::class.java, "mTheme").set(activity, null)
            field(Activity::class.java, "mApplication").set(activity, app)
            field(Activity::class.java, "mComponent").set(activity, activity.intent.component)
            val info = ActivityInfo(sourceInfo).apply { applicationInfo = context.applicationInfo }
            field(Activity::class.java, "mActivityInfo").set(activity, info)
            val theme = info.theme.takeIf { it != 0 } ?: context.applicationInfo.theme.takeIf { it != 0 }
                ?: android.R.style.Theme_Material_Light_NoActionBar
            activity.setTheme(theme)
        }
        original.callActivityOnCreate(activity, state)
    }

    // This method overrides a non-SDK virtual entry point at runtime. Keep its exact JVM signature.
    @Suppress("unused")
    fun execStartActivity(who: Context, contextThread: IBinder, token: IBinder?, target: Activity?,
        intent: Intent, requestCode: Int, options: Bundle?): ActivityResult? {
        val routed = if (who is Activity && who.application === application) route(intent) else intent
        val method = Instrumentation::class.java.getDeclaredMethod("execStartActivity", Context::class.java,
            IBinder::class.java, IBinder::class.java, Activity::class.java, Intent::class.java,
            Int::class.javaPrimitiveType, Bundle::class.java).apply { isAccessible = true }
        return try { method.invoke(original, who, contextThread, token, target, routed, requestCode, options) as ActivityResult? }
        catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
    }

    override fun callActivityOnStart(activity: Activity) = original.callActivityOnStart(activity)
    override fun callActivityOnResume(activity: Activity) = original.callActivityOnResume(activity)
    override fun callActivityOnPause(activity: Activity) = original.callActivityOnPause(activity)
    override fun callActivityOnStop(activity: Activity) = original.callActivityOnStop(activity)
    override fun callActivityOnDestroy(activity: Activity) = original.callActivityOnDestroy(activity)
    override fun callActivityOnRestart(activity: Activity) = original.callActivityOnRestart(activity)
    override fun callActivityOnNewIntent(activity: Activity, intent: Intent) = original.callActivityOnNewIntent(activity, intent)
    override fun callActivityOnSaveInstanceState(activity: Activity, state: Bundle) = original.callActivityOnSaveInstanceState(activity, state)
    override fun callActivityOnRestoreInstanceState(activity: Activity, state: Bundle) = original.callActivityOnRestoreInstanceState(activity, state)
    override fun callActivityOnPostCreate(activity: Activity, state: Bundle?) = original.callActivityOnPostCreate(activity, state)
    override fun callActivityOnUserLeaving(activity: Activity) = original.callActivityOnUserLeaving(activity)

    companion object {
        private const val EXTRA_COMPONENT = "aether_guest_component"
        val STUBS = listOf(GuestStubActivity::class.java, GuestStubActivity1::class.java,
            GuestStubActivity2::class.java, GuestStubActivity3::class.java)
        private fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        fun install(owner: ProxyActivity, record: SandboxRecord, prepared: PreparedGuest,
            activities: Map<String, ActivityInfo>): GuestActivityRuntime {
            if (Build.VERSION.SDK_INT >= 28) check(HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/app/ActivityThread;", "Landroid/app/Activity;", "Landroid/app/Instrumentation;",
                "Landroid/content/ContextWrapper;", "Landroid/view/ContextThemeWrapper;")) {
                "Akses runtime internal tidak tersedia pada perangkat ini"
            }
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
            val instrumentation = field(threadClass, "mInstrumentation")
            val original = instrumentation.get(thread) as Instrumentation
            check(original !is GuestActivityRuntime) { "Runtime Activity sudah dipasang" }
            return GuestActivityRuntime(owner, record, prepared, activities, original).also { instrumentation.set(thread, it) }
        }
    }
}

/** A stub should only instantiate if an OS restart occurs without the runtime bridge. */
open class GuestStubActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(android.widget.TextView(this).apply { text = "Runtime perlu dimulai ulang dari AetherSandbox." })
    }
}
class GuestStubActivity1 : GuestStubActivity()
class GuestStubActivity2 : GuestStubActivity()
class GuestStubActivity3 : GuestStubActivity()
