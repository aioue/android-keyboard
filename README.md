# Personal fork: what is changed here

This is a personal fork of FUTO Keyboard (branch `diag-log`). Nothing here has been pushed upstream. Everything changed compared with `futo-org/android-keyboard` master is listed below. "Fork only" means it is not meant for upstream. "Upstream candidate" means a clean local branch off master exists (`pr/...`); none has been raised.

- **Diagnostic log** (fork only): an encrypted local log of swipe, autocorrect and backspace events, with toolbar actions to switch it on or off (the icon is red while it is on), view, export and clear. It is off by default. Settings > Misc has a switch that shows the same state as the red icon. It is encrypted to a public key built into the app; the private key stays on the Mac (`tools/diaglog`).
- **Swipe language-model fix** (fork only): copies `scoring.json` next to the swipe models and reports the model under its old codename, so the scoring weight is not zero (upstream issue #2405). Also a one-shot personal dictionary import and extra swipe language-model details in the log.
- **Hold volume up to dictate** (fork only): hold volume up with the keyboard shown to dictate, release to finish. A quick tap still changes the volume.
- **Volume down submits** (fork only): while holding volume up, volume down finishes dictation and presses Enter. In T3 Code it taps the send button through an accessibility service and reopens the keyboard.
- **Hold the voice panel to pause silence auto-stop** (fork only).
- **Long dictation in chunks** (fork only): long recordings are transcribed in pieces at pauses while still recording. Behind a setting.
- **Pre-warmed recorder** (setting; upstream candidate, parked): creates the voice recorder while the keyboard is shown, which was about 67 ms faster to first audio in testing. It is released when the keyboard hides, is built outside the lock so hiding does not stall, and is skipped when the system voice input is selected. Branch `pr/voice-recorder-prewarm` does not have the stall fix.
- **Quick words are transcribed** (upstream candidate: `pr/voice-short-clips`): whisper.cpp returns nothing for clips under about 1 s, so shorter clips are padded to 1.1 s, but only when the voice detector heard enough speech (at least 4 speech frames, ignoring the first 150 ms).
- **Silent recordings are not transcribed** (upstream candidate: `pr/voice-skip-no-speech`, not to be raised before about 600 presses of testing): a recording with fewer than 4 speech frames, of any length, is not sent to Whisper, because Whisper makes up text for silence. The upstream branch uses a stricter test (no speech frames at all) than this fork.
- **Bluetooth availability check** (upstream candidate: `pr/voice-bt-single-query`): the first device list is reused instead of asking Android for it a second time, which brings the listening screen forward by about 18-30 ms.
- **Short-clip guards** (fork only): decoder limited to 32 steps for clips under 2 s (separate local branch `pr/voice-decoder-cap`, parked), a word repeated three or more times in a short result is collapsed, and no live partial text is shown for clips under 2 s.
- **Empty voice result** (fork only): an empty result no longer overwrites text that is being composed.
- **Voice timing marks** (fork only): timing and result word count in the `VoiceTiming` log tag, for measuring.

Rejected after testing: a filter for the text "♪ ♪" (`pr/voice-blank-music-note`). Skipping silent recordings covers it and the other made-up text.

---

# FUTO Keyboard

The goal is to make a good modern keyboard that stays offline and doesn't spy on you. This keyboard is a fork of [LatinIME, The Android Open-Source Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME), with significant changes made to it.

Check out the [FUTO Keyboard website](https://keyboard.futo.tech/) for downloads and more information.

The code is licensed under the [FUTO Source First License 1.1](LICENSE.md).

## Nightly builds

Usually, a build close to the current master is available at [https://dl.keyboard.futo.org/nightly.apk](https://dl.keyboard.futo.org/nightly.apk). Read more about it here: https://docs.keyboard.futo.tech/improvements/nightly

The "Check for updates manually" button in the nightly build will check against the latest nightly, allowing you to get updated this way. It's recommended to check it once immediately after first installation in case an older version got downloaded due to CDN cache.

## Issue tracking and contributing

Please check the GitHub repository to report issues: [https://github.com/futo-org/android-keyboard/](https://github.com/futo-org/android-keyboard/)

The source code is hosted on our [internal GitLab](https://gitlab.futo.org/keyboard/latinime) and mirrored to [GitHub](https://github.com/futo-org/android-keyboard/). As registration is closed on our internal GitLab, we use GitHub instead for issues and pull requests.

If you want to help translate the app, please do so via our Pontoon instance: https://i18n-keyboard.futo.org/

Due to custom license, pull requests to this repository require signing a [CLA](https://cla.futo.org/) which you can do after opening a PR. Contributions to the [layouts repo](https://github.com/futo-org/futo-keyboard-layouts) don't require CLA as they're Apache-2.0

Please do not submit AI-generated pull requests, as these tend to make the codebase more difficult to understand. Pull requests made with clear heavy use of AI may be closed without comment. Please also avoid using AI to write issues, and try to explain it in your own words instead.

## Layouts

If you want to contribute layouts, check out the [layouts repo](https://github.com/futo-org/futo-keyboard-layouts).

## Building

When cloning the repository, you must perform a recursive clone to fetch all dependencies:
```
git clone --recursive https://gitlab.futo.org/keyboard/latinime.git
```

If you forgot to specify recursive clone, use this to fetch submodules:
```
git submodule update --init --recursive
```

You can then open the project in Android Studio and build it that way, or use gradle commands:
```
./gradlew assembleUnstableDebug
./gradlew assembleStableRelease
```

## APK signing

For official FUTO Keyboard versions, you can verify the APK's signing key fingerprint for integrity.

```
Signing key fingerprint for all versions except Google Play:

MD5: 3A:BB:71:C6:BB:E4:92:27:B1:E3:5D:81:01:48:6A:B0
SHA1: 5D:15:B3:6E:C9:6A:96:28:41:09:DD:62:93:0D:9C:39:9F:5F:06:43
SHA-256: 74:3F:AD:58:64:AB:C4:26:50:0B:2D:C2:C4:7C:8A:D3:24:CB:CD:16:03:3F:80:16:99:48:41:35:63:74:F9:95

```
