package com.drivesequencer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Speedometer ring, big speed, state, tempo, 16 step lights and the instrument labels. */
public class GaugeView extends View {
    static final int BG = 0xFF0D1013, LINE = 0xFF262E36, INK = 0xFFEEF2F5, MUTE = 0xFF8D99A6,
            AMBER = 0xFFFFB000, GO = 0xFF39D98A, STOP = 0xFFFF5A4D;

    // set by MainActivity
    String speed = "0", unit = "mph", state = "Ready", sub = "Press a button below to start";
    int stateColor = MUTE, step = -1, mask = 0;
    float energy, boost, brake;
    boolean running, showLayers = true;
    String[] layers = new String[0];

    private float pulse;
    private int lastStep = -1;
    private final float d;
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF(), cell = new RectF();
    private final Typeface cond = Typeface.create("sans-serif-condensed", Typeface.BOLD);

    public GaugeView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        txt.setTextAlign(Paint.Align.CENTER);
    }

    /** Call after changing the fields above. */
    void update() {
        if (step != lastStep) {
            if (step >= 0 && step % 4 == 0) pulse = step == 0 ? 1f : .55f;
            lastStep = step;
        }
        pulse *= .85f;
        invalidate();
    }

    private float ringH(int w) { return w * .84f; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = (int) (ringH(w) + 10 * d + 12 * d + 14 * d + 2 * 30 * d + 8 * d);
        setMeasuredDimension(w, h);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth();
        float H = ringH(w), cx = w / 2f, cy = H * .58f, R = Math.min(w / 2f, H * .56f) * .86f;
        float a0 = 135f, sweep = 270f, lw = R * .085f;

        // ticks
        for (int i = 0; i <= 30; i++) {
            double a = Math.toRadians(a0 + sweep * i / 30f);
            boolean lit = running && i / 30f <= energy;
            float r1 = R * .78f, r2 = R * (i % 5 == 0 ? .86f : .83f);
            arc.setColor(lit ? AMBER : 0xFF2A333C);
            arc.setStrokeWidth((i % 5 == 0 ? 3 : 2) * d);
            c.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1, cx + (float) Math.cos(a) * r2, cy + (float) Math.sin(a) * r2, arc);
        }
        // track + energy arc with a glow that pulses on the beat
        oval.set(cx - R, cy - R, cx + R, cy + R);
        arc.setColor(0xFF1C2329);
        arc.setStrokeWidth(lw);
        c.drawArc(oval, a0, sweep, false, arc);
        if (energy > .003f) {
            arc.setColor(AMBER);
            arc.setAlpha((int) (40 + 90 * pulse));
            arc.setStrokeWidth(lw * (1.7f + .6f * pulse));
            c.drawArc(oval, a0, sweep * energy, false, arc);
            arc.setAlpha(255);
            arc.setStrokeWidth(lw * (1 + .2f * pulse));
            c.drawArc(oval, a0, sweep * energy, false, arc);
        }
        float ro = R + lw * 1.1f;
        oval.set(cx - ro, cy - ro, cx + ro, cy + ro);
        arc.setStrokeWidth(3 * d);
        if (boost > .05f) {
            arc.setColor(GO);
            arc.setAlpha((int) (255 * Math.min(1f, boost * 1.4f)));
            c.drawArc(oval, a0, sweep * Math.min(1f, energy + .25f * boost), false, arc);
        }
        if (brake > .05f) {
            arc.setColor(STOP);
            arc.setAlpha((int) (255 * Math.min(1f, brake * 1.4f)));
            c.drawArc(oval, a0, sweep * Math.max(.02f, energy), false, arc);
        }
        arc.setAlpha(255);

        // readout
        txt.setTypeface(cond);
        txt.setColor(INK);
        txt.setTextSize(R * .58f);
        c.drawText(speed, cx, cy + R * .16f, txt);
        txt.setTypeface(Typeface.DEFAULT);
        txt.setColor(MUTE);
        txt.setTextSize(15 * d);
        c.drawText(unit, cx, cy + R * .16f + 22 * d, txt);
        txt.setTypeface(cond);
        txt.setColor(stateColor);
        txt.setTextSize(23 * d);
        c.drawText(state, cx, cy + R * .16f + 52 * d, txt);
        txt.setTypeface(Typeface.DEFAULT);
        txt.setColor(MUTE);
        txt.setTextSize(14 * d);
        c.drawText(sub, cx, cy + R * .16f + 72 * d, txt);

        // 16 step lights
        float y = H + 10 * d, gap = 4 * d, cw = (w - 15 * gap) / 16f;
        for (int i = 0; i < 16; i++) {
            float x = i * (cw + gap);
            cell.set(x, y, x + cw, y + 12 * d);
            fill.setColor(i == step ? AMBER : i % 4 == 0 ? 0xFF2C3742 : 0xFF1D242B);
            c.drawRoundRect(cell, 3 * d, 3 * d, fill);
        }

        // instrument labels, centred rows that wrap
        if (!showLayers || layers.length == 0) return;
        y += 12 * d + 14 * d;
        txt.setTextSize(13 * d);
        float pad = 11 * d, ch = 26 * d, sp = 6 * d;
        int start = 0;
        while (start < layers.length) {
            float rowW = 0;
            int end = start;
            while (end < layers.length) {
                float cwid = chipW(layers[end], pad);
                float add = (end > start ? sp : 0) + cwid;
                if (rowW + add > w && end > start) break;
                rowW += add;
                end++;
            }
            float x = (w - rowW) / 2f;
            for (int i = start; i < end; i++) {
                boolean on = running && (mask & (1 << i)) != 0;
                float cwid = chipW(layers[i], pad);
                cell.set(x, y, x + cwid, y + ch);
                if (on) {
                    fill.setStyle(Paint.Style.FILL);
                    fill.setColor(GO);
                } else {
                    fill.setStyle(Paint.Style.STROKE);
                    fill.setStrokeWidth(1 * d);
                    fill.setColor(LINE);
                }
                c.drawRoundRect(cell, ch / 2, ch / 2, fill);
                fill.setStyle(Paint.Style.FILL);
                txt.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                txt.setColor(on ? BG : MUTE);
                c.drawText(layers[i], x + cwid / 2, y + ch / 2 + 4.5f * d, txt);
                x += cwid + sp;
            }
            y += ch + 6 * d;
            start = end;
        }
    }

    private float chipW(String s, float pad) {
        txt.setTypeface(Typeface.DEFAULT_BOLD);
        return txt.measureText(s) + pad * 2;
    }
}
