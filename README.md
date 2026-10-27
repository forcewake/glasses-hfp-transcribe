# Glasses Transcript

Android app that records the microphone on Meta Ray-Ban Display glasses over Bluetooth HFP and shows a near-real-time transcript computed on the phone.

The glasses already behave as a normal Bluetooth headset once they are paired in the Meta AI app. This app does not use the Meta Wearables Device Access Toolkit.

- minSdk 31, targetSdk 37 (Android 17)
- Kotlin, Jetpack Compose
- Capture: `AudioRecord`, 16 kHz mono PCM16, `VOICE_COMMUNICATION`
- Speech: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 2.0.0, streaming models, Apache-2.0 runtime

## Install

1. Pair the glasses with the phone in the Meta AI app. Developer Mode is not required for this HFP path.
2. On the phone, open the [GitHub release](../../releases) and download `glasses-transcript-0.1.2-debug.apk`.
3. Allow installation from the browser or Files, then install. The APK is signed with the debug key in this repository (`app/debug.keystore`, store password `android`). Later builds of the same version install over the previous one.
4. Open **Glasses Transcript** and allow the microphone, nearby Bluetooth devices, and notifications.

The first recording downloads the selected speech model. The default Russian model is about 23 MB. Nothing in the APK is a speech model.

## Permissions

| Permission | Why |
| --- | --- |
| Microphone | Capture and the foreground service type |
| Nearby devices (`BLUETOOTH_CONNECT`) | Read the headset name and select its SCO input |
| Notifications | Keep the ongoing “Recording” notification visible |
| Internet | Download a model from the sherpa-onnx GitHub release the first time it is used |

Recording runs in a foreground service with type `microphone`, a partial wake lock, and a persistent notification, so it continues with the screen off. Leaving the app does not stop it. Stop from the notification or the in-app button. The red **Recording** banner is the in-app indicator.

## How to record

- **Glasses** is the default input. The status line names the headset that will be used, for example `Glasses microphone · Ray-Ban Meta`.
- If several Bluetooth headsets are connected, Settings → **Glasses name** picks the product name that contains that text (default `Ray-Ban`). Otherwise the app prefers a name containing Ray-Ban, RayBan, or Meta, then the first SCO device.
- The app sets `AudioManager.mode` to `MODE_IN_COMMUNICATION` and calls `setCommunicationDevice` with the `TYPE_BLUETOOTH_SCO` device from `availableCommunicationDevices` (the speaker side of the headset). The glasses microphone is a separate input and is only passed to `AudioRecord`. If the phone rejects that request, the app falls back to `startBluetoothSco` and waits until SCO is connected. A failure shows what was tried and a **Copy diagnostics** action.
- **Phone microphone** is the other side of the same switch. It is labeled **Phone microphone**, or **Phone microphone · no glasses** when no headset is connected. It uses the built-in mic and `VOICE_RECOGNITION`, and does not take the communication route.
- A level meter shows the live input. Partial text is italic; finished phrases are saved.
- Sessions (WAV + timestamped `.txt`) are listed under **Sessions**. You can play, share, or delete them. Files live in the app’s private storage.

Transcript lines look like this:

```text
[00:01.200] Привет
[00:04.200] hello there
```

The timestamp is when the phrase was committed, on the recording clock.

## How HFP routing works, and its limits

Meta’s microphone guide for Android is the routing this app uses: choose `AudioDeviceInfo.TYPE_BLUETOOTH_SCO` from `AudioManager.availableCommunicationDevices` (a sink), set `MODE_IN_COMMUNICATION`, then `setCommunicationDevice`. The platform picks the matching microphone. This app also passes that input to `AudioRecord` when it can see one. Phones that reject `setCommunicationDevice` get a `startBluetoothSco` fallback. After the route has settled, capture reads the glasses.

[Device audio overview](https://wearables.developer.meta.com/docs/develop/dat/microphones-and-speakers):

| Profile | Direction | What you get |
| --- | --- | --- |
| A2DP | Output only | Music-quality playback to the glasses |
| HFP | Both ways | Mono voice, 16 kHz or 8 kHz, negotiated by the phone and the glasses |

HFP and A2DP cannot be active together. Starting a recording drops A2DP. Music, navigation prompts, and other high-quality playback to the glasses stop until the recording ends and the app restores `MODE_NORMAL` and clears the communication device.

On HFP, the glasses beamform toward the wearer. Other people and the room are much quieter. That is the headset path, not a bug. The app asks for 16 kHz. If the link is narrowband, capture falls back to 8 kHz and the status line shows it. PCM is mono, 16-bit.

If the SCO route disappears mid-recording, the app tries to select the glasses again. If that fails, it saves what it already recorded and shows an error.

## Speech models

The engine is sherpa-onnx’s streaming `OnlineRecognizer`. Capture is 16 kHz PCM handed straight to the recognizer. The platform `SpeechRecognizer` is not used: its on-device path depends on whatever language packs the phone has installed, and it is not a reliable consumer of an app-owned SCO route.

A small `SpeechTranscriber` interface sits between the recorder and sherpa-onnx. Another engine can implement it later. A separate Vosk runtime is not included.

| Model | When | Download | Notes |
| --- | --- | --- | --- |
| Russian · small Zipformer int8 | Default | ~23 MB | `sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16`. Streaming Zipformer2. |
| Russian · T-one | Optional, higher accuracy | ~123 MB | `sherpa-onnx-streaming-t-one-russian-2025-09-08`. Streaming CTC, telephony-tuned (the model expects 8 kHz; sherpa resamples the 16 kHz capture). |
| English · Kroko Zipformer | English | ~55 MB | `sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06`. Streaming Zipformer2. |
| All languages · Nemotron 3.5 | Optional | ~450 MB download, ~650 MB on disk | `sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-160ms-int8-2026-06-11`. Streaming 0.6B int8, 160 ms chunks. Russian, English, and Dutch, plus punctuation. Dutch stays disabled until this model is on the phone. |

Archives come from the official [asr-models release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models). The sherpa-onnx Android library itself is the [v2.0.0 AAR](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.0), downloaded by Gradle, not committed.

The larger icefall English streaming Zipformer (`sherpa-onnx-streaming-zipformer-en-2023-06-26`, about 296 MB compressed) was skipped in favor of Kroko, which is the same family of streaming transducer at a size that is reasonable to fetch on a phone.

### Why this engine

Streaming Zipformer emits a partial hypothesis while the person is still talking, and the default Russian model is small enough to run on a phone CPU. T-one is the higher-accuracy Russian option when the link is narrowband voice. Nemotron 3.5 is the one official streaming model in that release that covers Dutch as well as Russian and English, so it is offered as an explicit large download rather than a second native library.

### What else was considered

- **Vosk** streams on CPU and has a small Dutch model (`vosk-model-small-nl-0.22`, about 39 MB). Accuracy is behind a current Zipformer, and it would be a second native runtime. The transcriber interface is there so it can be added; this build does not ship it.
- **whisper.cpp / whisper streaming** covers Russian, English, and Dutch, but Whisper is a windowed model. Partial text means re-decoding chunks, and a whisper.cpp tiny build has been measured much slower than the sherpa-onnx runtime on older phones.
- **Moonshine** is fast and aimed at on-device streaming, and it does not cover Russian or Dutch.
- **`SpeechRecognizer.createOnDeviceSpeechRecognizer`** (Android 12+, with on-device packs more reliable from Android 13) can stream partials, but only for languages the device has installed, and this app does not route SCO audio through it.

### Real-time factor

RTF is decode time divided by audio time. Below 1.0 means the transcript keeps up. This app shows the live RTF while recording and stores it on the session.

Published figures, not measurements from this phone:

- A 20M English streaming Zipformer in sherpa-onnx was about **RTF 0.12** on a Galaxy S10 (30 seconds of 16 kHz audio, VoicePing’s February 2026 Android benchmark). Kroko and the Russian int8 Zipformer are the same class of small streaming transducer; the Russian encoder is about 26 MB.
- The sherpa-onnx T-one example log reports about **RTF 0.32** on that author’s machine for a 6.4 second clip (`Elapsed seconds: 2`).
- Nemotron 3.5 int8 was about **RTF 0.12** on an M-series Mac in the export notes. On a phone it costs more CPU and memory than the small Zipformer. That is why it is optional. The 160 ms chunk is the latency/accuracy point shipped here; the same release also has 80, 320, 560, and 1120 ms packages, and a longer chunk is more accurate.

CI cannot record from glasses, so those numbers are citations. The number that matters is the RTF line on your phone.

## Privacy

Say that you are recording. The app keeps a red banner up and a notification that does not go away. Whether the glasses’ own capture LED lights during HFP-only capture is not documented, so do not assume bystanders can see it on the frames.

Audio and transcripts never leave the phone except when you share a session. Models are downloaded from GitHub and stored in app-specific files. Backup of app data is off.

## Troubleshooting

- **No glasses microphone.** Wake the glasses, confirm they are connected as a headset in system Bluetooth settings, and grant Nearby devices. If another app owns the call route, disconnect it.
- **The route did not settle.** Move the glasses closer and try again. The app waits up to about 2.5 seconds and then checks that the SCO device is actually the communication device.
- **You hear nothing in playback, or the meter stays at zero.** Confirm the status line says glasses and not **Phone microphone**. HFP beamforming also ignores voices that are not the wearer’s.
- **Music to the glasses stops.** Expected. HFP replaces A2DP until the recording ends.
- **The transcript is empty or in the wrong language.** Settings chooses one model at a time. Russian small Zipformer will not transcribe English. Dutch needs the Nemotron download.
- **Download failed.** The phone needs a network path to `github.com` and enough free space (about 1.2 GB free before the Nemotron install, because the archive and the extracted model exist at the same time).
- **Nemotron is slow or the app is killed.** Use the small Zipformer or Kroko. The 0.6B model wants a phone with memory to spare; the process is marked `largeHeap`, which is not a guarantee.
- **Recording stops when the screen is off.** Battery restrictions on some phones still kill foreground recording. Exempt the app from battery optimization. The notification should stay visible while a session is running.
- **Install says the package conflicts.** Uninstall the previous copy if it was signed with a different debug key.

## Develop

```bash
export ANDROID_HOME="$HOME/android-sdk"   # or your SDK
./gradlew test
./gradlew assembleDebug
```

The first build downloads `sherpa-onnx-2.0.0.aar` from the sherpa-onnx GitHub release. Unit tests cover SCO device selection, the WAV writer, WAV reading, and transcript formatting. They do not load the native library.

Debug builds can feed a WAV through the same recognizer without a button on screen. The activity has to be in the foreground, and the path has to be readable by the app:

```bash
adb shell am start -n com.glasses.hfptranscribe/.MainActivity \
  -a com.glasses.hfptranscribe.action.DEBUG_TRANSCRIBE_WAV \
  --es wav /data/user/0/com.glasses.hfptranscribe/files/inbox/clip.wav \
  --es model ru-small-int8
```

Release builds ignore that intent.

GitHub Actions, on every push to `main`, runs those tests, builds the debug APK, and attaches it to a GitHub Release tagged from `appVersionName` in `gradle.properties` (currently `v0.1.2`). Pull requests run the same tests and build without publishing.
