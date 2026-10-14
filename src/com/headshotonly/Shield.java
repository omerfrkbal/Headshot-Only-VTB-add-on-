package com.headshotonly;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import vtb.api.ImpactEvents;
import vtb.api.ImpactEvents.Impact;

/**
 * The headshot-only logic. Runs around Hit(), and only for VTB bullets
 * (ImpactEvents.current() is null for everything else, so melee and other damage are ignored).
 *
 * Before the hit we change the zombie's health:
 *   head hit:        set to almost zero, so any damage kills it
 *   body/limb hit:   set to a huge value, so the hit cannot kill it
 * After the hit we work out how much damage was dealt. A body hit puts the health back
 * to (old health - damage), but never below FLOOR. So the zombie gets weaker, just not dead.
 */
public final class Shield {
    // VTB hit location ids (Impact.ragdollPart)
    static final int HEAD = 2;
    // The spine volume is thick and reaches up near the skull, so a shot to the back of the
    // neck is reported as SPINE, not HEAD. We re-check those hits ourselves (see enter()).
    static final int SPINE = 1;
    /** Fallback only: used when bone data can't be read. A spine hit this many metres above the feet counts as head. */
    static volatile double napeMin = 1.50;
    /** Main setting: a spine hit this close (metres) to the neck-to-head bone line counts as a headshot. */
    static volatile double napeRadius = 0.16;
    static volatile boolean debugHitboxes = false;
    static volatile boolean verbose = false;   // log every hit to console.txt (errors always print)
    private static long cfgChecked, cfgStamp;
    private static java.io.File cfgFile;

    /** Re-reads Zomboid/HeadshotOnly.properties if it changed (checked at most every 2 seconds). */
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
                    w.write("# fallback only, used when the zombie's bones can't be read: a spine hit this many metres above the feet counts as a headshot\n");
                    w.write("nape_min=1.50\n");
                    w.write("# a spine hit within this many metres of the neck-to-head bone line counts as a headshot (follows the animation)\n");
                    w.write("# raise it if back-of-head shots miss, lower it if shoulder shots kill\n");
                    w.write("nape_radius=0.16\n");
                    w.write("# true = draw VTB's hitboxes in game\n");
                    w.write("debug_hitboxes=false\n");
                    w.write("# true = log every hit to console.txt, handy for tuning nape_radius\n");
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
    static final double LEVEL_M = 2.4494896;   // metres per floor level in VTB (z is counted in levels)
    /** The zombie that last took a head hit, so HeadshotListener can finish it if needed. */
    static volatile Object lastHead;
    static final float SHIELD = 5000f;   // health during a body hit. Kept modest: very large floats lose precision
    static final float FRAIL = 0.0001f;  // health during a head hit
    static final float FLOOR = 0.05f;    // body hits never push health below this (5%)

    /** Per-zombie bookkeeping for one hit. depth counts nested Hit() calls (both hook classes can fire). */
    private static final class State { int depth; float hp; boolean head; }
    private static final IdentityHashMap<Object, State> ACTIVE = new IdentityHashMap<>();
    private static Method getH, setH;
    private static Class<?> bound;
    private static int logged;

    /** Debug output, only when verbose=true. */
    static void log(String s) {
        if (verbose) System.out.println("[HeadshotOnly] " + s);
    }

    /** Always printed. */
    static void err(String s) { System.out.println("[HeadshotOnly] " + s); }

    /** Called before Hit(): decide head or body, then set the zombie's health to match. */
    public static void enter(Object self) {
        try {
            Impact cur = ImpactEvents.current();
            if (cur == null || cur.target != self) return;   // not a VTB bullet aimed at this zombie
            if (!bind(self)) return;
            State st = ACTIVE.get(self);
            if (st != null) { st.depth++; return; }   // nested call, keep the first decision
            st = new State();
            st.depth = 1;
            st.hp = (Float) getH.invoke(self);
            st.head = cur.ragdollPart == HEAD;
            // Spine hits near the neck are really back-of-head shots, so measure how far the hit is from the neck-to-head line
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

    /** Called after Hit(): restore the health of a body hit (head hits are left to kill). */
    public static void exit(Object self) {
        try {
            State st = ACTIVE.get(self);
            if (st == null) return;
            if (--st.depth > 0) return;   // still inside a nested call
            ACTIVE.remove(self);
            if (st.head) { if (verbose) log("HEAD applied, hp now " + getH.invoke(self)); return; }
            float after = (Float) getH.invoke(self);
            float dmg = Math.max(0f, SHIELD - after);   // damage dealt = how far the shield value dropped
            float nh = Math.max(st.hp - dmg, FLOOR);
            if (st.hp < FLOOR) nh = st.hp;   // was already under the floor, don't raise it
            setH.invoke(self, nh);
            if (verbose) log("BODY applied: dmg=" + dmg + " hp " + st.hp + " -> " + nh);
        } catch (Throwable t) {
            err("exit error: " + t);
        }
    }

    // Skeleton lookup. It uses the same game calls VTB uses to build its hitboxes, found through
    // reflection because they aren't available when this jar is compiled.

    private static Method mBoneIdx, mBoneWorld, mGetAP;
    private static Class<?> cVec;
    private static boolean boneApiBroken;

    /** Distance in metres from the hit point to the neck-to-head line, or -1 if the bones can't be read. */
    private static double boneDistance(Object ch, Impact cur) {
        if (boneApiBroken) return -1;
        try {
            if (mGetAP == null) {
                mGetAP = ch.getClass().getMethod("getAnimationPlayer");
                Class<?> model = Class.forName("zombie.core.skinnedmodel.model.Model");
                cVec = Class.forName("zombie.iso.Vector3");
                // boneToWorldCoords has several overloads, pick the (character, int, Vector3) one
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
            if (b == null) {   // no head-tip bone: extend the neck-to-head direction 0.20 m to reach the top of the skull
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

    // The "why we fell back to the height check" message is printed only once
    private static boolean onceDone;
    private static void once(String why) { if (!onceDone) { onceDone = true; System.out.println("[HeadshotOnly] bones fallback reason: " + why); } }

    /** World position of a bone as {x, y, height}, all in metres, or null if the bone doesn't exist. */
    private static double[] bonePos(Object ch, Object ap, String name) throws Exception {
        int idx = (Integer) mBoneIdx.invoke(ap, name, -1);
        if (idx < 0) return null;
        Object v = cVec.getConstructor().newInstance();
        mBoneWorld.invoke(null, ch, idx, v);
        double x = cVec.getField("x").getFloat(v), y = cVec.getField("y").getFloat(v), z = cVec.getField("z").getFloat(v);
        return new double[] { x, y, z * LEVEL_M };   // x and y: 1 tile = 1 m. z is in levels, so convert to metres
    }

    /** Shortest distance from point p to the line segment a-b. */
    private static double segDist(double px, double py, double pu, double[] a, double[] b) {
        double abx = b[0] - a[0], aby = b[1] - a[1], abu = b[2] - a[2];
        double apx = px - a[0], apy = py - a[1], apu = pu - a[2];
        double len2 = abx * abx + aby * aby + abu * abu;
        double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, (apx * abx + apy * aby + apu * abu) / len2));
        double dx = px - (a[0] + abx * t), dy = py - (a[1] + aby * t), du = pu - (a[2] + abu * t);
        return Math.sqrt(dx * dx + dy * dy + du * du);
    }

    /** Looks up getHealth/setHealth once per class. Reflection, because the game's classes aren't on our compile path. */
    private static boolean bind(Object t) throws Exception {
        Class<?> c = t.getClass();
        if (c == bound) return true;
        getH = c.getMethod("getHealth");
        setH = c.getMethod("setHealth", float.class);
        bound = c;
        return true;
    }
}
