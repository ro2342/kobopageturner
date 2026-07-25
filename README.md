# Kobo Page Turner

Turn your Android phone into a Bluetooth page-turner remote for Kobo
e-readers. No jailbreak, no changes to the Kobo — it just pairs like any
other Bluetooth keyboard.

## Status: spike (Android)

A Windows 10 Mobile / UWP version was tried first and confirmed dead-end:
Windows blocks third-party apps from publishing a local HID GATT service,
by policy, with no workaround. See `CLAUDE.md` for details — that code is
kept in `uwp/` for reference only.

## How it works

Kobo's own firmware lets you pair Bluetooth keyboards and use them to turn
pages (Left/Right arrow by default — remappable in the Kobo's Reading
settings). This app makes the phone advertise itself as a BLE keyboard
(HID over GATT), with two full-screen tap/swipe zones that send Left
Arrow / Right Arrow key presses. Pair it from the Kobo's Bluetooth settings
like you would any Bluetooth keyboard.

## Installing (sideload on Android)

1. Download the APK from the download page — `app/index.html`, i.e.
   `https://ro2342.github.io/kobopageturner/` — or from the **Actions**
   tab, latest successful run of *"03 - Build Android APK"*.
2. Open the downloaded `.apk` and allow "install unknown apps" for your
   browser/file manager if prompted.
3. Install and open the app.
4. Grant the Bluetooth permission prompt (needed to advertise as a BLE
   peripheral).

## Pairing with a Kobo

1. On the Kobo: Settings → Bluetooth (or Accessibility → Bluetooth
   accessories, depending on model/firmware) → search for devices.
2. On the phone: open Kobo Page Turner — it starts advertising
   automatically.
3. Select the phone from the Kobo's device list to pair.
4. Tap or swipe left/right on the phone screen to turn pages.

## Credits / prior art

This app's approach is based on reading (not reusing code from) these
reference projects:

- [tylpk1216/KoboPageTurner](https://github.com/tylpk1216/KoboPageTurner)
  — runs on a jailbroken Kobo, forwards HTTP requests to simulated touch
  events. Different approach (requires modifying the Kobo); not used here.
- [tsowell/kobo-btpt](https://github.com/tsowell/kobo-btpt) — a native
  plugin that runs on a jailbroken Kobo and maps Bluetooth Classic input
  events (e.g. from a gamepad) to page turns. Also requires modifying the
  Kobo; not used here.
- [tkanov/esp32-bluetooth-remote-kobo](https://github.com/tkanov/esp32-bluetooth-remote-kobo)
  — the model this app follows: an ESP32 board emulating a BLE HID
  keyboard that sends Left/Right arrow key codes, recognized by the Kobo's
  own native Bluetooth-accessory support with no Kobo-side changes.
- [kshoji/BLE-HID-Peripheral-for-Android](https://github.com/kshoji/BLE-HID-Peripheral-for-Android)
  (Apache 2.0) — consulted to confirm Android's GATT server doesn't block
  publishing a HID service the way Windows does, before building
  `BlePeripheralService.kt`.
