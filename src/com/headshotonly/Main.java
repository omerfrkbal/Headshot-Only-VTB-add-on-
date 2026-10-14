package com.headshotonly;

/**
 * Entry point, called by ZombieBuddy when the jar loads.
 *
 * This class must not mention any VTB class. If a class references something that isn't
 * loaded yet, the JVM fails the whole class and ZombieBuddy swallows the error, so the mod
 * would silently do nothing. All VTB access lives in Hook, and we just retry until it works.
 */
public final class Main {
    public static void main(String[] args) {
        System.out.println("[HeadshotOnly] Main.main called (ZombieBuddy loaded this jar).");
        if (tryHook()) return;
        // VTB may not be loaded yet: retry every 2 seconds for up to 2 minutes
        Thread t = new Thread(() -> {
            for (int i = 0; i < 60; i++) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                if (tryHook()) return;
            }
            System.out.println("[HeadshotOnly] gave up: VTB ImpactEvents never became available.");
        }, "HeadshotOnly-hook");
        t.setDaemon(true);
        t.start();
    }

    private static boolean tryHook() {
        try {
            Hook.install();
            System.out.println("[HeadshotOnly] listener registered on VTB ImpactEvents.");
            return true;
        } catch (Throwable t) {
            System.out.println("[HeadshotOnly] hook not ready yet: " + t);
            return false;
        }
    }
}
