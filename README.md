# CheckPrint

Android app that finds receipt / label printers on your Wi‑Fi network and prints text or images to them — no drivers, no vendor apps.

[![Build](https://github.com/Holego/CheckPrint/actions/workflows/android.yml/badge.svg)](https://github.com/Holego/CheckPrint/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/Holego/CheckPrint)](https://github.com/Holego/CheckPrint/releases/latest)

**Download:** grab `CheckPrint-x.y.z.apk` from the [latest release](https://github.com/Holego/CheckPrint/releases/latest). Android 8.0+.

## What it does

- **Scan** — probes every address in your subnet (auto-detected, editable as CIDR `192.168.1.0/24` or `192.168.1.1-254`) for the ports thermal printers listen on: `9100`, `9101`, `9102` (RAW / JetDirect), `8100` (some ESC/POS clones), `8008` (Epson ePOS), `631` (IPP), `515` (LPD). Port list and timeout are editable. Hosts show up as they are found, with reverse‑DNS names when available.
- **Print** — tap a found port (or type IP + port by hand) and send:
  - **Text** with width ×1–×8 and height ×1–×8 scaling (`GS !`), bold, underline, left/center/right alignment and a selectable code page (CP437, CP866, CP1251, CP1252, CP852, CP858, UTF‑8) plus a raw `ESC t n` override for printers with non‑Epson tables.
  - **Image** from the gallery: scaled to 384 dots (58 mm), 576 dots (80 mm) or any custom width, converted with Floyd–Steinberg dithering or a plain threshold, live preview, sent as `GS v 0` raster bands.
  - **Both together** — choose whether the image goes above or below the text.
  - Feed lines and paper cut (`GS V`) after the job; a built‑in **test page** to check sizes, alignment and Cyrillic.
- **Transports:** RAW TCP (ESC/POS), LPD (RFC 1179) and a minimal IPP Print‑Job.
- **Languages:** English and Russian, switchable in the app (About tab) or following the system.

## How to use

1. Connect the phone to the same Wi‑Fi as the printer.
2. Open **Scan** and tap **Scan network**. Every host with an open printer port is listed.
3. Tap a port → the **Print** tab opens with the address filled in.
4. Type text and/or choose an image, adjust size, then tap **Print** (or **Test page**).

If Cyrillic prints as garbage, switch the code page between CP866 and CP1251, or type the `ESC t` number from your printer's manual (e.g. `17` = CP866 on Epson‑compatible firmware, `73` on some Chinese models).

## Building

```bash
git clone https://github.com/Holego/CheckPrint.git
cd CheckPrint
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests (IP ranges, ESC/POS, encodings, fake printers)
```

Requires JDK 17+ and the Android SDK (platform 34). To produce a signed release build, put the keystore outside the repo and point `local.properties` (git‑ignored) at it:

```properties
RELEASE_STORE_FILE=C:/path/to/checkprint-release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=checkprint
RELEASE_KEY_PASSWORD=...
```

then `./gradlew assembleRelease`.

Stack: Kotlin, Jetpack Compose (Material 3), coroutines. No third‑party printer libraries — the ESC/POS, LPD and IPP code lives in `app/src/main/kotlin/.../escpos` and `.../net`.

## License

MIT — see [LICENSE](LICENSE).
