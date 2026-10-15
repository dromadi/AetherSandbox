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
) : Instrumentation(), AutoCloseable {
    private val pending = WeakHashMap<Activity, Pair<ActivityInfo, Intent>>()
    private var application: android.app.Application? = null
    private val routed = linkedMapOf<String, Class<out Activity>>()
    private val attachHooks = mutableListOf<de.robv.android.xposed.XC_MethodHook.Unhook>()
    private var restoreInstrumentation: (() -> Unit)? = null
    private fun stubFor(component: String): Class<out Activity> = routed.getOrPut(component) {
        val pool = POOLS[owner.slot]
        check(routed.size < pool.size) { "Batas ${pool.size} jenis Activity per instance tercapai" }
        pool[routed.size]
    }

    fun attachApplication(app: android.app.Application) { application = app }

    fun launch(): Boolean {
        val launcher = prepared.components.launcher ?: return false
        owner.startActivity(route(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(record.packageName, launcher))))
        return true
    }

    private fun route(source: Intent): Intent {
        val component = source.component
        val info = if (component != null && component.packageName == record.packageName) activities[component.className]
            ?: throw android.content.ActivityNotFoundException("Activity tidak terdaftar: ${component.className}")
            else prepared.intents.resolve(source, true)
        if (info == null) {
            if (source.`package` == record.packageName || component?.packageName == record.packageName)
                throw android.content.ActivityNotFoundException("Intent guest tidak cocok dengan manifest")
            // The system handles external apps and file pickers, including their real permission checks.
            return Intent(source)
        }
        check(info.enabled && info.applicationInfo.enabled) { "Activity dinonaktifkan" }
        val supportedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
            Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_FORWARD_RESULT or
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        check(source.flags and supportedFlags.inv() == 0) { "Flag task ini belum didukung" }
        return Intent(source).setPackage(null).setComponent(ComponentName(owner, stubFor(info.name)))
            .putExtra(EXTRA_COMPONENT, info.name).putExtra(ProxyActivity.EXTRA_ID, record.id)
    }

    private fun guestIntent(source: Intent): Intent {
        source.setExtrasClassLoader(prepared.context.classLoader)
        val name = requireNotNull(source.getStringExtra(EXTRA_COMPONENT))
        return Intent(source).setComponent(ComponentName(record.packageName, name)).apply {
            removeExtra(EXTRA_COMPONENT); removeExtra(ProxyActivity.EXTRA_ID)
            setExtrasClassLoader(prepared.context.classLoader)
        }
    }

    override fun newActivity(cl: ClassLoader, className: String, intent: Intent): Activity {
        if (POOLS[owner.slot].none { it.name == className }) return original.newActivity(cl, className, intent)
        intent.setExtrasClassLoader(prepared.context.classLoader)
        check(intent.getStringExtra(ProxyActivity.EXTRA_ID) == record.id) { "Instance Activity tidak cocok" }
        val name = requireNotNull(intent.getStringExtra(EXTRA_COMPONENT))
        val info = requireNotNull(activities[name]) { "Activity guest tidak terdaftar" }
        val guestIntent = guestIntent(intent)
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
            val theme = sourceInfo.theme.takeIf { it != 0 } ?: prepared.context.applicationInfo.theme.takeIf { it != 0 }
                ?: android.R.style.Theme_Material_Light_NoActionBar
            activity.setTheme(theme)
        }
        original.callActivityOnCreate(activity, state)
        if (activity.application === application) {
            activity.window.decorView.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            android.util.Log.d("AetherActivity", "Created ${activity.javaClass.name}")
        }
    }

    // This method overrides a non-SDK virtual entry point at runtime. Keep its exact JVM signature.
    @Suppress("unused")
    fun execStartActivity(who: Context, contextThread: IBinder, token: IBinder?, target: Activity?,
        intent: Intent, requestCode: Int, options: Bundle?): ActivityResult? {
        android.util.Log.d("AetherActivity", "Navigate ${who.javaClass.name} to ${intent.component}")
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
    override fun callActivityOnNewIntent(activity: Activity, intent: Intent) {
        val delivered = if (activity.application === application) guestIntent(intent) else intent
        original.callActivityOnNewIntent(activity, delivered)
    }
    override fun callActivityOnSaveInstanceState(activity: Activity, state: Bundle) = original.callActivityOnSaveInstanceState(activity, state)
    override fun callActivityOnRestoreInstanceState(activity: Activity, state: Bundle) = original.callActivityOnRestoreInstanceState(activity, state)
    override fun callActivityOnPostCreate(activity: Activity, state: Bundle?) = original.callActivityOnPostCreate(activity, state)
    override fun callActivityOnUserLeaving(activity: Activity) = original.callActivityOnUserLeaving(activity)

    private fun installAttachHook() {
        val methods = Activity::class.java.declaredMethods.filter {
            it.name == "attach" && it.parameterTypes.firstOrNull() == Context::class.java &&
                it.parameterTypes.contains(ActivityInfo::class.java)
        }
        check(methods.isNotEmpty()) { "Activity.attach tidak tersedia" }
        // API 36 has delegating overloads. Hook every supported shape, wrapping its Context once.
        methods.forEach { method -> attachHooks += de.robv.android.xposed.XposedBridge.hookMethod(method, object : de.robv.android.xposed.XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                val entry = pending[activity] ?: return
                try {
                    val app = requireNotNull(application) { "Application guest belum siap" }
                    if (param.args[0] !is VirtualContext) param.args[0] = prepared.context.forActivity(param.args[0] as Context).also { it.application = app }
                    method.parameterTypes.forEachIndexed { index, type ->
                        when (type) {
                            android.app.Application::class.java -> param.args[index] = app
                            ActivityInfo::class.java -> {
                                // ActivityThread applies the record's theme after attach returns.
                                // Keep that theme consistent with the guest resources installed here.
                                (param.args[index] as ActivityInfo).theme = entry.first.theme.takeIf { it != 0 }
                                    ?: prepared.context.applicationInfo.theme.takeIf { it != 0 }
                                    ?: android.R.style.Theme_Material_Light_NoActionBar
                                param.args[index] = ActivityInfo(entry.first).apply { applicationInfo = prepared.context.applicationInfo }
                            }
                            Intent::class.java -> param.args[index] = entry.second
                        }
                    }
                } catch (error: Throwable) { param.throwable = error }
            }
        }) }
    }
    override fun close() {
        attachHooks.forEach { it.unhook() }; attachHooks.clear()
        prepared.context.activityRouter = null
        restoreInstrumentation?.invoke(); restoreInstrumentation = null
    }

    companion object {
        private const val EXTRA_COMPONENT = "aether_guest_component"
        val STUBS = listOf(GuestStubActivity::class.java, GuestStubActivity1::class.java,
            GuestStubActivity2::class.java, GuestStubActivity3::class.java)
        val POOLS: List<List<Class<out Activity>>> = listOf(
            listOf(GuestStubActivity::class.java, GuestSlot0Activity0::class.java, GuestSlot0Activity1::class.java, GuestSlot0Activity2::class.java, GuestSlot0Activity3::class.java, GuestSlot0Activity4::class.java, GuestSlot0Activity5::class.java, GuestSlot0Activity6::class.java, GuestSlot0Activity7::class.java),
            listOf(GuestStubActivity1::class.java, GuestSlot1Activity0::class.java, GuestSlot1Activity1::class.java, GuestSlot1Activity2::class.java, GuestSlot1Activity3::class.java, GuestSlot1Activity4::class.java, GuestSlot1Activity5::class.java, GuestSlot1Activity6::class.java, GuestSlot1Activity7::class.java),
            listOf(GuestStubActivity2::class.java, GuestSlot2Activity0::class.java, GuestSlot2Activity1::class.java, GuestSlot2Activity2::class.java, GuestSlot2Activity3::class.java, GuestSlot2Activity4::class.java, GuestSlot2Activity5::class.java, GuestSlot2Activity6::class.java, GuestSlot2Activity7::class.java),
            listOf(GuestStubActivity3::class.java, GuestSlot3Activity0::class.java, GuestSlot3Activity1::class.java, GuestSlot3Activity2::class.java, GuestSlot3Activity3::class.java, GuestSlot3Activity4::class.java, GuestSlot3Activity5::class.java, GuestSlot3Activity6::class.java, GuestSlot3Activity7::class.java)
        )
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
            return GuestActivityRuntime(owner, record, prepared, activities, original).also { bridge ->
                instrumentation.set(thread, bridge)
                bridge.restoreInstrumentation = { if (instrumentation.get(thread) === bridge) instrumentation.set(thread, original) }
                prepared.context.activityRouter = bridge::route
                try { bridge.installAttachHook() } catch (error: Throwable) { bridge.close(); throw error }
            }
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

class GuestSlot0Activity0 : GuestStubActivity()
class GuestSlot0Activity1 : GuestStubActivity()
class GuestSlot0Activity2 : GuestStubActivity()
class GuestSlot0Activity3 : GuestStubActivity()
class GuestSlot0Activity4 : GuestStubActivity()
class GuestSlot0Activity5 : GuestStubActivity()
class GuestSlot0Activity6 : GuestStubActivity()
class GuestSlot0Activity7 : GuestStubActivity()
class GuestSlot1Activity0 : GuestStubActivity()
class GuestSlot1Activity1 : GuestStubActivity()
class GuestSlot1Activity2 : GuestStubActivity()
class GuestSlot1Activity3 : GuestStubActivity()
class GuestSlot1Activity4 : GuestStubActivity()
class GuestSlot1Activity5 : GuestStubActivity()
class GuestSlot1Activity6 : GuestStubActivity()
class GuestSlot1Activity7 : GuestStubActivity()
class GuestSlot2Activity0 : GuestStubActivity()
class GuestSlot2Activity1 : GuestStubActivity()
class GuestSlot2Activity2 : GuestStubActivity()
class GuestSlot2Activity3 : GuestStubActivity()
class GuestSlot2Activity4 : GuestStubActivity()
class GuestSlot2Activity5 : GuestStubActivity()
class GuestSlot2Activity6 : GuestStubActivity()
class GuestSlot2Activity7 : GuestStubActivity()
class GuestSlot3Activity0 : GuestStubActivity()
class GuestSlot3Activity1 : GuestStubActivity()
class GuestSlot3Activity2 : GuestStubActivity()
class GuestSlot3Activity3 : GuestStubActivity()
class GuestSlot3Activity4 : GuestStubActivity()
class GuestSlot3Activity5 : GuestStubActivity()
class GuestSlot3Activity6 : GuestStubActivity()
class GuestSlot3Activity7 : GuestStubActivity()
