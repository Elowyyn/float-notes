package com.elowyyn.floatnotes;

import org.json.JSONObject;

import java.util.Locale;

/** 单个浮窗的数据模型。所有字段均会持久化。 */
public class WindowItem {

    public static final int TYPE_TEXT = 0;
    public static final int TYPE_IMAGE = 1;

    public static final float MIN_SCALE = 0.1f;
    public static final float MAX_SCALE = 8f;

    public String id = "";
    public int type = TYPE_TEXT;
    public String text = "";
    public String imagePath = "";
    public int x = 24;
    public int y = 200;
    public int opacity = 100;
    public boolean locked = false;
    public boolean hidden = false;
    /** 双指缩放倍率：同时影响卡片宽度与字号，1.0 为默认 */
    public float scale = 1f;

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("type", type);
            o.put("text", text == null ? "" : text);
            o.put("imagePath", imagePath == null ? "" : imagePath);
            o.put("x", x);
            o.put("y", y);
            o.put("opacity", opacity);
            o.put("locked", locked);
            o.put("hidden", hidden);
            o.put("scale", scale);
        } catch (Exception ignored) {}
        return o;
    }

    public static WindowItem fromJson(JSONObject o) {
        WindowItem w = new WindowItem();
        w.id = o.optString("id", "");
        w.type = o.optInt("type", TYPE_TEXT);
        w.text = o.optString("text", "");
        w.imagePath = o.optString("imagePath", "");
        w.x = o.optInt("x", 24);
        w.y = o.optInt("y", 200);
        w.opacity = Math.max(10, Math.min(100, o.optInt("opacity", 100)));
        w.locked = o.optBoolean("locked", false);
        w.hidden = o.optBoolean("hidden", false);
        w.scale = (float) Math.max(MIN_SCALE, Math.min(MAX_SCALE, o.optDouble("scale", 1.0)));
        return w;
    }

    public String title() {
        if (type == TYPE_IMAGE) return "图片浮窗";
        String t = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) return "文字浮窗";
        return t.length() > 20 ? t.substring(0, 20) + "…" : t;
    }

    public String stateLabel() {
        StringBuilder sb = new StringBuilder();
        sb.append(hidden ? "已隐藏" : "显示中")
                .append(" · ").append(locked ? "已锁定" : "可拖动")
                .append(" · ").append(opacity).append("%");
        if (Math.abs(scale - 1f) > 0.02f) {
            sb.append(" · ").append(String.format(Locale.US, "%.1f×", scale));
        }
        return sb.toString();
    }
}
