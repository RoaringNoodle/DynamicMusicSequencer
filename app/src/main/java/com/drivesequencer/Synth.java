package com.drivesequencer;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * The whole music engine: drive model (speed -> energy), step sequencer, presets,
 * synth voices and effects. Plain Java with no Android classes, so it can be tested on a PC.
 *
 * Energy (0..1) comes from speed relative to "full energy at", plus a lift when accelerating
 * and a dip when braking. Each preset turns energy into tempo, brightness and which instruments play.
 */
public final class Synth {
    public static final int SR = 48000;
    static final int BLOCK = 128;
    private static Synth instance;

    public static synchronized Synth get() {
        if (instance == null) instance = new Synth();
        return instance;
    }

    public static final int SRC_NONE = 0, SRC_GPS = 1, SRC_SIM = 2;
    static final int SINE = 0, TRI = 1, SAW = 2, PULSE12 = 3, PULSE25 = 4, NOISE = 5;
    static final int MEL = 0, FILT = 1, DRUM = 2;
    static final int LP = 0, HP = 1, BP = 2;

    // ---- inputs, set from any thread
    public volatile int source = SRC_NONE;
    public volatile float simTarget = 0f, topKmh = 96.6f, volume = .8f, duck = 1f;
    public volatile int preset = 0;
    public volatile boolean generative = true;
    private float gpsKmh;
    private long gpsSeq;
    private double gpsTime;

    /** Called by the GPS listener with the latest speed in km/h. */
    public synchronized void pushGps(float kmh) {
        gpsKmh = kmh;
        gpsTime = wallMs();
        gpsSeq++;
    }

    // ---- outputs, read by the screen
    public volatile float speedOut, energyOut, bpmOut = 66, boostOut, brakeOut;
    public volatile int stepOut = -1, layerMask = 0;
    public volatile boolean waitingGps = false;

    // ---- drive model (audio thread only)
    private int lastSource = -1;
    private long seenSeq = 0;
    private double spd, acc, E, eBar, boost, brake, bpm = 66, simV, lastT = -1, lastFix = -1e12, audioMs;

    // ---- sequencer
    private double stepCountdown, curOff;
    private int step, bar, arpI;
    private boolean seqOn;

    // ---- mixing
    private final double[] busMel = new double[BLOCK], busFilt = new double[BLOCK], busDrum = new double[BLOCK], busEcho = new double[BLOCK];
    private final ArrayList<Voice> voices = new ArrayList<>();
    private final Biquad lpL = new Biquad(), lpR = new Biquad(), dLp = new Biquad();
    private final Reverb reverb = new Reverb();
    private final double[] echoBuf = new double[SR * 2];
    private int echoW;
    private double echoDelay = SR * .3, cut = 600, dCut = 18000, revSend = .4, gain = .8, compEnv = 0;
    private long rng = 0x9E3779B97F4A7C15L;
    private static final double TH = .2, MAKEUP = 1.5;

    static double wallMs() { return System.nanoTime() / 1e6; }
    static double clamp(double x, double a, double b) { return Math.min(b, Math.max(a, x)); }
    static double clamp01(double x) { return clamp(x, 0, 1); }
    static double mf(double m) { return 440 * Math.pow(2, (m - 69) / 12.0); }
    static double k(double dt, double tau) { return 1 - Math.exp(-dt / tau); }

    double noise() {
        rng ^= rng << 13; rng ^= rng >>> 7; rng ^= rng << 17;
        return ((rng >>> 11) * 0x1.0p-53) * 2 - 1;
    }

    /** Fill out[] with frames of interleaved stereo 16-bit audio. */
    public synchronized void render(short[] out, int frames) {
        int pos = 0;
        while (pos < frames) {
            int n = Math.min(BLOCK, frames - pos);
            control(n / (double) SR);
            block(out, pos, n);
            pos += n;
            audioMs += n * 1000.0 / SR;
        }
    }

    private void ingest(double v, double t) {
        if (lastT < 0) { lastT = t; spd = v; return; }
        double dt = (t - lastT) / 1000;
        if (dt <= .02) return;
        lastT = t;
        double sm = spd + (v - spd) * (1 - Math.exp(-dt / .6));
        acc += ((sm - spd) / dt - acc) * (1 - Math.exp(-dt / .8));
        spd = (v == 0 && sm < .5) ? 0 : sm;
    }

    private void control(double dt) {
        int src = source;
        double now = wallMs();
        if (src != lastSource) {
            if (src != SRC_NONE && !seqOn) { seqOn = true; stepCountdown = .05 * SR; step = 0; }
            if (src == SRC_NONE) { seqOn = false; stepOut = -1; layerMask = 0; }
            lastSource = src; lastT = -1; acc = 0; simV = spd;
            if (src == SRC_GPS) lastFix = -1e12;
        }
        if (src == SRC_SIM) {
            simV += clamp(simTarget - simV, -14 * dt, 9 * dt);
            ingest(simV, audioMs);
        } else if (src == SRC_GPS) {
            long sq; float v; double t;
            synchronized (this) { sq = gpsSeq; v = gpsKmh; t = gpsTime; }
            if (sq != seenSeq) { seenSeq = sq; lastFix = t; ingest(v, t); }
            if (now - lastFix > 4000) acc *= Math.exp(-dt);
        } else {
            spd *= Math.exp(-dt / .5);
            acc = 0;
        }
        Preset p = PRESETS[presetIndex()];
        double es = clamp01(spd / Math.max(5, topKmh));
        boost += (clamp01(acc / 8) - boost) * k(dt, .4);
        brake += (clamp01(-acc / 10) - brake) * k(dt, .4);
        double tE = clamp01(es + .25 * boost - .1 * brake);
        E += (tE - E) * k(dt, tE > E ? 1 : 2.2);
        bpm = p.bpmLo + (p.bpmHi - p.bpmLo) * (E + boost / 6);
        double tc = p.cutLo * Math.pow(p.cutHi / p.cutLo, E) * (1 - .6 * brake);
        cut += (tc - cut) * k(dt, .15);
        dCut += (18000 * (1 - .85 * brake) - dCut) * k(dt, .15);
        lpL.set(LP, cut, 1.11);
        lpR.set(LP, cut, 1.11);
        dLp.set(LP, dCut, 1.12);
        revSend += ((.45 - .3 * E) - revSend) * k(dt, .3);
        double ed = Math.min(1.9, 3 * 60 / bpm / 4) * SR;
        echoDelay += (ed - echoDelay) * k(dt, .2);
        gain += (volume * duck - gain) * k(dt, .05);
        speedOut = (float) spd;
        energyOut = (float) E;
        bpmOut = (float) bpm;
        boostOut = (float) boost;
        brakeOut = (float) brake;
        waitingGps = src == SRC_GPS && now - lastFix > 4000;
    }

    private int presetIndex() { return Math.max(0, Math.min(PRESETS.length - 1, preset)); }

    private void block(short[] out, int pos, int n) {
        Arrays.fill(busMel, 0, n, 0);
        Arrays.fill(busFilt, 0, n, 0);
        Arrays.fill(busDrum, 0, n, 0);
        Arrays.fill(busEcho, 0, n, 0);
        Preset p = PRESETS[presetIndex()];
        if (seqOn && generative) {
            while (stepCountdown < n) {
                double sd = 60 / bpm / 4;
                // instruments only join or leave on the half-bar, and need a clear drop to leave
                if (step == 0 || step == 8) {
                    if (E > eBar || E < eBar - .06 || E < .02) eBar = E;
                    int mask = 0;
                    for (int i = 0; i < p.layerAt.length; i++) if (eBar >= p.layerAt[i]) mask |= 1 << i;
                    layerMask = mask;
                }
                curOff = Math.max(0, stepCountdown) + ((step & 1) == 1 ? p.swing * sd * SR : 0);
                p.step(this, step, bar, eBar, sd);
                stepOut = step;
                stepCountdown += sd * SR;
                if (++step == 16) { step = 0; bar++; }
            }
            stepCountdown -= n;
        }
        for (int i = voices.size() - 1; i >= 0; i--) if (voices.get(i).render(n)) voices.remove(i);

        double rs = revSend;
        for (int i = 0; i < n; i++) {
            double m = busMel[i];
            reverb.process(m * rs);
            double e = echo(busEcho[i]);
            double dry = m + busFilt[i] + e;
            double l = lpL.process(dry + reverb.l), r = lpR.process(dry + reverb.r);
            double d = dLp.process(busDrum[i]);
            l = (l + d) * gain;
            r = (r + d) * gain;
            double pk = Math.max(Math.abs(l), Math.abs(r));
            compEnv = pk > compEnv ? compEnv + (pk - compEnv) * .02 : compEnv * .99995;
            double g = (compEnv > TH ? (TH + (compEnv - TH) / 4) / compEnv : 1) * MAKEUP;
            out[(pos + i) * 2] = (short) (Math.tanh(l * g) * 32000);
            out[(pos + i) * 2 + 1] = (short) (Math.tanh(r * g) * 32000);
        }
    }

    private double echo(double in) {
        int len = echoBuf.length;
        double rp = echoW - echoDelay;
        while (rp < 0) rp += len;
        int i0 = (int) rp;
        double fr = rp - i0;
        int i1 = (i0 + 1) % len;
        double y = echoBuf[i0] * (1 - fr) + echoBuf[i1] * fr;
        echoBuf[echoW] = in * .35 + y * .38 + 1e-20;
        echoW = (echoW + 1) % len;
        return y;
    }

    // ================= voices =================

    static double blep(double t, double dt) {
        if (t < dt) { t /= dt; return t + t - t * t - 1; }
        if (t > 1 - dt) { t = (t - 1) / dt; return t * t + t + t + 1; }
        return 0;
    }

    static double pulse(double p, double dt, double d) {
        double v = p < d ? 1 : -1;
        v += blep(p, dt);
        double q = p - d;
        if (q < 0) q += 1;
        v -= blep(q, dt);
        return (v - (2 * d - 1)) / (2 - 2 * d);
    }

    final class Voice {
        int delay, n, aN, hN, rN, total, wave, dest, sweepN, ftype;
        double peak, env, mulA, mulR, vibCents, vibPh, vibInc, sweepMul = 1, fCur, fMul32 = 1, fq;
        boolean echo;
        double[] ph, inc;
        Biquad flt;

        /** Adds this voice into its bus for the block; returns true when it has finished. */
        boolean render(int len) {
            if (delay >= len) { delay -= len; return false; }
            int i = delay;
            delay = 0;
            double[] bus = dest == MEL ? busMel : dest == FILT ? busFilt : busDrum;
            for (; i < len; i++) {
                if (n >= total) return true;
                double e;
                if (n < aN) { e = env; env *= mulA; }
                else if (n < aN + hN) { e = env = peak; }
                else { if (n == aN + hN) env = peak; e = env; env *= mulR; }
                double vib = 1;
                if (vibCents != 0) { vib = 1 + vibCents * 0.000577623 * Math.sin(vibPh); vibPh += vibInc; }
                if (n < sweepN) for (int k = 0; k < inc.length; k++) inc[k] *= sweepMul;
                double s = 0;
                for (int k = 0; k < ph.length; k++) {
                    double dt = inc[k] * vib, p = ph[k];
                    switch (wave) {
                        case SINE: s += Math.sin(2 * Math.PI * p); break;
                        case TRI: s += 4 * Math.abs(p - .5) - 1; break;
                        case SAW: s += 2 * p - 1 - blep(p, dt); break;
                        case PULSE12: s += pulse(p, dt, .125); break;
                        case PULSE25: s += pulse(p, dt, .25); break;
                        default: s += noise();
                    }
                    p += dt;
                    if (p >= 1) p -= 1;
                    ph[k] = p;
                }
                if (flt != null) {
                    if (fMul32 != 1 && (n & 31) == 0) { flt.set(ftype, fCur, fq); fCur *= fMul32; }
                    s = flt.process(s);
                }
                double o = s * e;
                bus[i] += o;
                if (echo) busEcho[i] += o;
                n++;
            }
            return n >= total;
        }
    }

    /** Note builder: S.n().w(SAW).v(.05).r(.4).play(60) */
    final class N {
        int w = SINE, dest = MEL, ftype = LP;
        double v = .05, a = .005, h = 0, r = .3, lp = 0, lpEnd = 0, q = 1, vib = 0, at = 0, f1 = 0, sweep = 0;
        double[] det = {0};
        boolean echo;

        N w(int x) { w = x; return this; }
        N v(double x) { v = x; return this; }
        N a(double x) { a = x; return this; }
        N h(double x) { h = x; return this; }
        N r(double x) { r = x; return this; }
        N det(double... c) { det = c; return this; }
        N lp(double x) { lp = x; return this; }
        N lpEnd(double x) { lpEnd = x; return this; }
        N q(double x) { q = x; return this; }
        N vib(double x) { vib = x; return this; }
        N at(double x) { at = x; return this; }
        N dest(int x) { dest = x; return this; }
        N echo(boolean x) { echo = x; return this; }
        N filter(int type, double f, double qq) { ftype = type; lp = f; q = qq; return this; }
        N sweep(double to, double secs) { f1 = to; sweep = secs; return this; }

        void play(double midi) { start(mf(midi)); }

        void start(double hz) {
            if (voices.size() >= 220) return;
            Voice vo = new Voice();
            vo.delay = (int) Math.round(curOff + at * SR);
            vo.aN = Math.max(1, (int) (a * SR));
            vo.hN = Math.max(0, (int) (h * SR));
            vo.rN = Math.max(1, (int) (r * SR));
            vo.total = vo.aN + vo.hN + vo.rN;
            vo.peak = Math.max(2e-4, v);
            vo.env = 1e-4;
            vo.mulA = Math.pow(vo.peak / 1e-4, 1.0 / vo.aN);
            vo.mulR = Math.pow(1e-4 / vo.peak, 1.0 / vo.rN);
            vo.wave = w;
            vo.dest = dest;
            vo.echo = echo;
            vo.ph = new double[det.length];
            vo.inc = new double[det.length];
            for (int k = 0; k < det.length; k++) vo.inc[k] = hz * Math.pow(2, det[k] / 1200) / SR;
            if (vib != 0) { vo.vibCents = vib; vo.vibInc = 2 * Math.PI * 5.5 / SR; }
            if (f1 > 0 && sweep > 0) { vo.sweepN = Math.max(1, (int) (sweep * SR)); vo.sweepMul = Math.pow(f1 / hz, 1.0 / vo.sweepN); }
            if (lp > 0) {
                vo.flt = new Biquad();
                vo.ftype = ftype;
                vo.fq = ftype == BP ? q : Math.pow(10, q / 20); // lowpass/highpass Q in dB, like Web Audio
                vo.fCur = lp;
                vo.flt.set(ftype, lp, vo.fq);
                vo.fMul32 = lpEnd > 0 ? Math.pow(lpEnd / lp, 32.0 / vo.total) : 1;
            }
            voices.add(vo);
        }
    }

    N n() { return new N(); }

    /** Pitch-dropping thump: kicks, bass drums, toms. */
    void boom(double v, double f0, double f1, double d, int wave) {
        n().w(wave).v(v).a(.002).r(d).dest(DRUM).sweep(f1, Math.min(.14, d * .4)).start(f0);
    }

    /** Filtered noise burst: hats, snares, shakers, cymbals, vinyl clicks. */
    void hit(double v, double f, int type, double q, double d, int dest) { hitAt(0, v, f, type, q, d, dest); }

    void hitAt(double at, double v, double f, int type, double q, double d, int dest) {
        n().w(NOISE).v(v).a(.001).r(d).filter(type, f, q).dest(dest).at(at).start(1);
    }

    private static final int[] ARP = {0, 1, 2, 3, 4, 5, 4, 3, 2, 1};

    int arp(int[] ch) {
        int i = ARP[arpI++ % ARP.length];
        return i < 3 ? ch[i] + 12 : ch[i - 3] + 24;
    }

    // two original one-bar tunes: step -> chord tone (0-2) or octave (3); -1 = rest
    private static final int[][] MELODY = {
            {0, -1, 1, 2, 3, -1, -1, -1, 2, -1, 1, -1, 0, -1, -1, -1},
            {2, -1, -1, -1, 1, -1, 2, -1, 3, -1, -1, 2, 1, -1, 0, -1}};

    static int melNote(int[] ch, int s, int b) {
        int i = MELODY[b % 2][s];
        return i < 0 ? -1 : i == 3 ? ch[0] + 12 : ch[i];
    }

    // ================= effects =================

    static final class Biquad {
        double b0 = 1, b1, b2, a1, a2, x1, x2, y1, y2, f = -1, q = -1;
        int t = -1;

        void set(int type, double freq, double Q) {
            freq = clamp(freq, 20, SR * .45);
            if (type == t && Math.abs(freq - f) < .5 && Q == q) return;
            t = type; f = freq; q = Q;
            double w = 2 * Math.PI * freq / SR, cs = Math.cos(w), al = Math.sin(w) / (2 * Q), a0 = 1 + al;
            switch (type) {
                case LP: b0 = (1 - cs) / 2; b1 = 1 - cs; b2 = b0; break;
                case HP: b0 = (1 + cs) / 2; b1 = -(1 + cs); b2 = b0; break;
                default: b0 = al; b1 = 0; b2 = -al;
            }
            a1 = -2 * cs; a2 = 1 - al;
            b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0;
        }

        double process(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2 + 1e-20;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            return y;
        }
    }

    static final class Comb {
        final double[] buf; int i; double store;
        Comb(int n) { buf = new double[n]; }
        double process(double x) {
            double y = buf[i];
            store = y * .75 + store * .25;
            buf[i] = x + store * .85 + 1e-20;
            if (++i == buf.length) i = 0;
            return y;
        }
    }

    static final class Allpass {
        final double[] buf; int i;
        Allpass(int n) { buf = new double[n]; }
        double process(double x) {
            double b = buf[i];
            buf[i] = x + b * .5;
            if (++i == buf.length) i = 0;
            return b - x;
        }
    }

    /** Small stereo Freeverb-style room. */
    static final class Reverb {
        final Comb[] cl = new Comb[6], cr = new Comb[6];
        final Allpass[] al = new Allpass[3], ar = new Allpass[3];
        double l, r;

        Reverb() {
            int[] cs = {1116, 1188, 1277, 1356, 1422, 1491}, as = {556, 441, 341};
            double sc = SR / 44100.0;
            for (int i = 0; i < 6; i++) { cl[i] = new Comb((int) (cs[i] * sc)); cr[i] = new Comb((int) ((cs[i] + 23) * sc)); }
            for (int i = 0; i < 3; i++) { al[i] = new Allpass((int) (as[i] * sc)); ar[i] = new Allpass((int) ((as[i] + 23) * sc)); }
        }

        void process(double in) {
            double x = in * .05, a = 0, b = 0;
            for (Comb c : cl) a += c.process(x);
            for (Comb c : cr) b += c.process(x);
            for (Allpass p : al) a = p.process(a);
            for (Allpass p : ar) b = p.process(b);
            l = a; r = b;
        }
    }

    // ================= presets =================

    public abstract static class Preset {
        public final String name, desc;
        public final String[] layers;
        final double bpmLo, bpmHi, cutLo, cutHi, swing;
        final double[] layerAt;
        final int[][] ch;
        final int[] rt;

        Preset(String name, String desc, double bpmLo, double bpmHi, double cutLo, double cutHi, double swing,
               int[][] ch, int[] rt, String[] layers, double[] layerAt) {
            this.name = name; this.desc = desc; this.bpmLo = bpmLo; this.bpmHi = bpmHi; this.cutLo = cutLo; this.cutHi = cutHi;
            this.swing = swing; this.ch = ch; this.rt = rt; this.layers = layers; this.layerAt = layerAt;
        }

        /** Play one 16th-note step. s = step 0-15, b = bar number, e = energy 0-1, sd = step length in seconds. */
        abstract void step(Synth S, int s, int b, double e, double sd);
    }

    public static final Preset[] PRESETS = {
        new Preset("Synthwave", "Pads, arps, four-on-the-floor", 66, 138, 500, 8000, 0,
                new int[][]{{57, 60, 64}, {53, 57, 60}, {52, 55, 60}, {50, 55, 59}}, new int[]{45, 41, 48, 43},
                new String[]{"Pad", "Arp", "Bass", "Kick", "Hats", "Snare", "Sparkle"},
                new double[]{0, 0, .15, .2, .3, .4, .75}) {
            void step(Synth S, int s, int b, double e, double sd) {
                int[] c = ch[b % 4];
                int r = rt[b % 4];
                if (s == 0) for (int m : c) S.n().w(SAW).v(.035).a(.7).h(Math.max(.05, sd * 16 - .7)).r(.9).det(-8, 8).lp(1100).play(m);
                if (e < .15) { if (s % 4 == 0) S.n().v(.07).r(1.4).play(S.arp(c)); }
                else if (e < .5) { if (s % 2 == 0) S.n().w(TRI).v(.06).r(.4).play(S.arp(c)); }
                else S.n().w(SAW).v(.035).r(.2).play(S.arp(c));
                if (e > .75 && s % 3 == 0) S.n().v(.03).r(.35).play(c[(s + b) % 3] + 36);
                if (e >= .7) { if (s % 2 == 0) bass(S, s % 4 == 2 ? r + 12 : r, sd * 1.6, e); }
                else if (e >= .45) { if (s == 0 || s == 3 || s == 6 || s == 8 || s == 11 || s == 14) bass(S, r, sd * 1.8, e); }
                else if (e >= .15) { if (s == 0 || s == 8) bass(S, r, sd * 7, e); }
                if (e >= .2 && (s == 0 || s == 8 || (e >= .5 && (s == 4 || s == 12)) || (e > .8 && s == 14))) S.boom(.9, 150, 42, .38, SINE);
                if (e >= .4 && (s == 4 || s == 12)) snare(S, 1);
                if (S.boost > .45 && e >= .3 && s >= 13) snare(S, .35 + .2 * (s - 13));
                if (e >= .3) {
                    if (e >= .65) S.hit(s % 2 == 1 ? .055 : .1, 7500, HP, 1, s == 14 && e > .8 ? .22 : .045, DRUM);
                    else if (s % 2 == 0) S.hit(s % 4 == 2 ? .1 : .055, 7500, HP, 1, .045, DRUM);
                }
            }
            void bass(Synth S, int m, double d, double e) {
                S.n().w(SAW).v(.16).a(.01).h(Math.max(0, d - .08)).r(.08).lp(900 + 1500 * e).lpEnd(180).q(4).dest(FILT).play(m);
            }
            void snare(Synth S, double v) {
                S.hit(.32 * v, 1400, HP, 1, .16, DRUM);
                S.boom(.25 * v, 190, 180, .08, TRI);
            }
        },
        new Preset("Marching band", "Instruments join one by one", 72, 132, 1500, 14000, 0,
                new int[][]{{60, 64, 67}, {57, 60, 64}, {53, 57, 60}, {55, 59, 62}}, new int[]{36, 33, 41, 43},
                new String[]{"Tuba", "Glockenspiel", "Snare drum", "Bass drum", "Horns", "Trumpet", "Piccolo", "Cymbals"},
                new double[]{0, 0, .15, .25, .4, .6, .78, .9}) {
            void step(Synth S, int s, int b, double e, double sd) {
                int[] c = ch[b % 4];
                int r = rt[b % 4];
                // tuba "oom", horns "pah"
                if (e < .6) {
                    if (s == 0) tuba(S, r, sd);
                    if (s == 8) tuba(S, r + 7, sd);
                    if (e >= .4 && (s == 4 || s == 12)) horns(S, c, sd);
                } else {
                    if (s % 4 == 0) tuba(S, s % 8 != 0 ? r + 7 : r, sd);
                    if (s % 4 == 2) horns(S, c, sd);
                }
                // glockenspiel ticks along, then doubles the tune once the trumpet is in
                int mn = melNote(c, s, b);
                if (e >= .6) { if (mn >= 0) glock(S, mn); }
                else if (s % (e < .3 ? 8 : 4) == 0) glock(S, c[(s / 4 + b) % 3]);
                // marching snare: accents, then the full drum-line figure, then rolls
                if (e >= .15) {
                    if (s == 4 || s == 12) sn(S, 1);
                    else if (e >= .35 && (s == 0 || s == 2 || s == 3 || s == 6 || s == 8 || s == 10 || s == 11 || s == 14)) sn(S, .45);
                    if (e >= .7 && s >= 14) { sn(S, .4); S.hitAt(sd / 2, .14, 2600, BP, .7, .1, DRUM); }
                }
                if (e >= .25 && (s == 0 || s == 8 || (e >= .55 && (s == 4 || s == 12)))) S.boom(.8, 95, 52, .55, SINE);
                if (e >= .6 && mn >= 0) S.n().w(SAW).v(.05).a(.02).h(sd * 1.4).r(.1).det(0, 6).lp(3000).vib(12).play(mn + 12);
                if (e >= .78) {
                    if (s % 4 == 2) S.n().w(TRI).v(.035).a(.01).h(sd * .8).r(.08).vib(20).play(c[(s / 2 + b) % 3] + 36);
                    if (s >= 14) {
                        S.n().w(TRI).v(.03).h(sd * .3).r(.04).play(c[0] + 36);
                        S.n().w(TRI).v(.03).h(sd * .3).r(.04).at(sd / 2).play(c[0] + 38);
                    }
                }
                if (s == 0 && (e >= .9 || S.boost > .55)) S.hit(.16, 5000, HP, 1, 1.3, DRUM);
            }
            void tuba(Synth S, int m, double sd) {
                S.n().w(SAW).v(.15).a(.02).h(sd * 1.6).r(.15).lp(520).lpEnd(260).q(2).dest(FILT).play(m);
            }
            void horns(Synth S, int[] c, double sd) {
                for (int m : c) S.n().w(SAW).v(.04).a(.012).h(sd * .7).r(.12).det(-6, 6).lp(2400).lpEnd(900).play(m);
            }
            void glock(Synth S, int m) {
                S.n().v(.06).r(1.1).play(m + 24);
                S.n().w(TRI).v(.012).r(.3).play(m + 36);
            }
            void sn(Synth S, double v) {
                S.hit(.3 * v, 2600, BP, .7, .12, DRUM);
                S.boom(.18 * v, 210, 190, .06, TRI);
            }
        },
        new Preset("8-bit", "Pulse waves and noise drums", 80, 160, 2500, 16000, 0,
                new int[][]{{64, 67, 71}, {60, 64, 67}, {67, 71, 74}, {62, 66, 69}}, new int[]{40, 36, 43, 38},
                new String[]{"Triangle bass", "Pulse arp", "Kick", "Noise hats", "Noise snare", "Pulse lead", "Echo"},
                new double[]{0, 0, .2, .3, .4, .6, .8}) {
            void step(Synth S, int s, int b, double e, double sd) {
                int[] c = ch[b % 4];
                int r = rt[b % 4];
                if (e < .2) { if (s == 0 || s == 8) S.n().w(TRI).v(.15).h(sd * 6).r(.1).dest(FILT).play(r); }
                else if (s % 2 == 0) S.n().w(TRI).v(.22).h(sd * .9).r(.03).dest(FILT).play(s % 4 == 2 ? r + 12 : r);
                if (e < .3) { if (s % 4 == 0) S.n().w(PULSE12).v(.035).r(.3).play(S.arp(c)); }
                else if (e < .55) { if (s % 2 == 0) S.n().w(PULSE12).v(.035).h(sd * .5).r(.05).play(S.arp(c)); }
                else S.n().w(PULSE12).v(.03).h(sd * .4).r(.03).play(S.arp(c));
                if (e >= .2 && (s == 0 || s == 8 || (e >= .5 && (s == 4 || s == 12)))) S.boom(.6, 180, 50, .15, TRI);
                if (e >= .3 && (e >= .65 || s % 2 == 0)) S.hit(s % 4 == 2 ? .07 : .045, 9000, HP, 1, .03, DRUM);
                if (e >= .4 && (s == 4 || s == 12)) S.hit(.3, 1500, HP, 1, .12, DRUM);
                if (S.boost > .45 && e >= .3 && s >= 12) S.hit(.12 + .04 * (s - 12), 1500, HP, 1, .06, DRUM);
                int mn = melNote(c, s, b);
                if (e >= .6 && mn >= 0) S.n().w(PULSE25).v(.045).h(sd * 1.5).r(.05).vib(15).echo(e >= .8).play(mn + 12);
            }
        },
        new Preset("Lo-fi", "Warm keys, lazy swing, vinyl", 62, 96, 900, 9000, .18,
                new int[][]{{53, 57, 60, 64}, {53, 59, 64, 69}, {52, 55, 59, 62}, {55, 59, 60, 64}}, new int[]{38, 43, 36, 45},
                new String[]{"Keys", "Vinyl", "Sub bass", "Kick", "Shaker", "Rim", "Bells"},
                new double[]{0, 0, .15, .2, .3, .38, .7}) {
            void step(Synth S, int s, int b, double e, double sd) {
                int[] c = ch[b % 4];
                int r = rt[b % 4];
                if (s == 0) keys(S, c, 1);
                if (e < .4 && s == 8) keys(S, c, .55);
                if (e >= .4 && (s == 6 || s == 10)) keys(S, c, .6);
                if (s % 4 == 0) S.hitAt(0, .012, 3500, HP, 1, sd * 4.2, MEL); // soft record hiss
                for (int i = 0; i < 2; i++) if (Math.random() < .35) S.hitAt(Math.random() * sd, .02 + Math.random() * .03, 2500, HP, 1, .004, MEL);
                if (e >= .15) {
                    if (s == 0) S.n().v(.24).a(.01).h(sd * 6).r(.2).dest(FILT).play(r);
                    if (s == 10) S.n().v(.2).a(.01).h(sd * 4).r(.2).dest(FILT).play(r + (b % 2 == 1 ? 7 : 0));
                }
                if (e >= .2 && (s == 0 || s == 10 || (e >= .5 && s == 7))) S.boom(.7, 110, 45, .3, SINE);
                if (e >= .3 && (e >= .6 || s % 2 == 0)) S.hit(s % 4 == 2 ? .05 : .03, 6000, HP, 1, .05, DRUM);
                if (e >= .38 && (s == 4 || s == 12)) { S.boom(.2, 820, 760, .03, TRI); S.hit(.12, 3000, HP, 1, .02, DRUM); }
                if (e >= .7 && s % 8 == 4) S.n().v(.03).r(1).play(c[(s / 4 + b) % c.length] + 24);
            }
            void keys(Synth S, int[] c, double v) {
                for (int i = 0; i < c.length; i++) {
                    S.n().v(.028 * v).a(.008).r(3).at(i * .014).play(c[i]);
                    S.n().v(.01 * v).r(.35).at(i * .014).play(c[i] + 12);
                }
            }
        }
    };
}
