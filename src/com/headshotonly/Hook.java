package com.headshotonly;

/** The only place that touches VTB classes. Main calls it inside a try/catch so a missing VTB can't break loading. */
final class Hook {
    static void install() {
        Shield.poll();   // read (or create) the config file right away
        vtb.api.ImpactEvents.addListener(new HeadshotListener());
        // Re-read the config every 2 seconds, so edits (like debug_hitboxes) apply without a restart
        Thread t = new Thread(() -> {
            while (true) {
                try { Shield.poll(); Thread.sleep(2000); } catch (InterruptedException e) { return; } catch (Throwable ignored) { }
            }
        }, "HeadshotOnly-config");
        t.setDaemon(true);
        t.start();
    }
}
