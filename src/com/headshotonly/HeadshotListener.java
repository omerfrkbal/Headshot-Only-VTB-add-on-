package com.headshotonly;

import java.lang.reflect.Method;
import vtb.api.ImpactEvents;
import vtb.api.ImpactEvents.Impact;

/**
 * Backup for headshots. Shield already makes a head hit lethal by dropping the zombie's
 * health to almost nothing before the hit. If a head hit still leaves the zombie alive
 * for any reason, this kills it after VTB reports the impact.
 * VTB only calls listeners after the damage is applied, so this can't stop a body hit.
 */
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
