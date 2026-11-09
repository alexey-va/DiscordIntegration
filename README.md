# ![](http://media-elerium.cursecdn.com/avatars/46/357/636053578365458286.png) **DiscordIntegration**

[![Build Status](https://build.chikachi.net/buildStatus/icon?job=Chikachi/DiscordIntegration/1.7.10)](https://build.chikachi.net/job/Chikachi/job/DiscordIntegration/job/1.7.10/)

This mod combines your Discord with your Minecraft server.

This is a server-side mod and therefore don't need to be on the client.

#### Changes compared to previous versions
- Support for multiple Discord channels
- Ability to configure per Discord channel and per Minecraft dimension
- All commands is executed through a FakeUser [(DiscordFakeUser / 828653ca-0185-43d4-b26d-620a7f016be6)](https://mcuuid.net/?q=828653ca-0185-43d4-b26d-620a7f016be6) that you can decide permissions to through vanilla OP and other permission mods.


Guide on how to get the information needed from Discord can be found on [the wiki](https://github.com/Chikachi/ChikachiDiscord/wiki/How-to-get-a-token-and-channel-ID-for-Discord).

_This mod is not affiliated with Discord in any way._
# GTNH transport patch

The `v3/mc1.7` fork uses the original DiscordIntegration 3.0.5 release and its bundled JDA 3.5.1_339. The legacy Gradle build depends on an unavailable Jenkins artifact. The focused build in `gtnh/` compiles the patched client from the pinned upstream core source, checks the release JAR's SHA-256 and verifies that every unrelated JAR entry stays byte-for-byte identical.

Download the [original 3.0.5 JAR for Minecraft 1.7.10](https://www.curseforge.com/minecraft/mc-mods/discordintegration/files/2542338), then run with a modern JDK that supports `javac --release 8`.

```sh
git submodule update --init core
python3 gtnh/build.py /path/to/original.jar build/DiscordIntegration-mc1.7.10-3.0.5-ruscrafting.1.jar
jar=build/DiscordIntegration-mc1.7.10-3.0.5-ruscrafting.1.jar
javac --release 8 -cp "$jar" -d build/gtnh-test gtnh/DiscordTransportTest.java
java -cp "build/gtnh-test:$jar" chikachi.discord.core.DiscordTransportTest
```

REST and Gateway use an 8-second TCP connect timeout. REST reads and writes and Gateway handshake reads use a 15-second inactivity timeout. After WebSocket Upgrade, the Gateway uses JDA's existing heartbeat behavior.

Set `-Ddiscordintegration.httpProxy=http://host:port` on the Forge JVM to route both Discord REST and Gateway through an existing HTTP CONNECT proxy reachable from that host. The property applies only to this mod. Leave it unset to keep the existing route. Discord certificates are still verified by the original TLS implementation.
