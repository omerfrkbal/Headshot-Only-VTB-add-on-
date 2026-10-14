package com.headshotonly;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Wraps IsoZombie.Hit(): enter() runs before the hit is applied, exit() after.
 * The real work is in Shield. HitCharacter does the same for the parent class, because
 * I couldn't confirm which of the two classes actually declares Hit(). Hooking both is
 * safe, Shield ignores a nested call for the same zombie.
 */
@Patch(className = "zombie.characters.IsoZombie", methodName = "Hit")
public class HitZombie {
    @Patch.OnEnter public static void enter(@Patch.This Object self) { Shield.enter(self); }
    @Patch.OnExit  public static void exit(@Patch.This Object self)  { Shield.exit(self); }
}
