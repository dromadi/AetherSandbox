package com.aether.sandbox.core;

import android.content.*;
import android.content.pm.*;
import android.os.*;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.lsposed.hiddenapibypass.HiddenApiBypass;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Per-runtime self-package identity and in-process ContentResolver routing. Not UID isolation. */
public final class GuestFrameworkBridge implements AutoCloseable {
    private final PackageInfo snapshot;
    private final GuestIntentRegistry intents;
    private final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
    private final Map<String, Object> transports = new ConcurrentHashMap<>();
    private final Set<Object> local = Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile boolean closed;
    private GuestFrameworkBridge(PackageInfo info, GuestIntentRegistry intents) { snapshot = info; this.intents = intents; }

    public static GuestFrameworkBridge install(Context context, PackageInfo info, GuestIntentRegistry intents) {
        GuestFrameworkBridge bridge = new GuestFrameworkBridge(info, intents);
        try {
            if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/app/ApplicationPackageManager;", "Landroid/app/ContextImpl$ApplicationContentResolver;",
                "Landroid/content/ContentProvider;", "Landroid/content/IContentProvider;");
            Class<?> pm = context.getPackageManager().getClass();
            Set<String> names = new HashSet<>(Arrays.asList("getPackageInfo", "getPackageInfoAsUser", "getApplicationInfo", "getApplicationInfoAsUser",
                "getActivityInfo", "getServiceInfo", "getReceiverInfo", "getProviderInfo", "resolveContentProvider", "getPackageUid", "getPackageUidAsUser",
                "getInstallerPackageName", "getLaunchIntentForPackage", "getComponentEnabledSetting", "setComponentEnabledSetting", "resolveActivity", "queryIntentActivities"));
            for (Method method : pm.getDeclaredMethods()) if (names.contains(method.getName())) bridge.hook(method, false);
            Class<?> resolver = context.getContentResolver().getClass();
            for (Method method : resolver.getDeclaredMethods()) if (Arrays.asList("acquireProvider", "acquireUnstableProvider", "acquireExistingProvider",
                "releaseProvider", "releaseUnstableProvider", "unstableProviderDied").contains(method.getName())) bridge.hook(method, true);
            return bridge;
        } catch (Throwable error) { bridge.close(); throw new IllegalStateException("Framework guest gagal disiapkan", error); }
    }
    private void hook(Method method, boolean resolver) {
        hooks.add(XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (closed) return;
                try { if (resolver) resolveProvider(param); else packageCall(param); }
                catch (Throwable error) { param.setThrowable(error); }
            }
        }));
    }
    private void resolveProvider(XC_MethodHook.MethodHookParam param) {
        String name = param.method.getName();
        if (name.startsWith("acquire")) {
            for (Object arg : param.args) if (arg instanceof String) {
                Object provider = transports.get(arg);
                if (provider != null) { param.setResult(provider); return; }
            }
        } else if (param.args.length > 0) {
            synchronized (local) { if (local.contains(param.args[0])) param.setResult(name.equals("unstableProviderDied") ? null : Boolean.TRUE); }
        }
    }
    /** Publish to this process only, before attachInfo invokes Provider.onCreate. */
    public void publish(ContentProvider provider, ProviderInfo info) throws Exception {
        Method method = ContentProvider.class.getDeclaredMethod("getIContentProvider"); method.setAccessible(true);
        Object transport = method.invoke(provider);
        synchronized (local) { local.add(transport); }
        for (String authority : info.authority.split(";")) {
            if (transports.putIfAbsent(authority, transport) != null) throw new IllegalStateException("Authority Provider duplikat: " + authority);
        }
    }
    private boolean own(Object name) { return snapshot.packageName.equals(name); }
    private PackageInfo copy() {
        Parcel p = Parcel.obtain();
        try { snapshot.writeToParcel(p, 0); p.setDataPosition(0); return PackageInfo.CREATOR.createFromParcel(p); }
        finally { p.recycle(); }
    }
    private void packageCall(XC_MethodHook.MethodHookParam p) throws Exception {
        if (p.args.length == 0) return;
        String name = p.method.getName(); Object first = p.args[0];
        if (first instanceof Intent && (name.equals("resolveActivity") || name.equals("queryIntentActivities"))) {
            Intent intent = (Intent) first;
            List<ResolveInfo> matches = intents.query(intent, true);
            if (!matches.isEmpty() || own(intent.getPackage()) || intent.getComponent() != null && own(intent.getComponent().getPackageName()))
                p.setResult(name.equals("resolveActivity") ? matches.stream().findFirst().orElse(null) : matches);
            return;
        }
        if (first instanceof ComponentName && own(((ComponentName) first).getPackageName())) {
            ComponentName component = (ComponentName) first;
            if (name.equals("getComponentEnabledSetting")) { p.setResult(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT); return; }
            if (name.equals("setComponentEnabledSetting")) throw new UnsupportedOperationException("Perubahan komponen guest belum didukung");
            PackageInfo info = copy();
            ComponentInfo[] components = name.equals("getActivityInfo") ? info.activities : name.equals("getServiceInfo") ? info.services : name.equals("getReceiverInfo") ? info.receivers : info.providers;
            if (components != null) for (ComponentInfo candidate : components) if (candidate.name.equals(component.getClassName())) { p.setResult(candidate); return; }
            throw new PackageManager.NameNotFoundException(component.flattenToShortString());
        }
        if (name.equals("resolveContentProvider") && first instanceof String) {
            if (snapshot.providers != null) for (ProviderInfo provider : copy().providers)
                if (provider.authority != null && Arrays.asList(provider.authority.split(";")).contains(first)) { p.setResult(provider); return; }
            return;
        }
        if (!own(first)) return;
        switch (name) {
            case "getPackageInfo": case "getPackageInfoAsUser": p.setResult(copy()); break;
            case "getApplicationInfo": case "getApplicationInfoAsUser": p.setResult(new ApplicationInfo(snapshot.applicationInfo)); break;
            case "getPackageUid": case "getPackageUidAsUser": p.setResult(android.os.Process.myUid()); break;
            case "getInstallerPackageName": p.setResult(null); break;
            case "getLaunchIntentForPackage":
                Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(snapshot.packageName);
                ActivityInfo activity = intents.resolve(launcher, false);
                p.setResult(activity == null ? null : launcher.setComponent(new ComponentName(snapshot.packageName, activity.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); break;
        }
    }
    @Override public void close() {
        closed = true;
        for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
        hooks.clear(); transports.clear(); synchronized (local) { local.clear(); }
    }
}
