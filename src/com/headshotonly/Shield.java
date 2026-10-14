package com.headshotonly;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import vtb.api.ImpactEvents;
import vtb.api.ImpactEvents.Impact;

/**
 * Pre/post hook around Hit() for VTB bullets only (ImpactEvents.current() != null).
 * Head: health -> tiny so any damage kills. Body/limb: health -> huge so it cannot die,
 * afterwards health = max(oldHealth - damage, FLOOR).
 */
public final class Shield {
    static final int HEAD = 2;           // VTB hit location id: 2 = head
    static final int SPINE = 1;          // VTB hit location id: 1 = spine capsule (r=0.19, top 1.61 m)
    /** A spine hit at/above this height over the zombie's feet counts as head (nape / skull base). */
    static volatile double napeMin = 1.50;
    static volatile double napeRadius = 0.16;
    static volatile boolean debugHitboxes = false;
    static volatile boolean verbose = false;   // per-hit log lines; off by default (errors/config always print)
    private static long cfgChecked, cfgStamp;
    private static java.io.File cfgFile;

    /** Tunable without rebuilding: Zomboid/HeadshotOnly.properties  ->  nape_min=1.50 (metres above feet). */
    static void poll() { loadConfig(); }

    private static void loadConfig() {
        long now = System.currentTimeMillis();
        if (now - cfgChecked < 2000) return;
        cfgChecked = now;
        try {
            if (cfgFile == null)
                cfgFile = new java.io.File(System.getProperty("user.home"), "Zomboid" + java.io.File.separator + "HeadshotOnly.properties");
            if (!cfgFile.exists()) {
                try (java.io.FileWriter w = new java.io.FileWriter(cfgFile)) {
                    w.write("# metres above the zombie's feet; a spine hit at or above this counts as a headshot\n");
                    w.write("# spine capsule top is 1.61 m, head capsule starts at 1.43 m. Lower = easier nape shots, but shoulders start counting\n");
                    w.write("nape_min=1.50\n");
                    w.write("# a spine hit within this many metres of the neck->head line counts as a headshot (follows the animation)\n");
                    w.write("nape_radius=0.16\n");
                    w.write("# true = draw VTB's hitbox volumes in game (VTB debug view)\n");
                    w.write("debug_hitboxes=false\n");
                    w.write("# true = log every hit (HEAD-check distances etc.) to console.txt, for tuning\n");
                    w.write("verbose=false\n");
                }
            }
            if (cfgFile.lastModified() == cfgStamp) return;
            cfgStamp = cfgFile.lastModified();
            java.util.Properties pr = new java.util.Properties();
            try (java.io.FileReader r = new java.io.FileReader(cfgFile)) { pr.load(r); }
            napeMin = Double.parseDouble(pr.getProperty("nape_min", "1.50").trim());
            napeRadius = Double.parseDouble(pr.getProperty("nape_radius", "0.16").trim());
            debugHitboxes = Boolean.parseBoolean(pr.getProperty("debug_hitboxes", "false").trim());
            vtb.config.BallisticsConfig.debugHitboxes = debugHitboxes;
            verbose = Boolean.parseBoolean(pr.getProperty("verbose", "false").trim());
            System.out.println("[HeadshotOnly] config: nape_min=" + napeMin + " nape_radius=" + napeRadius + " debug_hitboxes=" + debugHitboxes + " verbose=" + verbose);
        } catch (Throwable t) {
            System.out.println("[HeadshotOnly] config error: " + t);
        }
    }
    static final double LEVEL_M = 2.4494896; // VTB: metres per floor level
    static volatile Object lastHead;
    static final float SHIELD = 5000f;
    static final float FRAIL = 0.0001f;
    static final float FLOOR = 0.05f;    // body hits can never push health below this

    private static final class State { int depth; float hp; boolean head; }
    private static final IdentityHashMap<Object, State> ACTIVE = new IdentityHashMap<>();
    private static Method getH, setH;
    private static Class<?> bound;
    private static int logged;

    /** Per-hit tuning output; only when verbose=true in HeadshotOnly.properties. */
    static void log(String s) {
        if (verbose) System.out.println("[HeadshotOnly] " + s);
    }

    /** Errors always print. */
    static void err(String s) { System.out.println("[HeadshotOnly] " + s); }

    public static void enter(Object self) {
        try {
            Impact cur = ImpactEvents.current();
            if (cur == null || cur.target != self) return;
            if (!bind(self)) return;
            State st = ACTIVE.get(self);
            if (st != null) { st.depth++; return; }
            st = new State();
            st.depth = 1;
            st.hp = (Float) getH.invoke(self);
            st.head = cur.ragdollPart == HEAD;
            if (!st.head && cur.ragdollPart == SPINE) {
                double d = boneDistance(self, cur);
                if (d >= 0) {
                    if (verbose) log("HEAD-check spine hit dist to neck-head line=" + String.format("%.2f", d) + " m (head if <= " + napeRadius + ")");
                    if (d <= napeRadius) st.head = true;
                } else {
                    double feet = ((Number) self.getClass().getMethod("getZ").invoke(self)).doubleValue() * LEVEL_M;
                    double rel = cur.hitU - feet;
                    if (verbose) log("HEAD-check spine hit height=" + String.format("%.2f", rel) + " m (bones unavailable; head if >= " + napeMin + ")");
                    if (rel >= napeMin) st.head = true;
                }
            }
            if (st.head) lastHead = self;
            ACTIVE.put(self, st);
            setH.invoke(self, st.head ? FRAIL : SHIELD);
            if (verbose) log((st.head ? "HEAD" : "BODY(part=" + cur.ragdollPart + ")") + " hit: hp was " + st.hp);
        } catch (Throwable t) {
            err("enter error: " + t);
        }
    }

    public static void exit(Object self) {
        try {
            State st = ACTIVE.get(self);
            if (st == null) return;
            if (--st.depth > 0) return;
            ACTIVE.remove(self);
            if (st.head) { if (verbose) log("HEAD applied, hp now " + getH.invoke(self)); return; }
            float after = (Float) getH.invoke(self);
            float dmg = Math.max(0f, SHIELD - after);
            float nh = Math.max(st.hp - dmg, FLOOR);
            if (st.hp < FLOOR) nh = st.hp;           // already below floor: leave as is
            setH.invoke(self, nh);
            if (verbose) log("BODY applied: dmg=" + dmg + " hp " + st.hp + " -> " + nh);
        } catch (Throwable t) {
            err("exit error: " + t);
        }
    }


    // ---- skeleton lookup (same API VTB uses for its hitboxes) ----
    private static Method mBoneIdx, mBoneWorld, mGetAP;
    private static Class<?> cVec;
    private static boolean boneApiBroken;

    /** Distance (m) from hit point to the neck->head-nub segment, or -1 if bones can't be read. */
    private static double boneDistance(Object ch, Impact cur) {
        if (boneApiBroken) return -1;
        try {
            if (mGetAP == null) {
                mGetAP = ch.getClass().getMethod("getAnimationPlayer");
                Class<?> model = Class.forName("zombie.core.skinnedmodel.model.Model");
                cVec = Class.forName("zombie.iso.Vector3");
                // several overloads exist: pick (IsoGameCharacter-ish, int, Vector3)
                for (Method m : model.getMethods()) {
                    if (!m.getName().equals("boneToWorldCoords") || m.getParameterCount() != 3) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt[0].isInstance(ch) && pt[1] == int.class && pt[2] == cVec) { mBoneWorld = m; break; }
                }
                if (mBoneWorld == null) throw new NoSuchMethodException("Model.boneToWorldCoords(character,int,Vector3)");
            }
            Object ap = mGetAP.invoke(ch);
            if (ap == null) { once("no AnimationPlayer for this zombie"); return -1; }
            if (mBoneIdx == null) mBoneIdx = ap.getClass().getMethod("getSkinningBoneIndex", String.class, int.class);
            try {
                if (!(Boolean) ap.getClass().getMethod("isReady").invoke(ap)) { once("AnimationPlayer not ready"); return -1; }
            } catch (NoSuchMethodException ignored) { }
            double[] neck = bonePos(ch, ap, "Bip01_Neck");
            double[] head = bonePos(ch, ap, "Bip01_Head");
            if (neck == null || head == null) { once("bones missing: neck=" + (neck != null) + " head=" + (head != null)); return -1; }
            double[] a = neck;
            double[] b = bonePos(ch, ap, "Bip01_HeadNub");
            if (b == null) {            // no nub bone: extend neck->head by 0.20 m to reach the skull top
                double dx = head[0] - neck[0], dy = head[1] - neck[1], du = head[2] - neck[2];
                double l = Math.sqrt(dx * dx + dy * dy + du * du);
                if (l < 1e-6) { once("neck and head bones coincide"); return -1; }
                b = new double[] { head[0] + dx / l * 0.20, head[1] + dy / l * 0.20, head[2] + du / l * 0.20 };
            }
            return segDist(cur.hitX, cur.hitY, cur.hitU, a, b);
        } catch (Throwable t) {
            boneApiBroken = true;
            StackTraceElement[] st = t.getStackTrace();
            Throwable c = t.getCause() != null ? t.getCause() : t;
            err("bone lookup unavailable, using height fallback: " + t + (st.length > 0 ? " at " + st[0] : "") + (c != t ? " cause=" + c : ""));
            return -1;
        }
    }

    private static boolean onceDone;
    private static void once(String why) { if (!onceDone) { onceDone = true; System.out.println("[HeadshotOnly] bones fallback reason: " + why); } }

    private static double[] bonePos(Object ch, Object ap, String name) throws Exception {
        int idx = (Integer) mBoneIdx.invoke(ap, name, -1);
        if (idx < 0) return null;
        Object v = cVec.getConstructor().newInstance();
        mBoneWorld.invoke(null, ch, idx, v);
        double x = cVec.getField("x").getFloat(v), y = cVec.getField("y").getFloat(v), z = cVec.getField("z").getFloat(v);
        return new double[] { x, y, z * LEVEL_M };   // tiles == metres in VTB; z is in levels
    }

    private static double segDist(double px, double py, double pu, double[] a, double[] b) {
        double abx = b[0] - a[0], aby = b[1] - a[1], abu = b[2] - a[2];
        double apx = px - a[0], apy = py - a[1], apu = pu - a[2];
        double len2 = abx * abx + aby * aby + abu * abu;
        double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, (apx * abx + apy * aby + apu * abu) / len2));
        double dx = px - (a[0] + abx * t), dy = py - (a[1] + aby * t), du = pu - (a[2] + abu * t);
        return Math.sqrt(dx * dx + dy * dy + du * du);
    }

    private static boolean bind(Object t) throws Exception {
        Class<?> c = t.getClass();
        if (c == bound) return true;
        getH = c.getMethod("getHealth");
        setH = c.getMethod("setHealth", float.class);
        bound = c;
        return true;
    }
}
