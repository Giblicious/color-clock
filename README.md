# Color Clock

A full-screen color that changes on a schedule — green from 7:00 AM to 7:00 PM and red overnight by default. Handy as an "OK to wake" light for kids, a do-not-disturb sign, or anything else where a glance at a color is enough.

It comes in two forms:

- **Android app** (`android/`): turns a spare phone into a dedicated display. It starts on boot, keeps the screen on, locks itself into kiosk mode, has a child lock, and installs its own updates from this repo's GitHub releases.
- **Web page** (`public/`): the same display in any browser. Settings are saved in that browser.

## Android app

### Features

- Day and night colors, start times, brightness, and an optional label for each (e.g. "Stay in bed" / "OK to wake").
- Optional large clock and a "next change" reminder.
- **A pixel gecko** (on by default, toggle under **Display**) that lives on the screen. During the day it explores, climbs over the clock digits (they dip under its feet), climbs onto and naps on the reminder, hunts the flies and crickets that turn up (tap anywhere away from it to call one over), plays with a ball that rolls in now and then until it pops, and can be petted by holding a finger on it. Ten minutes before night it gets sleepy and heads to bed; at night it sleeps, darkened so it doesn't catch the eye, and ignores touches; in the morning it wakes up and does a few push-ups. A simple "when the gecko is up, you can get up" for kids who can't read a clock yet.
- Colors crossfade, text adapts its ink to stay readable on any color, and drifts a few pixels each minute to avoid burn-in.
- Full-screen settings laid out for a landscape phone, with nothing to scroll. Tap the screen, then the gear.
- **Child lock**: taps do nothing and the power menu is hidden. To open settings, hold the top-right corner for 3 seconds, then type the code shown in words (a new code every time, e.g. "Five Two Eight Zero" → 5280). Nothing to remember, but a child who can't read can't get in. The code screen goes back to the clock after three wrong codes or ten seconds untouched, and settings close without saving after two minutes untouched.
- **Kiosk mode** (needs device owner, below): becomes the home screen, hides the status and navigation bars, disables the lock screen, and keeps the screen on while plugged in.
- **Automatic updates**: checks GitHub for a new release every 6 hours (or on demand) and installs it. As device owner this is silent; otherwise Android asks once per update.

### Install on a phone

You need a phone with Android 8 or newer, a USB cable, and [`adb`](https://developer.android.com/tools/adb).

1. Factory-reset the phone, or remove every account from it (device owner can only be set with no accounts). Enable **Developer options → USB debugging**.
2. Download the latest `color-clock-*.apk` from [Releases](../../releases/latest) and install it:

   ```sh
   adb install color-clock-*.apk
   adb shell dpm set-device-owner com.giblicious.colorclock/.AdminReceiver
   adb shell am start -n com.giblicious.colorclock/.MainActivity
   ```

The app works without the `dpm` step too, but then there's no kiosk lockdown and each update asks for confirmation.

To uninstall later, open settings → **Device** → **Hold to release device owner**, then uninstall normally.

### Build and release

Requires the Android SDK and JDK 17+ (Android Studio's bundled JDK is found automatically on Windows), plus the [GitHub CLI](https://cli.github.com/) for releases.

```sh
npm run android:build      # build a signed APK
npm run android:release    # bump the version, build, and publish a GitHub release
```

Releases are signed with a key in `android/signing/`, which is not in git. Android only installs updates signed with the same key, so keep a backup of that folder. To build your own copy, create a keystore and an `android/signing/signing.properties` with `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`, and change the repository in `Updater.java` and `tools/android-release.mjs`.

## Web page

Open `public/index.html` from any static web host. Move the pointer or tap to show the gear; settings are saved in the browser. The gecko lives here too. The Android app bundles this same page (with the gecko) and drives it from its own settings.

```sh
npm run dev                # http://127.0.0.1:8088
docker build -t color-clock . && docker run -p 8088:80 color-clock
```

Browsers limit what a page can do: fullscreen needs a tap, screen wake lock only lasts while the page is visible, and service workers need HTTPS (except on `localhost`). The Android app doesn't have these limits.

## Development

The gecko's engine is in `pet/engine/`, and `pet/clock.ts` adapts it to the clock: the display's elements become its terrain and the schedule becomes its day. It's bundled into `public/pet.js`, which is committed so the web version needs no build step.

```sh
npm install
npm run check              # typecheck, syntax checks and tests
npm run pet:build          # rebuild public/pet.js after changing pet/
```

## Credits

The gecko is adapted from [Pixel Pet](https://github.com/spreadwell/pixel-pet) by Spreadwell, used under the MIT License. Its engine and license are in `pet/engine/`.
