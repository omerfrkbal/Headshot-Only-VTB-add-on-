# Headshot Only

Add-on for Viewpoint True Ballistics (VTB), Project Zomboid Build 42. Zombies shot with VTB bullets only die from headshots (neck / back of head included). Body and limb hits still do damage but leave the zombie at about 5% HP. Melee is untouched.

This is a Java mod, loaded by [ZombieBuddy](https://steamcommunity.com/workshop/browse/?appid=108600&searchtext=ZombieBuddy). Not affiliated with the VTB author.

Workshop page: _(add link)_

## What the code does

About 300 lines, 6 classes in `src/com/headshotonly/`.

- `Main` - entry point called by ZombieBuddy. Retries installing the hook until VTB is loaded.
- `Hook` - registers a listener with VTB's `ImpactEvents` and starts a thread that re-reads the config every 2 seconds.
- `HitZombie`, `HitCharacter` - `@Patch` hooks on `Hit()` that raise the zombie's health before the hit and restore it after, so non-head hits cannot kill.
- `Shield` - the logic: decides head / not head (VTB hit part, plus distance to the neck-head bone line for back-of-head shots) and writes the config.
- `HeadshotListener` - safety net: if a head hit somehow leaves the zombie alive, it kills it.

What it does **not** do: no network access, no sockets, no processes started, no environment variables read. The only file it touches is `Zomboid/HeadshotOnly.properties` (created on first launch, holds the 4 settings below).

## Build

You need a JDK and these jars from your own installs: `projectzomboid.jar` (game folder), the VTB jar and `ZombieBuddy.jar` (from their Workshop folders).

```
mkdir out
javac -d out -cp "projectzomboid.jar:vtb.jar:ZombieBuddy.jar" src/com/headshotonly/*.java
jar cfm HeadshotOnly.jar manifest.txt -C out com
```
(Use `;` instead of `:` on Windows.) Put the jar at `<mod>/42/media/java/client/HeadshotOnly.jar`.

ZombieBuddy shows the jar's SHA-256 in its approval prompt; compare it with the hash in the release notes. Hashes differ between builds, so build-it-yourself jars will not match the published one.

## Status

Tested in singleplayer only. Multiplayer is untested. It uses VTB internals (`ImpactEvents`, `BallisticsConfig`), so a VTB update can break it.

## License

MIT, see `LICENSE`.
