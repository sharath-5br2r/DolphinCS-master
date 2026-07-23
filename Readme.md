## DolphinCS Main
DolphinCS Main incorporates aspects of [DolphinCS](https://github.com/JoeysRetroHandhelds/DolphinCS) but instead syncs to pull requests. All branding is removed and the apk is spoofed to Game for Peace`com.tencent.tmgp.pubgmhd`  for performance.

## Modified Original Readme

# DolphinCS

[![Join their Discord](https://img.shields.io/badge/Discord-Join%20their%20Community-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://joeysrh.link/discord)

DolphinCS is an unofficial fork of [Dolphin](https://github.com/dolphin-emu/dolphin), the
GameCube/Wii emulator. It is **not affiliated with the Dolphin Emulator project**.

The only thing this fork changes is **where Dolphin stores its user data on Android**. Everything
else is identical to upstream Dolphin.

## What's different

A settings toggle lets you choose where Dolphin's user data (settings, saves, game paths, etc.)
lives:

- **Scoped Storage** (default, same as official Dolphin)
- **Internal Storage** (`/sdcard/dolphin-emu`)
- **SD Card**, if a removable card is detected

Switching locations offers to migrate your existing data to the new location automatically.

Nothing else is added, removed, or changed.

## Releases

Releases here are built automatically every pull request. See the
[My Patched APKs](https://sharath-5br2r.github.io/my-patched-apkz) for builds.
The releases are uploaded to [my-patched-apks](https://github.com/sharath-5br2r/my-patched-apks/) with other patches,

## License

DolphinCS is licensed under the GNU GPL version 2 (or any later version), same as upstream
Dolphin. See [COPYING](COPYING) for the full license text.

## Official Dolphin

For the official, upstream Dolphin (all platforms), see [dolphin-emu.org](https://dolphin-emu.org/)
or the [dolphin-emu/dolphin](https://github.com/dolphin-emu/dolphin) repository.
