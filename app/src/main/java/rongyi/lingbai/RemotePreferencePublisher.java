package rongyi.lingbai;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** Publishes private local settings to framework-owned API 102 Remote Preferences. */
final class RemotePreferencePublisher {
    private static final String TAG = "LiquidGlassPrefs";
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final Set<XposedService> SERVICES = ConcurrentHashMap.newKeySet();
    private static final Map<String, Binding> BINDINGS = new ConcurrentHashMap<>();
    private static final Set<String> MIGRATED_GROUPS = ConcurrentHashMap.newKeySet();
    private static volatile Context applicationContext;

    private RemotePreferencePublisher() {}

    static void start(Context context) {
        Context app = context.getApplicationContext();
        applicationContext = app != null ? app : context;
        if (!STARTED.compareAndSet(false, true)) return;

        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                try {
                    int apiVersion = service.getApiVersion();
                    String frameworkName = service.getFrameworkName();
                    long properties = service.getFrameworkProperties();
                    ModuleLog.i(TAG, "API " + apiVersion + " service bound: " + frameworkName
                            + ", remote="
                            + ((properties & XposedService.PROP_CAP_REMOTE) != 0L));
                    if (apiVersion < XposedService.API_102) {
                        ModuleLog.w(TAG, "Ignoring Xposed service below API 102: " + apiVersion);
                        return;
                    }
                    if ((properties & XposedService.PROP_CAP_REMOTE) == 0L) {
                        ModuleLog.w(TAG, "Ignoring Xposed service without Remote Preferences: "
                                + frameworkName);
                        return;
                    }
                    SERVICES.add(service);
                    for (Binding binding : BINDINGS.values()) {
                        publish(service, binding.group, binding.local);
                    }
                    ModuleLog.i(TAG, "Published " + BINDINGS.size()
                            + " preference groups to " + frameworkName);
                    Context context = applicationContext;
                    if (context != null) GlassSettings.notifyChanged(context);
                } catch (Throwable error) {
                    ModuleLog.w(TAG, "Ignoring unavailable Xposed service", error);
                }
            }

            @Override
            public void onServiceDied(XposedService service) {
                SERVICES.remove(service);
                ModuleLog.w(TAG, "API 102 service disconnected");
            }
        });
    }

    static SharedPreferences open(Context context, String group) {
        start(context);
        Context app = applicationContext != null ? applicationContext : context;
        Context device = deviceProtected(app);
        migrateCredentialCopyOnce(app, device, group);

        SharedPreferences local = device.getSharedPreferences(group, Context.MODE_PRIVATE);
        Binding created = new Binding(group, local);
        Binding binding = BINDINGS.putIfAbsent(group, created);
        if (binding == null) {
            binding = created;
            local.registerOnSharedPreferenceChangeListener(binding.listener);
            publishToAll(binding);
        }
        return binding.local;
    }

    private static Context deviceProtected(Context context) {
        try {
            Context device = context.createDeviceProtectedStorageContext();
            return device != null ? device : context;
        } catch (Throwable ignored) {
            return context;
        }
    }

    private static void migrateCredentialCopyOnce(Context credential, Context device, String group) {
        if (!MIGRATED_GROUPS.add(group)) return;
        try {
            if (credential.isDeviceProtectedStorage()) return;
            SharedPreferences source = credential.getSharedPreferences(group, Context.MODE_PRIVATE);
            SharedPreferences target = device.getSharedPreferences(group, Context.MODE_PRIVATE);
            Map<String, ?> sourceMap = source.getAll();
            Map<String, ?> targetMap = target.getAll();
            if (sourceMap.isEmpty()) return;

            long sourceRevision = revisionScore(sourceMap);
            long targetRevision = revisionScore(targetMap);
            if (targetMap.isEmpty() || sourceRevision > targetRevision) {
                replaceAll(target, sourceMap);
                return;
            }

            Map<String, Object> merged = new HashMap<>();
            for (Map.Entry<String, ?> entry : sourceMap.entrySet()) {
                if (!targetMap.containsKey(entry.getKey())) {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
            if (!merged.isEmpty()) putAll(target.edit(), merged).commit();
        } catch (Throwable error) {
            ModuleLog.w(TAG, "Preference migration failed for " + group, error);
        }
    }

    private static long revisionScore(Map<String, ?> values) {
        long best = 0L;
        for (String key : new String[]{"revision", "render_revision", "published_at"}) {
            Object value = values.get(key);
            if (value instanceof Number) best = Math.max(best, ((Number) value).longValue());
        }
        return best;
    }

    private static void publishToAll(Binding binding) {
        for (XposedService service : SERVICES) {
            try {
                publish(service, binding.group, binding.local);
            } catch (Throwable error) {
                ModuleLog.w(TAG, "Remote preference publish failed for " + binding.group, error);
            }
        }
    }

    private static void publish(XposedService service, String group, SharedPreferences local) {
        replaceAll(service.getRemotePreferences(group), local.getAll());
    }

    private static void replaceAll(SharedPreferences target, Map<String, ?> values) {
        SharedPreferences.Editor editor = target.edit().clear();
        putAll(editor, values).commit();
    }

    private static SharedPreferences.Editor putAll(
            SharedPreferences.Editor editor,
            Map<String, ?> values
    ) {
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Set<?>) {
                Set<String> strings = new HashSet<>();
                for (Object item : (Set<?>) value) {
                    if (item instanceof String) strings.add((String) item);
                }
                editor.putStringSet(key, strings);
            }
        }
        return editor;
    }

    private static final class Binding {
        final String group;
        final SharedPreferences local;
        final SharedPreferences.OnSharedPreferenceChangeListener listener;

        Binding(String group, SharedPreferences local) {
            this.group = group;
            this.local = local;
            this.listener = (preferences, changedKey) -> publishToAll(this);
        }
    }
}
