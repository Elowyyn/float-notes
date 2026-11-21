package com.elowyyn.floatnotes;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 唯一数据源：SharedPreferences 里的 JSON 数组。Activity 与 Service 共用。 */
public class Store {

    private static final String PREF = "float_notes_v1";
    private static final String KEY = "windows";

    public static synchronized List<WindowItem> load(Context c) {
        ArrayList<WindowItem> out = new ArrayList<>();
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY, "");
            if (raw == null || raw.isEmpty()) return out;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                WindowItem w = WindowItem.fromJson(o);
                if (w.id != null && !w.id.isEmpty()) out.add(w);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static synchronized void save(Context c, List<WindowItem> list) {
        JSONArray arr = new JSONArray();
        for (WindowItem w : list) arr.put(w.toJson());
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply();
    }

    public static File imageDir(Context c) {
        File d = new File(c.getFilesDir(), "overlays");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static void deleteImage(String path) {
        if (path == null || path.isEmpty()) return;
        try {
            File f = new File(path);
            if (f.exists()) f.delete();
        } catch (Exception ignored) {}
    }

    public static String newId() {
        return "w" + Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString((int) (Math.random() * 1296), 36);
    }
}
