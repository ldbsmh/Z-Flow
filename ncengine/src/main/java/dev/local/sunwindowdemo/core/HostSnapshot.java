package dev.local.sunwindowdemo.core;

import android.content.Context;
import android.os.Bundle;
import android.util.AtomicFile;
import dev.local.sunwindowdemo.BuildConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 宿主进程（system_server、SystemUI）保存“最近一次成功读取”的模块配置。
 * 模块进程被杀或被拦截、Provider 暂时不可达时继续沿用该快照；快照写入宿主自己的设备保护存储，
 * 宿主重启后也能读回。保存的是完整配置（含 enabled），读到新配置即整体覆盖，用户关闭的功能不会被缓存保持开启。
 * 不放宽 Provider 的导出范围和 UID 校验：快照只由宿主自己写、自己读。
 */
public final class HostSnapshot {
    public interface Reader { Bundle read() throws Exception; }

    /**
     * config 为 null 表示既没读到配置也没有可用快照，调用方保持原有的关闭回退。
     * live 表示本次直接读自 Provider；report 表示可达状态或失败原因发生变化，调用方只在此时记录日志。
     */
    public record Result(Bundle config, boolean live, boolean report, String summary) {}

    static final int FORMAT = 1;
    static final long MAX_RETRY_MS = 60_000;
    private static final Map<String, HostSnapshot> SHARED = new HashMap<>();
    private final AtomicFile file;
    private final String feature;
    private final Set<String> transientKeys;
    private final Object reading = new Object();
    private Bundle remembered;
    private boolean loaded;
    private String persisted;
    private long savedAt;
    private String failure;
    private int failures;
    private String storageFailure;

    HostSnapshot(File directory, String feature, String... transientKeys) {
        if (!feature.matches("[a-z_]+")) throw new IllegalArgumentException("feature");
        this.feature = feature;
        this.file = new AtomicFile(new File(directory, feature + ".json"));
        this.transientKeys = Set.of(transientKeys);
    }

    /** 同一宿主进程内同一功能共用一个实例，多个读取方（例如横屏双栏、状态栏偏移、信号显示）共享快照与日志状态。 */
    public static HostSnapshot open(Context context, String feature, String... transientKeys) {
        File directory = directory(context);
        String key = new File(directory, feature + ".json").getPath();
        synchronized (SHARED) {
            HostSnapshot existing = SHARED.get(key);
            if (existing == null) { existing = new HostSnapshot(directory, feature, transientKeys); SHARED.put(key, existing); }
            return existing;
        }
    }

    /** system_server 使用 /data/system 下的独立目录（设备保护存储）；其他宿主使用自身的设备保护 files 目录。 */
    public static File directory(Context context) {
        if (android.os.Process.myUid() == android.os.Process.SYSTEM_UID)
            return new File(android.os.Environment.getDataDirectory(), "system/"+ModuleIdentity.CURRENT.hostStorageName());
        return new File(context.createDeviceProtectedStorageContext().getFilesDir(), ModuleIdentity.CURRENT.hostStorageName());
    }

    /** reader 在调用线程上执行且不持有本对象的锁；调用方负责在 Binder 安全的工作线程上调用。 */
    public Result read(Reader reader) {
        synchronized (reading) { return readOrdered(reader); }
    }

    private Result readOrdered(Reader reader) {
        Bundle value = null;
        Exception error = null;
        try {
            value = reader.read();
            if (value == null) throw new IllegalStateException("empty config");
        } catch (Exception failed) { error = failed; }
        synchronized (this) {
            if (error == null) {
                boolean recovered = failure != null;
                failure = null;
                failures = 0;
                store(value);
                return new Result(value.deepCopy(), true, recovered, recovered ? feature + " 配置已恢复读取" : "");
            }
            String reason = reason(error);
            boolean changed = !reason.equals(failure);
            failure = reason;
            failures++;
            Bundle cached = cached();
            String summary = feature + " 配置读取失败（" + reason + "），"
                    + (cached == null ? "无可用快照" : "沿用最近快照 savedAt=" + savedAt);
            return new Result(cached, false, changed, summary);
        }
    }

    /** 最近一次读取是否成功；首次读取前视为可达。 */
    public synchronized boolean reachable() { return failure == null; }

    /** 连续失败时按 base、2×base… 退避，最长 60 秒；成功后恢复 base。 */
    public synchronized long retryDelay(long base) {
        if (failures <= 1) return base;
        return Math.min(MAX_RETRY_MS, base << Math.min(failures - 1, 6));
    }

    /** 只读当前快照，不访问 Provider。 */
    public synchronized Bundle cached() {
        if (!loaded) {
            loaded = true;
            if (remembered == null) remembered = readFile();
        }
        return remembered == null ? null : remembered.deepCopy();
    }

    private void store(Bundle value) {
        remembered = value.deepCopy();
        loaded = true;
        try {
            JSONObject values = encode(value, transientKeys);
            String text = values.toString();
            if (text.equals(persisted)) return;
            long now = System.currentTimeMillis();
            JSONObject root = new JSONObject().put("format", FORMAT).put("feature", feature)
                    .put("version", BuildConfig.MODULE_VERSION_NAME).put("versionCode", BuildConfig.MODULE_VERSION_CODE)
                    .put("savedAt", now).put("values", values);
            File directory = file.getBaseFile().getParentFile();
            if (directory != null && !directory.isDirectory()) {
                if (!directory.mkdirs() && !directory.isDirectory()) throw new IllegalStateException("snapshot directory unavailable");
                ownerOnly(directory, true);
            }
            FileOutputStream out = null;
            try {
                out = file.startWrite();
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                file.finishWrite(out);
                ownerOnly(file.getBaseFile(), false);
                persisted = text;
                savedAt = now;
                storageFailure = null;
            } catch (IOException failed) {
                if (out != null) file.failWrite(out);
                reportStorage(failed);
            }
        } catch (org.json.JSONException | RuntimeException failed) {
            reportStorage(failed);
        }
    }

    private void reportStorage(Exception error) {
        String reason = reason(error);
        if (!reason.equals(storageFailure)) android.util.Log.w("NcHostSnapshot", feature + " snapshot persistence failed: " + reason);
        storageFailure = reason;
    }

    private Bundle readFile() {
        try {
            JSONObject root = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            if (root.optInt("format") != FORMAT || !feature.equals(root.optString("feature"))) return null;
            JSONObject values = root.getJSONObject("values");
            Bundle result = decode(values);
            persisted = values.toString();
            savedAt = root.optLong("savedAt");
            return result;
        } catch (IOException | org.json.JSONException | RuntimeException corrupt) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    static JSONObject encode(Bundle value, Set<String> skipped) throws org.json.JSONException {
        JSONObject out = new JSONObject();
        for (String key : new TreeSet<>(value.keySet())) {
            if (skipped.contains(key)) continue;
            Object item = value.get(key);
            JSONArray entry = new JSONArray();
            if (item instanceof Boolean) entry.put("b").put(item);
            else if (item instanceof Integer) entry.put("i").put(item);
            else if (item instanceof Long) entry.put("l").put(item);
            else if (item instanceof String) entry.put("s").put(item);
            else if (item instanceof String[] strings) { JSONArray array = new JSONArray(); for (String s : strings) array.put(s == null ? JSONObject.NULL : s); entry.put("S").put(array); }
            else if (item instanceof int[] ints) { JSONArray array = new JSONArray(); for (int n : ints) array.put(n); entry.put("I").put(array); }
            else if (item instanceof long[] longs) { JSONArray array = new JSONArray(); for (long n : longs) array.put(n); entry.put("L").put(array); }
            else if (item instanceof ArrayList<?> list && list.stream().allMatch(e -> e instanceof String)) {
                JSONArray array = new JSONArray(); for (Object s : list) array.put(s); entry.put("A").put(array);
            } else continue;
            out.put(key, entry);
        }
        return out;
    }

    static Bundle decode(JSONObject values) throws org.json.JSONException {
        Bundle out = new Bundle();
        for (var keys = values.keys(); keys.hasNext();) {
            String key = keys.next();
            JSONArray entry = values.getJSONArray(key);
            String type = entry.getString(0);
            switch (type) {
                case "b" -> out.putBoolean(key, entry.getBoolean(1));
                case "i" -> out.putInt(key, entry.getInt(1));
                case "l" -> out.putLong(key, entry.getLong(1));
                case "s" -> out.putString(key, entry.getString(1));
                case "S" -> { JSONArray a = entry.getJSONArray(1); String[] r = new String[a.length()]; for (int n = 0; n < r.length; n++) r[n] = a.isNull(n) ? null : a.getString(n); out.putStringArray(key, r); }
                case "I" -> { JSONArray a = entry.getJSONArray(1); int[] r = new int[a.length()]; for (int n = 0; n < r.length; n++) r[n] = a.getInt(n); out.putIntArray(key, r); }
                case "L" -> { JSONArray a = entry.getJSONArray(1); long[] r = new long[a.length()]; for (int n = 0; n < r.length; n++) r[n] = a.getLong(n); out.putLongArray(key, r); }
                case "A" -> { JSONArray a = entry.getJSONArray(1); ArrayList<String> r = new ArrayList<>(); for (int n = 0; n < a.length(); n++) r.add(a.getString(n)); out.putStringArrayList(key, r); }
                default -> { }
            }
        }
        return out;
    }

    private static void ownerOnly(File target, boolean directory) {
        target.setReadable(false, false); target.setReadable(true, true);
        target.setWritable(false, false); target.setWritable(true, true);
        target.setExecutable(false, false);
        if (directory) target.setExecutable(true, true);
    }

    static String reason(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        String message = String.valueOf(error.getMessage());
        if (message.length() > 120) message = message.substring(0, 120);
        return error.getClass().getSimpleName() + ": " + message;
    }
}
