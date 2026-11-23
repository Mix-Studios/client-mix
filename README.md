# client-mix

The MixClient launcher for Minecraft: Java Edition. Every released version lives here.

## Download

Get the installer for your system from [Releases](https://github.com/Mix-Studios/client-mix/releases/latest):

| System | File |
| --- | --- |
| Windows 10/11 (x64) | `MixClient-1.0.0-installer-windows-x64.msi` |
| macOS, Apple silicon | `MixClient-1.0.0-installer-macos-arm64.dmg` |
| macOS, Intel | `MixClient-1.0.0-installer-macos-x64.dmg` |
| Debian, Ubuntu, Mint (x64) | `MixClient-1.0.0-installer-linux-amd64.deb` |
| Fedora, RHEL, openSUSE (x64) | `MixClient-1.0.0-installer-linux-x86_64.rpm` |

A plain `MixClient-1.0.0-all.jar` is published too, for any system that already has Java 17 or newer.

Each installer bundles its own Java runtime, so nothing else has to be installed. The builds
are unsigned: on macOS right-click **MixClient** in Applications and choose **Open** the first
time (or `xattr -dr com.apple.quarantine /Applications/MixClient.app`), and on Windows pick
**More info → Run anyway** in the SmartScreen prompt.

`checksums.txt` holds the SHA-256 of every file in the release, and each file has build
provenance that `gh attestation verify <file> --repo Mix-Studios/client-mix` checks.

## Launcher data

Accounts, settings, the launcher log and downloaded game files live in one directory:

- Windows: `%APPDATA%\MixClient`
- macOS: `~/Library/Application Support/MixClient`
- Linux: `~/.local/share/mixclient`

Set `MIXCLIENT_HOME` to point it somewhere else.

## Build from source

```sh
./gradlew fatJar            # build/libs/MixClient-1.0.0-all.jar
./gradlew packageInstaller  # native installer for the OS you are on
```

`packageInstaller` runs `jpackage` and writes to `build/installer/dist/`. It builds the DMG on
macOS, the MSI on Windows and the DEB on Linux; pass `-PinstallerType=rpm` on Linux for the RPM.
jpackage only builds for the machine it runs on, which is why
[`.github/workflows/release.yml`](.github/workflows/release.yml) runs one job per platform and
publishes everything to the release when a `v*` tag is pushed.

## Client source

The Minecraft client lives in [`Client/`](Client/README.md): a Forge 1.8.9 module and a Fabric
26.3 module, each with its own Gradle build and its own build instructions there.
