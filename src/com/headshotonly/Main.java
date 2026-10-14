package com.headshotonly;

/** Entry point. Deliberately has NO direct reference to VTB classes so it always loads. */
public final class Main {
    public static void main(String[] args) {
        System.out.println("[HeadshotOnly] Main.main called (ZombieBuddy loaded this jar).");
        if (tryHook()) return;
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
