# Student — Meteor Client addon

A Meteor Client addon for Minecraft **1.21.11** shipping three modules:

| Module         | Category      | Description                                              |
|----------------|---------------|----------------------------------------------------------|
| `High-Ping`    | Student pvp   | Fake high-ping / blink (queues movement, flushes bursts) |
| `Student-Aura` | Student pvp   | Multi-target aura with smart-crit, aim modes, bypass     |
| `Pearl-Predict`| Student pvp   | Ender-pearl trajectory + landing-point prediction        |

## Build

Requirements: **JDK 21**.

```bash
./gradlew build
```

Output jar: `build/libs/Student-1.21.11.jar`.

## Install

1. Install Fabric Loader for Minecraft 1.21.11.
2. Drop `meteor-client.jar` and `Student-1.21.11.jar` into `.minecraft/mods/`.
3. Launch; modules appear under the `Student pvp` category.

## Project layout

```
src/main/java/com/example/addon/
  AddonTemplate.java            # Meteor entrypoint (registers modules + categories)
  modules/
    HighPing.java
    PearlPredict.java
    StudentAura.java
src/main/resources/
  fabric.mod.json
  assets/student/icon.png
```

## Notes

- `Student-1.21.11_decompiled_sources.zip` contains the decompiled output of an
  earlier `Student-1.21.11.jar` and is kept for reference only. It is **not**
  part of the build — the decompile contained broken synthetic constructs
  (`case N::`, `/* goto @N; */`, split inner-class files, `/* lambda: X */`
  record bodies) that cannot be recompiled as-is.
