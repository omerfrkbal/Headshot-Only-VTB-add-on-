package com.headshotonly;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "Hit")
public class HitCharacter {
    @Patch.OnEnter public static void enter(@Patch.This Object self) { Shield.enter(self); }
    @Patch.OnExit  public static void exit(@Patch.This Object self)  { Shield.exit(self); }
}
