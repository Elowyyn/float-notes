package com.elowyyn.floatnotes;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前台服务：用 WindowManager 把每个浮窗挂到系统窗口层（可悬浮在任何 App 之上）。
 * 每个浮窗是一张完整卡片：顶部控制栏（显隐 / 锁定 / 关闭 + 透明度滑块），
 * 一条分隔线，下面直接接内容（文字或图片）。双指捏合可整体缩放。
 */
public class OverlayService extends Service {

    public static OverlayService INSTANCE;
    /** 主界面是否在前台：在前台时把浮窗隐藏，避免遮住列表操作区 */
    private static boolean activityForeground = false;

    private static final String CHANNEL_ID = "float_notes_channel";
    private static final int NOTI_ID = 1001;
    private static final String ACTION_STOP_ALL = "com.elowyyn.floatnotes.STOP_ALL";

    private static final float MIN_SCALE = WindowItem.MIN_SCALE;
    private static final float MAX_SCALE = WindowItem.MAX_SCALE;
    /** 卡片最小宽度：五个按钮 + 左右留白 */
    private static final float MIN_CARD_W_DP = 178f;
    /** 卡片基准最大宽度（放大时按比例放宽） */
    private static final float MAX_CARD_W_DP = 320f;
    /** 透明度子面板固定宽度（不随卡片宽度变化） */
    private static final float OP_PANEL_W_DP = 244f;

    /**
     * 按内容算卡片宽度：文字量文本宽度，图片按原始宽高比。
     * 结果 × scale，并夹在 [最小宽, 最大宽] 之间。
     */
    /** 内容最大高度：放大时按比例放开，夹到「屏高 - 顶部控件」。 */
    private int computeMaxBodyH(float scale) {
        int base = (int) (Ui.screenH(this) * 0.46f);
        if (scale <= 1f) return base;
        int max = Ui.screenH(this) - Ui.dp(this, 160);
        return (int) Math.min(max, base * scale);
    }

    private int computeCardWidth(WindowItem item) {
        float s = item.scale <= 0 ? 1f : item.scale;
        int want;
        if (item.type == WindowItem.TYPE_IMAGE) {
            int maxH = (int) (Ui.screenH(this) * 0.46f);
            want = Math.round(maxH * imageRatio(item.imagePath) * s);
        } else {
            want = measureTextWidth(item.text, s);
        }
        int min = s > 1f
                ? Math.round(Ui.dp(this, MIN_CARD_W_DP) * s)
                : Ui.dp(this, MIN_CARD_W_DP);
        int max = Math.min(Math.round(Ui.dp(this, MAX_CARD_W_DP) * Math.max(1f, s)),
                Ui.screenW(this) - Ui.dp(this, 20));
        return Math.max(min, Math.min(want, Math.max(min, max)));
    }

    private int measureTextWidth(String text, float s) {
        if (text == null || text.isEmpty()) return 0;
        android.text.TextPaint p = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(Ui.dp(this, 15) * s);
        float max = 0f;
        String[] lines = text.split("\n");
        for (String line : lines) {
            max = Math.max(max, p.measureText(line));
        }
        int padH = Math.round(Ui.dp(this, 15) * Math.max(0.75f, s)) * 2;
        return Math.round(max) + padH + Ui.dp(this, 6);
    }

    private float imageRatio(String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth > 0 && o.outHeight > 0) {
                return (float) o.outWidth / (float) o.outHeight;
            }
        } catch (Throwable ignored) {}
        return 1.5f;
    }

    private WindowManager wm;
    private final Map<String, Holder> holders = new LinkedHashMap<>();
    private List<WindowItem> items = new ArrayList<>();

    /** 一个浮窗的视图引用集合。 */
    private static class Holder {
        WindowItem item;
        WindowRoot root;
        LinearLayout card;
        LinearLayout row;
        LinearLayout bodyBox;
        TextView textView;
        ImageView imageView;
        MaxScroll textScroll;
        MaxImageView maxImage;
        ImageView vis;
        ImageView lock;
        SeekBar seek;
        TextView pct;
        View opPanel;
        WindowManager.LayoutParams opLp;
        WindowManager.LayoutParams lp;
        String renderedText = "";
        String renderedPath = "";
    }

    /* ================= 对外入口 ================= */

    public static void start(Context c) {
        Intent i = new Intent(c, OverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, OverlayService.class));
    }

    public static void refresh(Context c) {
        OverlayService s = INSTANCE;
        if (s != null) s.sync();
        else if (Settings.canDrawOverlays(c)) {
            List<WindowItem> list = Store.load(c);
            if (!list.isEmpty()) start(c);
        }
    }

    public static void onActivityForeground(boolean fg) {
        activityForeground = fg;
        OverlayService s = INSTANCE;
        if (s != null) s.applyForegroundState();
    }

    private void applyForegroundState() {
        for (Holder h : holders.values()) {
            if (h.root != null) {
                h.root.setVisibility(activityForeground ? View.INVISIBLE : View.VISIBLE);
            }
            if (activityForeground) hideOpacityPanel(h);
        }
    }

    /* ================= 生命周期 ================= */

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        ensureChannel();
        startForeground(NOTI_ID, buildNotification(0));
        sync();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_ALL.equals(intent.getAction())) {
            stopAll();
            return START_NOT_STICKY;
        }
        sync();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        for (String id : new ArrayList<>(holders.keySet())) removeWindow(id);
        INSTANCE = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* ================= 与 Store 同步 ================= */

    void sync() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        List<WindowItem> latest = Store.load(this);
        items = latest;

        List<String> alive = new ArrayList<>();
        for (WindowItem w : latest) alive.add(w.id);

        List<String> dead = new ArrayList<>();
        for (String id : holders.keySet()) if (!alive.contains(id)) dead.add(id);
        for (String id : dead) removeWindow(id);

        for (WindowItem w : latest) {
            if (!holders.containsKey(w.id)) addWindow(w);
        }
        for (WindowItem w : latest) {
            Holder h = holders.get(w.id);
            if (h == null) continue;
            if (!eq(h.renderedText, w.text) || !eq(h.renderedPath, w.imagePath)
                    || Math.abs(h.item.scale - w.scale) > 0.001f) {
                removeWindow(w.id);
                addWindow(w);
                continue;
            }
            h.item = w;
            applyState(h);
        }
        updateNotification(latest.size());
        if (latest.isEmpty()) stopSelf();
    }

    private void stopAll() {
        for (WindowItem w : Store.load(this)) Store.deleteImage(w.imagePath);
        Store.save(this, new ArrayList<WindowItem>());
        for (String id : new ArrayList<>(holders.keySet())) removeWindow(id);
        MainActivity.onStoreChanged();
        stopSelf();
    }

    /* ================= 构建浮窗（单一卡片） ================= */

    private void addWindow(WindowItem item) {
        final Holder h = new Holder();
        h.item = item;
        if (item.scale <= 0) item.scale = 1f;

        int cardW = computeCardWidth(item);
        int maxBodyH = computeMaxBodyH(item.scale);

        WindowRoot root = new WindowRoot(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));

        /* ---------- 唯一卡片 ---------- */
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipToOutline(true);
        card.setPadding(0, 0, 0, 0);

        /* --- 控制行：五个按钮（透明度滑块挪到独立子窗口，固定宽度） --- */
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), 0);

        ImageView vis = Ui.iconButton(this, R.drawable.ic_eye, 32, 18, color(R.color.on_surface_variant));
        ImageView lock = Ui.iconButton(this, R.drawable.ic_unlock, 32, 18, color(R.color.on_surface_variant));
        ImageView fit = Ui.iconButton(this, R.drawable.ic_fit, 32, 18, color(R.color.on_surface_variant));
        ImageView opa = Ui.iconButton(this, R.drawable.ic_opacity, 32, 18, color(R.color.on_surface_variant));
        ImageView close = Ui.iconButton(this, R.drawable.ic_close, 32, 18, color(R.color.on_surface_variant));
        row.addView(vis);
        row.addView(lock);
        row.addView(fit);
        row.addView(opa);
        row.addView(close);

        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* --- 拖动条：看着是一条分隔把手，实际是一整条可拖区域 --- */
        FrameLayout dragBar = new FrameLayout(this);
        View handle = new View(this);
        handle.setBackground(Ui.round(this, Ui.alpha(color(R.color.outline_variant), 0.95f), 999));
        dragBar.addView(handle, new FrameLayout.LayoutParams(
                Ui.dp(this, 44), Math.max(2, Ui.dp(this, 3)), Gravity.CENTER));
        card.addView(dragBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 18)));

        /* --- 分隔线 + 内容（同一个 bodyBox，便于整体隐藏/调透明） --- */
        LinearLayout bodyBox = new LinearLayout(this);
        bodyBox.setOrientation(LinearLayout.VERTICAL);

        TextView tv = null;
        ImageView iv = null;
        if (item.type == WindowItem.TYPE_TEXT) {
            MaxScroll sv = new MaxScroll(this);
            sv.maxHeight = maxBodyH;
            h.textScroll = sv;
            sv.setVerticalScrollBarEnabled(false);

            tv = new TextView(this);
            tv.setText(item.text);
            tv.setTextSize(15 * item.scale);
            tv.setTextColor(color(R.color.on_surface));
            tv.setLineSpacing(Ui.dp(this, 4), 1f);
            int padH = Math.round(Ui.dp(this, 15) * Math.max(0.75f, item.scale));
            int padV = Math.round(Ui.dp(this, 13) * Math.max(0.75f, item.scale));
            tv.setPadding(padH, padV, padH, padV);
            sv.addView(tv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            bodyBox.addView(sv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            MaxImageView miv = new MaxImageView(this);
            miv.maxHeight = maxBodyH;
            h.maxImage = miv;
            miv.setAdjustViewBounds(true);
            miv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Bitmap bm = decodeBitmap(item.imagePath, Math.max(Ui.dp(this, 240), cardW) * 2);
            if (bm != null) miv.setImageBitmap(bm);
            bodyBox.addView(miv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            iv = miv;
        }

        card.addView(bodyBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(card, new LinearLayout.LayoutParams(
                cardW, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* ---------- 窗口参数 ---------- */
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                cardW + Ui.dp(this, 12),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int winW = cardW + Ui.dp(this, 12);
        lp.x = Math.max(0, Math.min(item.x, Math.max(0, Ui.screenW(this) - winW)));
        lp.y = Math.max(0, Math.min(item.y, Math.max(0, Ui.screenH(this) - Ui.dp(this, 120))));

        h.root = root;
        h.card = card;
        h.row = row;
        h.bodyBox = bodyBox;
        h.textView = tv;
        h.imageView = iv;
        h.vis = vis;
        h.lock = lock;
        h.lp = lp;

        root.bind(h);
        attachDrag(h);
        attachActions(h, fit, opa, close);

        try {
            wm.addView(root, lp);
        } catch (Exception e) {
            Toast.makeText(this, "浮窗添加失败，请检查悬浮窗权限", Toast.LENGTH_SHORT).show();
            stopSelf();
            return;
        }
        h.renderedText = item.text == null ? "" : item.text;
        h.renderedPath = item.imagePath == null ? "" : item.imagePath;
        holders.put(item.id, h);
        applyState(h);
        if (activityForeground) root.setVisibility(View.INVISIBLE);
        root.post(new Runnable() {
            @Override
            public void run() { keepInsideScreen(h); }
        });
    }

    /** 卡片背景：底色透明度跟随滑块，控制栏始终保持清晰。 */
    private GradientDrawable buildCardBg(WindowItem item) {
        float a = Math.max(0.12f, item.opacity / 100f);
        int base = Ui.alpha(color(R.color.container_high), a);
        GradientDrawable d = Ui.round(this, base, 18);
        if (item.locked) {
            d.setStroke(Math.max(1, Ui.dp(this, 1.5f)), color(R.color.primary));
        }
        return d;
    }

    /** 拖动：只作用于控制行的空白区域；锁定后不响应。 */
    private void attachDrag(final Holder h) {
        h.card.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (h.item.locked) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = h.lp.x;
                        startY = h.lp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - downX);
                        int dy = (int) (e.getRawY() - downY);
                        if (!moved && Math.abs(dx) + Math.abs(dy) < 4) return true;
                        moved = true;
                        h.lp.x = startX + dx;
                        h.lp.y = startY + dy;
                        try {
                            wm.updateViewLayout(h.root, h.lp);
                        } catch (Exception ignored) {}
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) {
                            h.item.x = (int) h.root.getX();
                            h.item.y = (int) h.root.getY();
                            positionPanel(h);
                            Store.save(OverlayService.this, items);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void attachActions(final Holder h, ImageView fit, ImageView opa, ImageView close) {
        h.vis.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                h.item.hidden = !h.item.hidden;
                if (h.item.hidden) hideOpacityPanel(h);
                applyState(h);
                Store.save(OverlayService.this, items);
                MainActivity.onStoreChanged();
            }
        });

        h.lock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                h.item.locked = !h.item.locked;
                applyState(h);
                Store.save(OverlayService.this, items);
                MainActivity.onStoreChanged();
            }
        });

        opa.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleOpacityPanel(h);
            }
        });

        fit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyScale(h, 1f, true);
                Store.save(OverlayService.this, items);
                MainActivity.onStoreChanged();
            }
        });

        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Store.deleteImage(h.item.imagePath);
                items.remove(h.item);
                removeWindow(h.item.id);
                Store.save(OverlayService.this, items);
                MainActivity.onStoreChanged();
                if (items.isEmpty()) stopSelf();
            }
        });
    }

    /** 只刷新透明度相关（拖动滑块时高频调用，避免重设背景造成卡顿）。 */
    private void applyOpacity(Holder h) {
        float a = h.item.opacity / 100f;
        int base = Ui.alpha(color(R.color.container_high), Math.max(0.12f, a));
        GradientDrawable d = Ui.round(this, base, 18);
        if (h.item.locked) {
            d.setStroke(Math.max(1, Ui.dp(this, 1.5f)), color(R.color.primary));
        }
        h.card.setBackground(d);
        h.bodyBox.setAlpha(a);
    }

    private void applyState(Holder h) {
        WindowItem it = h.item;
        h.bodyBox.setVisibility(it.hidden ? View.GONE : View.VISIBLE);
        h.card.setBackground(buildCardBg(it));
        applyOpacity(h);

        if (it.hidden) {
            h.vis.setImageResource(R.drawable.ic_eye_off);
            h.vis.setColorFilter(color(R.color.on_primary_container));
            h.vis.setBackground(Ui.circle(this, color(R.color.primary_container)));
        } else {
            h.vis.setImageResource(R.drawable.ic_eye);
            h.vis.setColorFilter(color(R.color.on_surface_variant));
            h.vis.setBackground(Ui.ripple(this));
        }

        if (it.locked) {
            h.lock.setImageResource(R.drawable.ic_lock);
            h.lock.setColorFilter(color(R.color.on_primary_container));
            h.lock.setBackground(Ui.circle(this, color(R.color.primary_container)));
        } else {
            h.lock.setImageResource(R.drawable.ic_unlock);
            h.lock.setColorFilter(color(R.color.on_surface_variant));
            h.lock.setBackground(Ui.ripple(this));
        }

        int p = Math.max(0, Math.min(90, it.opacity - 10));
        if (h.seek != null && h.seek.getProgress() != p) h.seek.setProgress(p);
        if (h.pct != null) h.pct.setText(it.opacity + "%");
    }

    private void applyScale(Holder h, float s) {
        applyScale(h, s, false);
    }

    /** 双指缩放 / 重置：宽度按内容重算，字号同步，位置自动夹进屏幕。 */
    private void applyScale(Holder h, float s, boolean force) {
        s = Math.max(MIN_SCALE, Math.min(MAX_SCALE, s));
        if (!force && Math.abs(s - h.item.scale) < 0.004f) return;
        h.item.scale = s;

        if (h.textView != null) {
            h.textView.setTextSize(15 * s);
            int padH = Math.round(Ui.dp(this, 15) * Math.max(0.75f, s));
            int padV = Math.round(Ui.dp(this, 13) * Math.max(0.75f, s));
            h.textView.setPadding(padH, padV, padH, padV);
        }

        int maxBodyH = computeMaxBodyH(s);
        if (h.textScroll != null) h.textScroll.maxHeight = maxBodyH;
        if (h.maxImage != null) h.maxImage.maxHeight = maxBodyH;

        int cardW = computeCardWidth(h.item);
        h.lp.width = cardW + Ui.dp(this, 12);

        ViewGroup.LayoutParams clp = h.card.getLayoutParams();
        if (clp != null) {
            clp.width = cardW;
            h.card.setLayoutParams(clp);
        }

        h.lp.x = Math.max(0, Math.min(h.lp.x, Math.max(0, Ui.screenW(this) - h.lp.width)));
        h.lp.y = Math.max(0, Math.min(h.lp.y, Math.max(0, Ui.screenH(this) - Ui.dp(this, 96))));
        try {
            wm.updateViewLayout(h.root, h.lp);
        } catch (Exception ignored) {}

        // 宽度变了，如果卡片高度超出屏幕底部就往上挪
        h.root.post(new Runnable() {
            @Override
            public void run() {
                keepInsideScreen(h);
                positionPanel(h);
            }
        });
    }

    /** 内容越长卡片越高，超出屏幕就自动上移，保证整张卡片可见。 */
    private void keepInsideScreen(Holder h) {
        if (h.root.getHeight() <= 0) return;
        int maxY = Ui.screenH(this) - h.root.getHeight() - Ui.dp(this, 8);
        if (h.lp.y > maxY) {
            h.lp.y = Math.max(0, maxY);
            h.item.y = h.lp.y;
            try {
                wm.updateViewLayout(h.root, h.lp);
            } catch (Exception ignored) {}
        }
    }

    private void removeWindow(String id) {
        Holder h = holders.remove(id);
        if (h == null || h.root == null) return;
        hideOpacityPanel(h);
        try {
            wm.removeView(h.root);
        } catch (Exception ignored) {}
    }

    /* ================= 透明度子面板（固定宽度，独立小窗口） ================= */

    private void toggleOpacityPanel(Holder h) {
        if (h.opPanel != null && h.opPanel.getParent() != null) {
            hideOpacityPanel(h);
        } else {
            showOpacityPanel(h);
        }
    }

    private void showOpacityPanel(final Holder h) {
        if (h.opPanel == null) {
            h.opPanel = buildOpacityPanel(h);
            h.opLp = new WindowManager.LayoutParams(
                    Ui.dp(this, OP_PANEL_W_DP),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            h.opLp.gravity = Gravity.TOP | Gravity.START;
        }
        if (h.opPanel.getParent() == null) {
            h.opLp.x = h.lp.x + Ui.dp(this, 6);
            h.opLp.y = h.lp.y + Ui.dp(this, 120);
            try {
                wm.addView(h.opPanel, h.opLp);
            } catch (Exception ignored) {}
        }
        h.root.post(new Runnable() {
            @Override
            public void run() { positionPanel(h); }
        });
    }

    private void hideOpacityPanel(Holder h) {
        if (h.opPanel != null && h.opPanel.getParent() != null) {
            try {
                wm.removeView(h.opPanel);
            } catch (Exception ignored) {}
        }
    }

    /** 面板贴在卡片下方（下方放不下就翻到上方），左右夹进屏幕。 */
    private void positionPanel(Holder h) {
        if (h.opLp == null || h.opPanel == null || h.opPanel.getParent() == null) return;
        int pw = Ui.dp(this, OP_PANEL_W_DP);
        int ph = Math.max(h.opPanel.getHeight(), Ui.dp(this, 48));
        int sw = Ui.screenW(this), sh = Ui.screenH(this);
        int x = h.lp.x + Ui.dp(this, 6);
        int y = h.lp.y + h.root.getHeight() + Ui.dp(this, 6);
        if (y + ph > sh - Ui.dp(this, 8)) {
            y = h.lp.y - ph - Ui.dp(this, 6);
        }
        x = Math.max(0, Math.min(x, Math.max(0, sw - pw)));
        y = Math.max(0, Math.min(y, Math.max(0, sh - ph)));
        h.opLp.x = x;
        h.opLp.y = y;
        try {
            wm.updateViewLayout(h.opPanel, h.opLp);
        } catch (Exception ignored) {}
    }

    /** 面板内容：图标 + 滑块 + 百分比，宽度固定 244dp。 */
    private View buildOpacityPanel(final Holder h) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(Ui.roundStroke(this, color(R.color.container_high),
                Ui.alpha(color(R.color.outline_variant), 0.75f), 0.5f, 16));
        box.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));

        ImageView icon = Ui.icon(this, R.drawable.ic_opacity, 15,
                Ui.alpha(color(R.color.on_surface_variant), 0.8f));
        box.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 15), Ui.dp(this, 15)));

        SeekBar seek = new SeekBar(this);
        seek.setMax(90);
        seek.setProgress(Math.max(0, Math.min(90, h.item.opacity - 10)));
        seek.setProgressTintList(Ui.csl(color(R.color.primary)));
        seek.setThumbTintList(Ui.csl(color(R.color.primary)));
        seek.setProgressBackgroundTintList(Ui.csl(color(R.color.outline_variant)));
        seek.setMinimumHeight(0);
        seek.setMinimumWidth(0);
        seek.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, Ui.dp(this, 30), 1f);
        slp.leftMargin = Ui.dp(this, 6);
        box.addView(seek, slp);

        final TextView pct = Ui.label(this, h.item.opacity + "%", 11,
                color(R.color.on_surface_variant), false);
        pct.setGravity(Gravity.END);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(this, 34),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = Ui.dp(this, 4);
        box.addView(pct, plp);

        h.seek = seek;
        h.pct = pct;

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                h.item.opacity = progress + 10;
                applyOpacity(h);
                pct.setText(h.item.opacity + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                Store.save(OverlayService.this, items);
                MainActivity.onStoreChanged();
            }
        });
        return box;
    }

    /* ================= 通知 ================= */

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "浮窗运行状态",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(int count) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setContentTitle("浮窗便签")
                .setContentText(count > 0 ? count + " 个浮窗正在显示" : "暂无浮窗")
                .setSmallIcon(R.drawable.ic_eye)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pi);

        if (count > 0) {
            Intent stopIntent = new Intent(this, OverlayService.class).setAction(ACTION_STOP_ALL);
            PendingIntent ps = PendingIntent.getService(this, 1, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_trash), "全部关闭", ps).build());
        }
        return b.build();
    }

    private void updateNotification(int count) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTI_ID, buildNotification(count));
        } catch (Exception ignored) {}
    }

    /* ================= 图片解码 ================= */

    private Bitmap decodeBitmap(String path, int targetW) {
        if (path == null || path.isEmpty()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int sample = 1;
            if (bounds.outWidth > 0) {
                while (bounds.outWidth / sample > targetW) sample *= 2;
            }
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = Math.max(1, sample);
            return BitmapFactory.decodeFile(path, opt);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null || b.isEmpty() : a.equals(b == null ? "" : b);
    }

    private int color(int res) {
        return Ui.color(this, res);
    }

    /* ================= 根容器：拦截双指缩放 ================= */

    private class WindowRoot extends LinearLayout {

        private ScaleGestureDetector detector;
        private Holder holder;
        private boolean scaling;

        WindowRoot(Context c) {
            super(c);
        }

        void bind(final Holder h) {
            holder = h;
            detector = new ScaleGestureDetector(getContext(),
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScaleBegin(ScaleGestureDetector d) {
                            return !h.item.locked;
                        }

                        @Override
                        public boolean onScale(ScaleGestureDetector d) {
                            // getScaleFactor 是相对上一次回调的比值，直接累乘
                            applyScale(h, h.item.scale * d.getScaleFactor());
                            return true;
                        }
                    });
            detector.setQuickScaleEnabled(false);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (detector != null) detector.onTouchEvent(ev);

            boolean multi = ev.getPointerCount() > 1;
            if (multi && !scaling) {
                // 第二根手指按下：取消子控件按压态，接管本次手势
                scaling = true;
                MotionEvent cancel = MotionEvent.obtain(ev);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                return true;
            }
            if (scaling) {
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    scaling = false;
                    if (holder != null) {
                        Store.save(OverlayService.this, items);
                        MainActivity.onStoreChanged();
                    }
                }
                return true;
            }
            return super.dispatchTouchEvent(ev);
        }
    }

    /* ================= 受限容器 ================= */

    private static class MaxScroll extends ScrollView {
        int maxHeight = Integer.MAX_VALUE;

        MaxScroll(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec,
                    MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
            int h = Math.min(getMeasuredHeight(), maxHeight);
            setMeasuredDimension(getMeasuredWidth(), h);
        }
    }

    private static class MaxImageView extends ImageView {
        int maxHeight = Integer.MAX_VALUE;

        MaxImageView(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec,
                    MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
            if (getMeasuredHeight() > maxHeight) {
                setMeasuredDimension(getMeasuredWidth(), maxHeight);
            }
        }
    }
}
