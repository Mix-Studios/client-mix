# Client source

Two MixClient modules live here. They are separate Gradle builds, one per Minecraft version.

## 1.8.9 — `1.8.9/cloudclient`

The Forge 1.8.9 PvP client: HUD editor, keystrokes, zoom, name tags, Discord presence and the
usual 1.8.9 tweaks.

Needs JDK 8; the project ships its own Gradle 4.4.1 wrapper.

```sh
cd 1.8.9/cloudclient
./gradlew build            # jar in build/libs/
```

Install it like any other 1.8.9 Forge mod: drop the jar into `.minecraft/mods` of a Forge 1.8.9
profile.

Discord presence stays off until it has an application id: put the id on a single line in
`.minecraft/mixclient/discord.txt`, or set `MIXCLIENT_DISCORD_APP_ID`. Upload a Rich Presence
asset named `mixclient` in that Discord application so the logo shows next to the activity.

`build.gradle` still resolves ForgeGradle 2.1 and Mixin from the Forge and Sponge mavens and still
lists the retired `jcenter()` repository, so a cold build may need those entries replaced.

## 26.3 — `26.3/mixclient`

The modern client: a Fabric mod for Minecraft 26.3 (the calendar-version release, Java 25). It
currently carries MixClient branding, Discord Rich Presence and the performance defaults.

Needs JDK 25 or newer; the wrapper is Gradle 9.9.1 and Fabric Loom fetches Minecraft, Fabric
Loader and Fabric API on the first build.

```sh
cd 26.3/mixclient
./gradlew build            # jar in build/libs/
./gradlew runClient        # launch a dev client with the mod loaded
```

Install it into a Fabric 26.3 profile with Fabric Loader 0.20.1 or newer and Fabric API
0.166.2+26.3.

Settings live in `config/mixclient.json`: `discordAppId` and `performanceTweaks`.
