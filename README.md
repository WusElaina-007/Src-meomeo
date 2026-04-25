# PhgMC — Meteor Client addon

A Meteor Client addon for Minecraft **1.21.11** shipping four modules:

| Module            | Category      | Description                                                  |
|-------------------|---------------|--------------------------------------------------------------|
| `High-Ping`       | PhgMC PvP     | Fake high-ping / blink (queues movement, flushes bursts).    |
| `Student-Aura`    | PhgMC PvP     | Multi-target aura with smart-crit, aim modes, bypass.        |
| `Pearl-Predict`   | PhgMC PvP     | Ender-pearl trajectory + landing-point prediction.           |
| `Tim-Cong-Trinh`  | phg support   | Predict vanilla structure locations offline from a seed.     |

Designed to be **independent of the original Student/KingMC addon** — the mod id
(`phgmc`), package (`com.phgmc.addon`), jar name (`PhgMC-*.jar`) and category
names (`PhgMC`, `PhgMC PvP`, `phg support`) do not collide with any previous
Student build, so both can be installed side-by-side.

## Build

Requirements: **JDK 21**.

```bash
./gradlew build
```

Output jar: `build/libs/PhgMC-1.21.11.jar`.

## Install

1. Install Fabric Loader for Minecraft 1.21.11.
2. Drop `meteor-client.jar` and `PhgMC-1.21.11.jar` into `.minecraft/mods/`.
3. Launch; modules appear under the `PhgMC PvP` and `phg support` categories.
4. Registration is logged as `[PhgMC] + <Name>` in `.minecraft/logs/latest.log`
   so failures are obvious.

## Project layout

```
src/main/java/com/phgmc/addon/
  PhgMCAddon.java                 # Meteor entrypoint (registers modules + categories)
  modules/
    HighPing.java
    StudentAura.java
    PearlPredict.java
    StructureFinder.java          # "Tim-Cong-Trinh"
src/main/resources/
  fabric.mod.json
  assets/phgmc/icon.png
```

## Notes

- `Student-1.21.11_decompiled_sources.zip` contains the decompiled output of an
  earlier `Student-1.21.11.jar` and is kept for reference only. It is **not**
  part of the build — the decompile contained broken synthetic constructs
  (`case N::`, `/* goto @N; */`, split inner-class files, `/* lambda: X */`
  record bodies) that cannot be recompiled as-is.
