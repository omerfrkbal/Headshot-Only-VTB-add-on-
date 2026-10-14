package com.headshotonly;

import java.lang.reflect.Method;
import vtb.api.ImpactEvents;
import vtb.api.ImpactEvents.Impact;

/** Safety net: a head hit that left the zombie alive gets killed (runs after damage). */
final class HeadshotListener implements ImpactEvents.Listener {
    private Method isDead;
    @Override
    public void onConfirmedBallisticImpact(Impact imp) {
        if (imp.phase != ImpactEvents.PHASE_APPLIED) return;
        Object t = imp.target;
        if (t == null || !t.getClass().getName().equals("zombie.characters.IsoZombie")) return;
        if (imp.ragdollPart != Shield.HEAD && t != Shield.lastHead) return;
        try {
            if (isDead == null) isDead = t.getClass().getMethod("isDead");
            boolean dead = (Boolean) isDead.invoke(t);
            if (Shield.verbose) Shield.log("impact APPLIED part=" + imp.ragdollPart + " dead=" + dead);
            if (dead) return;
            for (Method m : t.getClass().getMethods())
                if (m.getName().equals("Kill") && m.getParameterCount() == 1) {
                    m.invoke(t, imp.shooter);
                    Shield.log("forced Kill()");
                    return;
                }
        } catch (Throwable e) {
            Shield.err("listener error: " + e);
        }
    }
}
