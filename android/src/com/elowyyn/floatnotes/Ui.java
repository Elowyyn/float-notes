package com.elowyyn.floatnotes;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 轻量 UI 工具：dp 换算、MD3 圆角背景、图标视图。 */
final class Ui {

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static int color(Context c, int resId) {
        return c.getColor(resId);
    }

    /** 透明度调整（0..255 原始 alpha 保留） */
    static int alpha(int color, float a) {
        return Color.argb(Math.round(255 * a), Color.red(color), Color.green(color), Color.blue(color));
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable roundStroke(Context c, int color, int strokeColor, float strokeDp, float radiusDp) {
        GradientDrawable d = round(c, color, radiusDp);
        d.setStroke(Math.max(1, dp(c, strokeDp)), strokeColor);
        return d;
    }

    static GradientDrawable roundTop(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        float r = dp(c, radiusDp);
        d.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return d;
    }

    static GradientDrawable circle(Context c, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /** 系统无边涟漪背景；取不到时返回 null。 */
    static Drawable ripple(Context c) {
        try {
            TypedValue tv = new TypedValue();
            boolean ok = c.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, tv, true);
            if (ok && tv.resourceId != 0) return c.getDrawable(tv.resourceId);
        } catch (Exception ignored) {}
        return null;
    }

    static Drawable rippleBounded(Context c) {
        try {
            TypedValue tv = new TypedValue();
            boolean ok = c.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground, tv, true);
            if (ok && tv.resourceId != 0) return c.getDrawable(tv.resourceId);
        } catch (Exception ignored) {}
        return null;
    }

    static ImageView icon(Context c, int resId, float sizeDp, int tint) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(resId);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (tint != 0) iv.setColorFilter(tint);
        int s = dp(c, sizeDp);
        iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return iv;
    }

    /** 40dp 命中区域 + 18~20dp 图标，带无边涟漪。 */
    static ImageView iconButton(Context c, int resId, float boxDp, float iconDp, int tint) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(resId);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setColorFilter(tint);
        int box = dp(c, boxDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(box, box);
        iv.setLayoutParams(lp);
        iv.setPadding((int) ((box - dp(c, iconDp)) / 2f), (int) ((box - dp(c, iconDp)) / 2f),
                (int) ((box - dp(c, iconDp)) / 2f), (int) ((box - dp(c, iconDp)) / 2f));
        Drawable r = ripple(c);
        if (r != null) iv.setBackground(r);
        iv.setClickable(true);
        iv.setFocusable(true);
        return iv;
    }

    static TextView label(Context c, String text, float sp, int color, boolean medium) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (medium) tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        tv.setIncludeFontPadding(false);
        return tv;
    }

    static TextView pillButton(Context c, String text, boolean filled) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        tv.setIncludeFontPadding(false);
        int padH = dp(c, 22), padV = dp(c, 12);
        tv.setPadding(padH, padV, padH, padV);
        if (filled) {
            tv.setTextColor(color(c, R.color.on_primary));
            tv.setBackground(round(c, color(c, R.color.primary), 999));
        } else {
            tv.setTextColor(color(c, R.color.primary));
            Drawable r = ripple(c);
            if (r != null) tv.setBackground(r);
        }
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    static LinearLayout.LayoutParams withBottomMargin(LinearLayout.LayoutParams p, int marginPx) {
        p.bottomMargin = marginPx;
        return p;
    }

    static int screenW(Context c) {
        return c.getResources().getDisplayMetrics().widthPixels;
    }

    static int screenH(Context c) {
        return c.getResources().getDisplayMetrics().heightPixels;
    }

    static int statusBar(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : dp(c, 24);
    }

    static int navBar(Context c) {
        int id = c.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : dp(c, 24);
    }

    static ColorStateList csl(int color) {
        return ColorStateList.valueOf(color);
    }

    static View space(Context c, int w, int h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        return v;
    }
}
