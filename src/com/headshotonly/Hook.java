package com.headshotonly;

/** Touches VTB classes; only loaded from inside Main's try/catch. */
final class Hook {
    static void install() {
        Shield.poll();   // load config once up front
        vtb.api.ImpactEvents.addListener(new HeadshotListener());
        // poll the config file so debug_hitboxes etc. apply live, without needing a hit first
        Thread t = new Thread(() -> {
            while (true) {
                try { Shield.poll(); Thread.sleep(2000); } catch (InterruptedException e) { return; } catch (Throwable ignored) { }
            }
        }, "HeadshotOnly-config");
        t.setDaemon(true);
        t.start();
    }
}
