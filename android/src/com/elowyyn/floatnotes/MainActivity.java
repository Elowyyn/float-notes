package com.elowyyn.floatnotes;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** 应用内管理界面：列表、添加、编辑、删除，以及悬浮窗权限引导。 */
public class MainActivity extends Activity {

    private static WeakReference<MainActivity> CURRENT;
    private static final int REQ_PICK = 2001;

    private final List<WindowItem> items = new ArrayList<>();
    private LinearLayout listBox;
    private LinearLayout emptyBox;
    private LinearLayout permBanner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CURRENT = new WeakReference<>(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        OverlayService.onActivityForeground(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        OverlayService.onActivityForeground(false);
    }

    /** 服务侧改动数据后回调 */
    public static void onStoreChanged() {
        MainActivity a = CURRENT == null ? null : CURRENT.get();
        if (a == null) return;
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                MainActivity cur = CURRENT.get();
                if (cur != null) cur.reload();
            }
        });
    }

    private int c(int res) {
        return Ui.color(this, res);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    /* ================= 界面骨架 ================= */

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(c(R.color.surface));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        final int contentW = Math.min(Ui.screenW(this) - dp(32), dp(640));

        /* 顶部栏 */
        LinearLayout headerWrap = new LinearLayout(this);
        headerWrap.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), Ui.statusBar(this) + dp(6), dp(10), dp(6));

        TextView title = Ui.label(this, "浮窗便签", 23, c(R.color.on_surface), true);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView clear = Ui.iconButton(this, R.drawable.ic_trash, 44, 20,
                c(R.color.on_surface_variant));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        });
        header.addView(clear);

        headerWrap.addView(header, new LinearLayout.LayoutParams(
                contentW, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(headerWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* 权限提示 */
        permBanner = buildPermBanner();
        LinearLayout permWrap = new LinearLayout(this);
        permWrap.setGravity(Gravity.CENTER_HORIZONTAL);
        permWrap.setPadding(dp(16), dp(4), dp(16), dp(6));
        permWrap.addView(permBanner, new LinearLayout.LayoutParams(
                contentW, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(permWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* 列表 */
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(6), dp(16), dp(120));

        emptyBox = buildEmpty();
        content.addView(emptyBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(listBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(Gravity.CENTER_HORIZONTAL);
        wrapper.addView(content, new LinearLayout.LayoutParams(
                contentW, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.addView(wrapper, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        /* FAB */
        FrameLayout fab = new FrameLayout(this);
        fab.setBackground(Ui.circle(this, c(R.color.primary)));
        fab.setElevation(dp(8));
        ImageView plus = Ui.icon(this, R.drawable.ic_plus, 24, c(R.color.on_primary));
        FrameLayout.LayoutParams plusLp = new FrameLayout.LayoutParams(dp(24), dp(24),
                Gravity.CENTER);
        fab.addView(plus, plusLp);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddSheet();
            }
        });

        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(dp(56), dp(56),
                Gravity.BOTTOM | Gravity.END);
        fabLp.setMargins(0, 0, dp(20), dp(24) + Ui.navBar(this));
        root.addView(fab, fabLp);

        setContentView(root);
    }

    private LinearLayout buildPermBanner() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(this, c(R.color.primary_container), 18));
        box.setPadding(dp(16), dp(14), dp(16), dp(6));
        box.setVisibility(View.GONE);

        TextView t1 = Ui.label(this, "还需开启「显示在其他应用上层」", 14.5f,
                c(R.color.on_primary_container), true);
        box.addView(t1);

        TextView t2 = Ui.label(this, "开启后浮窗才能悬浮在桌面和其它 App 之上", 12,
                Ui.alpha(c(R.color.on_primary_container), 0.78f), false);
        LinearLayout.LayoutParams t2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        t2lp.topMargin = dp(4);
        box.addView(t2, t2lp);

        TextView go = Ui.pillButton(this, "去开启", false);
        go.setTextColor(c(R.color.on_primary_container));
        go.setPadding(dp(10), dp(10), dp(10), dp(10));
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOverlaySettings();
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.gravity = Gravity.END;
        box.addView(go, glp);

        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOverlaySettings();
            }
        });
        return box;
    }

    private LinearLayout buildEmpty() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, dp(90), 0, dp(40));

        ImageView ic = Ui.icon(this, R.drawable.ic_image, 64,
                Ui.alpha(c(R.color.on_surface_variant), 0.35f));
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(64), dp(64));
        icLp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(ic, icLp);

        TextView t1 = Ui.label(this, "还没有浮窗", 16, Ui.alpha(c(R.color.on_surface), 0.85f), true);
        LinearLayout.LayoutParams t1lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        t1lp.topMargin = dp(14);
        t1lp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(t1, t1lp);

        TextView t2 = Ui.label(this, "点右下角 + 添加文字或图片浮窗", 12.5f,
                Ui.alpha(c(R.color.on_surface_variant), 0.75f), false);
        LinearLayout.LayoutParams t2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        t2lp.topMargin = dp(5);
        t2lp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(t2, t2lp);

        return box;
    }

    /* ================= 列表 ================= */

    private void reload() {
        items.clear();
        items.addAll(Store.load(this));
        render();
        boolean perm = Settings.canDrawOverlays(this);
        permBanner.setVisibility(perm ? View.GONE : View.VISIBLE);
        if (perm && !items.isEmpty()) {
            OverlayService.start(this);
            OverlayService.refresh(this);
        }
    }

    private void render() {
        listBox.removeAllViews();
        for (WindowItem w : items) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            listBox.addView(buildRow(w), lp);
        }
        emptyBox.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private Drawable rippleBg(int fillColor, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(Ui.alpha(c(R.color.primary), 0.12f)),
                Ui.round(this, fillColor, radius), null);
    }

    private LinearLayout buildRow(final WindowItem w) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, c(R.color.container), 18));
        card.setPadding(dp(12), dp(10), dp(12), dp(12));

        /* --- 标题行 --- */
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClickable(true);

        FrameLayout ib = new FrameLayout(this);
        ib.setBackground(Ui.round(this, c(R.color.primary_container), 13));
        ib.addView(Ui.icon(this, w.type == WindowItem.TYPE_IMAGE ? R.drawable.ic_image
                : R.drawable.ic_text, 20, c(R.color.on_primary_container)),
                new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        head.addView(ib, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(12), 0, dp(8), 0);
        tx.addView(Ui.label(this, w.title(), 15, c(R.color.on_surface), true));
        TextView t2 = Ui.label(this, w.stateLabel(), 12, c(R.color.on_surface_variant), false);
        LinearLayout.LayoutParams t2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        t2lp.topMargin = dp(3);
        tx.addView(t2, t2lp);
        head.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView del = Ui.iconButton(this, R.drawable.ic_trash, 40, 19, c(R.color.on_surface_variant));
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete(w);
            }
        });
        head.addView(del);

        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (w.type == WindowItem.TYPE_TEXT) {
                    showTextDialog(w);
                } else {
                    Toast.makeText(MainActivity.this, "图片浮窗：点右侧图标可删除",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* --- 透明度 --- */
        LinearLayout opaRow = new LinearLayout(this);
        opaRow.setOrientation(LinearLayout.HORIZONTAL);
        opaRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams opaRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        opaRowLp.topMargin = dp(8);
        card.addView(opaRow, opaRowLp);

        opaRow.addView(Ui.icon(this, R.drawable.ic_opacity, 14, c(R.color.on_surface_variant)),
                new LinearLayout.LayoutParams(dp(14), dp(14)));

        final TextView opaPct = Ui.label(this, w.opacity + "%", 11, c(R.color.on_surface_variant), false);
        opaPct.setGravity(Gravity.END);

        SeekBar opaSeek = new SeekBar(this);
        opaSeek.setMax(90);
        opaSeek.setProgress(Math.max(0, Math.min(90, w.opacity - 10)));
        styleSeek(opaSeek);
        LinearLayout.LayoutParams osLp = new LinearLayout.LayoutParams(0, dp(26), 1f);
        osLp.leftMargin = dp(8);
        osLp.rightMargin = dp(8);
        opaRow.addView(opaSeek, osLp);
        opaRow.addView(opaPct, new LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT));

        opaSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                w.opacity = p + 10;
                opaPct.setText(w.opacity + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                persistAndSync();
            }
        });

        /* --- 可见性 / 锁定 开关 --- */
        LinearLayout toggles = new LinearLayout(this);
        toggles.setOrientation(LinearLayout.HORIZONTAL);
        toggles.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tgLp.topMargin = dp(2);
        card.addView(toggles, tgLp);

        toggles.addView(buildToggle("可见", !w.hidden, new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                w.hidden = !on;
                persistAndSync();
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        toggles.addView(buildToggle("锁定", w.locked, new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                w.locked = on;
                persistAndSync();
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        /* --- 缩放大小 + 重置 --- */
        LinearLayout scaleRow = new LinearLayout(this);
        scaleRow.setOrientation(LinearLayout.HORIZONTAL);
        scaleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srLp.topMargin = dp(4);
        card.addView(scaleRow, srLp);

        scaleRow.addView(Ui.icon(this, R.drawable.ic_fit, 14, c(R.color.on_surface_variant)),
                new LinearLayout.LayoutParams(dp(14), dp(14)));

        final TextView scaleVal = Ui.label(this, scaleText(w.scale), 11,
                c(R.color.on_surface_variant), false);
        scaleVal.setGravity(Gravity.END);

        final SeekBar scaleSeek = new SeekBar(this);
        scaleSeek.setMax(79);
        scaleSeek.setProgress(Math.max(0, Math.min(79, Math.round(w.scale * 10) - 1)));
        styleSeek(scaleSeek);
        LinearLayout.LayoutParams ssLp = new LinearLayout.LayoutParams(0, dp(26), 1f);
        ssLp.leftMargin = dp(8);
        ssLp.rightMargin = dp(8);
        scaleRow.addView(scaleSeek, ssLp);
        scaleRow.addView(scaleVal, new LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView reset = Ui.label(this, "重置", 12, c(R.color.on_primary_container), true);
        reset.setBackground(Ui.round(this, c(R.color.primary_container), 999));
        reset.setPadding(dp(12), dp(5), dp(12), dp(5));
        reset.setGravity(Gravity.CENTER);
        reset.setClickable(true);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(6);
        scaleRow.addView(reset, rlp);

        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                w.scale = 1f;
                scaleSeek.setProgress(9);
                scaleVal.setText(scaleText(w.scale));
                persistAndSync();
            }
        });

        scaleSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                w.scale = (p + 1) / 10f;
                scaleVal.setText(scaleText(w.scale));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                persistAndSync();
            }
        });

        return card;
    }

    private void styleSeek(SeekBar seek) {
        seek.setProgressTintList(Ui.csl(c(R.color.primary)));
        seek.setThumbTintList(Ui.csl(c(R.color.primary)));
        seek.setProgressBackgroundTintList(Ui.csl(c(R.color.outline_variant)));
        seek.setMinimumHeight(0);
        seek.setMinimumWidth(0);
        seek.setPadding(dp(2), 0, dp(2), 0);
    }

    private String scaleText(float s) {
        return String.format(java.util.Locale.US, "%.1f×", s);
    }

    private LinearLayout buildToggle(String label, boolean checked,
            android.widget.CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);

        TextView l = Ui.label(this, label, 13, c(R.color.on_surface_variant), false);
        box.addView(l);

        Switch sw = new Switch(this);
        sw.setShowText(false);
        sw.setChecked(checked);
        int[][] states = new int[][]{
                new int[]{ android.R.attr.state_checked },
                new int[]{ -android.R.attr.state_checked } };
        sw.setThumbTintList(new ColorStateList(states,
                new int[]{ c(R.color.on_primary), c(R.color.outline) }));
        sw.setTrackTintList(new ColorStateList(states,
                new int[]{ c(R.color.primary), c(R.color.outline_variant) }));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(6);
        box.addView(sw, slp);
        sw.setOnCheckedChangeListener(listener);
        return box;
    }

    /** 把当前列表改动写回 Store，并同步到正在运行的浮窗服务。 */
    private void persistAndSync() {
        Store.save(this, items);
        if (!items.isEmpty() && Settings.canDrawOverlays(this)) {
            OverlayService.start(this);
            OverlayService.refresh(this);
        }
        render();
    }

    /* ================= 添加 ================= */

    private void showAddSheet() {
        final Dialog d = new Dialog(this, R.style.SheetTheme);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(Ui.roundTop(this, c(R.color.container_low), 28));
        sheet.setPadding(dp(16), dp(10), dp(16), dp(14) + Ui.navBar(this));

        View handle = new View(this);
        handle.setBackground(Ui.round(this, c(R.color.outline_variant), 999));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(32), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(16);
        sheet.addView(handle, hlp);

        TextView title = Ui.label(this, "添加浮窗", 16, c(R.color.on_surface), true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(dp(4), 0, 0, dp(14));
        sheet.addView(title, tlp);

        sheet.addView(buildOption(R.drawable.ic_text, "文字浮窗",
                "输入一段文字，悬浮在屏幕最上层",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        d.dismiss();
                        showTextDialog(null);
                    }
                }));

        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(dp(1), dp(10));
        sheet.addView(new View(this), gap);

        sheet.addView(buildOption(R.drawable.ic_image, "图片浮窗",
                "从相册选择，可一次添加多张",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        d.dismiss();
                        pickImages();
                    }
                }));

        TextView tip = Ui.label(this, "双指捏合可缩放浮窗 · 按住控制行可拖动",
                12, Ui.alpha(c(R.color.on_surface_variant), 0.8f), false);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = dp(14);
        tipLp.leftMargin = dp(6);
        sheet.addView(tip, tipLp);

        d.setContentView(sheet);
        d.show();

        Window win = d.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            win.setGravity(Gravity.BOTTOM);
            win.setDimAmount(0.42f);
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
    }

    private View buildOption(int iconRes, String title, String sub, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rippleBg(c(R.color.container_high), 16));
        row.setPadding(dp(14), dp(13), dp(14), dp(13));
        row.setClickable(true);
        row.setOnClickListener(click);

        FrameLayout ib = new FrameLayout(this);
        ib.setBackground(Ui.round(this, c(R.color.primary_container), 13));
        ib.addView(Ui.icon(this, iconRes, 22, c(R.color.on_primary_container)),
                new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        row.addView(ib, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(14), 0, 0, 0);
        tx.addView(Ui.label(this, title, 15, c(R.color.on_surface), true));
        TextView s = Ui.label(this, sub, 12, c(R.color.on_surface_variant), false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(3);
        tx.addView(s, slp);
        row.addView(tx, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        return row;
    }

    /* ================= 文字对话框 ================= */

    private void showTextDialog(final WindowItem target) {
        final Dialog d = new Dialog(this, R.style.DialogTheme);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(this, c(R.color.container_high), 24));
        box.setPadding(dp(20), dp(20), dp(20), dp(12));

        box.addView(Ui.label(this, target == null ? "新增文字浮窗" : "编辑文字", 18,
                c(R.color.on_surface), true));

        final EditText et = new EditText(this);
        et.setText(target == null ? "" : target.text);
        et.setHint("输入要显示的内容…");
        et.setTextSize(15);
        et.setTextColor(c(R.color.on_surface));
        et.setHintTextColor(Ui.alpha(c(R.color.on_surface_variant), 0.7f));
        et.setBackground(Ui.round(this, c(R.color.container), 14));
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        et.setMinLines(4);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(14);
        box.addView(et, elp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(14);
        box.addView(actions, alp);

        TextView cancel = Ui.pillButton(this, "取消", false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        actions.addView(cancel);

        TextView ok = Ui.pillButton(this, target == null ? "添加" : "保存", true);
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        okLp.leftMargin = dp(6);
        actions.addView(ok, okLp);

        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String txt = et.getText().toString().trim();
                if (txt.isEmpty()) {
                    Toast.makeText(MainActivity.this, "内容不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (target == null) {
                    addTextItem(txt);
                } else {
                    target.text = txt;
                    persist();
                }
                d.dismiss();
            }
        });

        d.setContentView(box);
        d.show();

        Window win = d.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.setLayout((int) (Ui.screenW(this) * 0.88f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        et.requestFocus();
    }

    private void addTextItem(String txt) {
        WindowItem w = new WindowItem();
        w.id = Store.newId();
        w.type = WindowItem.TYPE_TEXT;
        w.text = txt;
        w.scale = 1f;
        placeNew(w);
        items.add(w);
        persist();
    }

    /** 给新浮窗挑位置：与已有浮窗错开成阶梯，保证每张卡片都露出来。 */
    private void placeNew(WindowItem w) {
        int sw = Ui.screenW(this), sh = Ui.screenH(this);
        int wPx = Math.round(dp(252) * w.scale) + dp(12);
        int hPx = dp(250);
        int bestX = dp(16), bestY = dp(96);
        for (int i = 0; i < 7; i++) {
            int cx = dp(16) + i * dp(26);
            int cy = dp(96) + i * dp(84);
            if (cx + wPx > sw - dp(8)) break;
            if (cy + hPx > sh - dp(72)) break;
            if (!occupied(cx, cy)) {
                bestX = cx;
                bestY = cy;
                break;
            }
        }
        w.x = Math.max(0, Math.min(bestX, Math.max(0, sw - wPx)));
        w.y = Math.max(0, Math.min(bestY, Math.max(0, sh - hPx)));
    }

    private boolean occupied(int x, int y) {
        for (WindowItem o : items) {
            if (Math.abs(o.x - x) < dp(22) && Math.abs(o.y - y) < dp(22)) return true;
        }
        return false;
    }

    /* ================= 图片 ================= */

    private void pickImages() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的相册应用", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        int added = 0;
        for (Uri u : uris) {
            String id = Store.newId();
            String path = saveImage(u, id);
            if (path == null) continue;
            WindowItem w = new WindowItem();
            w.id = id;
            w.type = WindowItem.TYPE_IMAGE;
            w.imagePath = path;
            w.scale = 1f;
            placeNew(w);
            items.add(w);
            added++;
        }
        if (added == 0) {
            Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show();
            return;
        }
        persist();
    }

    private String saveImage(Uri uri, String id) {
        InputStream in = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            in = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, bounds);
            if (in != null) in.close();

            int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (maxSide > 0 && maxSide / sample > 1280) sample *= 2;

            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = Math.max(1, sample);
            in = getContentResolver().openInputStream(uri);
            Bitmap bm = BitmapFactory.decodeStream(in, null, opt);
            if (in != null) in.close();
            if (bm == null) return null;

            int w = bm.getWidth(), h = bm.getHeight();
            float sc = Math.min(1f, 1280f / Math.max(w, h));
            if (sc < 1f) {
                Bitmap scaled = Bitmap.createScaledBitmap(bm,
                        Math.max(1, Math.round(w * sc)), Math.max(1, Math.round(h * sc)), true);
                if (scaled != bm) bm.recycle();
                bm = scaled;
            }

            File f = new File(Store.imageDir(this), id + ".jpg");
            FileOutputStream fos = new FileOutputStream(f);
            bm.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            fos.close();
            bm.recycle();
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {}
        }
    }

    /* ================= 删除 / 清空 ================= */

    private void confirmDelete(final WindowItem w) {
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("删除这个浮窗？")
                .setMessage(w.title())
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        items.remove(w);
                        Store.deleteImage(w.imagePath);
                        persist();
                    }
                })
                .show();
    }

    private void confirmClear() {
        if (items.isEmpty()) {
            Toast.makeText(this, "当前没有浮窗", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("关闭全部浮窗？")
                .setMessage("共 " + items.size() + " 个，删除后不可恢复")
                .setNegativeButton("取消", null)
                .setPositiveButton("全部关闭", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        for (WindowItem w : items) Store.deleteImage(w.imagePath);
                        items.clear();
                        Store.save(MainActivity.this, items);
                        OverlayService.stop(MainActivity.this);
                        render();
                    }
                })
                .show();
    }

    /* ================= 持久化 / 服务 ================= */

    private void persist() {
        Store.save(this, items);
        render();
        if (items.isEmpty()) {
            OverlayService.stop(this);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            permBanner.setVisibility(View.VISIBLE);
            Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show();
            return;
        }
        permBanner.setVisibility(View.GONE);
        OverlayService.start(this);
        OverlayService.refresh(this);
    }

    private void openOverlaySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Exception ignored) {}
        }
    }
}
