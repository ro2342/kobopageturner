# Kobo Page Turner

Turn your Windows 10 Mobile phone into a Bluetooth page-turner remote for
Kobo e-readers. No jailbreak, no changes to the Kobo — it just pairs like
any other Bluetooth keyboard.

## Status: spike

This is a first spike to validate the core mechanism on real hardware
before building out settings/polish. See `CLAUDE.md` for the technical
design and the open hardware-support risk.

## How it works

Kobo's own firmware lets you pair Bluetooth keyboards and use them to turn
pages (Left/Right arrow by default — remappable in the Kobo's Reading
settings). This app makes the phone advertise itself as a BLE keyboard
(HID over GATT) with two buttons that send Left Arrow / Right Arrow key
presses. Pair it from the Kobo's Bluetooth settings like you would any
Bluetooth keyboard, then use the on-screen buttons (or, once paired, the
Kobo's own settings can be used to remap which physical/virtual keys turn
pages).

## Installing (sideload on Windows 10 Mobile)

1. Go to the **Actions** tab of this repo, open the latest successful run
   of *"02 - Build appxbundle"*.
2. Download the `.appxbundle` from `app/app.appxbundle` (or the workflow
   artifact), or use the download page at `app/index.html`.
3. On the phone: **Settings > Update & security > For developers** → turn
   on **Developer Mode**.
4. Transfer the `.appxbundle` to the phone and open it from File Explorer
   to install.
5. If you get an "untrusted publisher" error, first install the public
   certificate (`.cer`, produced by the one-time *"01 - Generate signing
   certificate"* workflow run) to trust the self-signed publisher, then
   retry installing the `.appxbundle`.

## Pairing with a Kobo

1. On the Kobo: Settings → Bluetooth (or Accessibility → Bluetooth
   accessories, depending on model/firmware) → search for devices.
2. On the phone: open Kobo Page Turner — it starts advertising
   automatically.
3. Select the phone from the Kobo's device list to pair.
4. Tap "‹ Previous" / "Next ›" on the phone to turn pages.

## Credits / prior art

This app's approach is based on reading (not reusing code from) three
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
  own native Bluetooth-accessory support with no Kobo-side changes. This
  app reimplements that same idea natively on Windows 10 Mobile/UWP.
