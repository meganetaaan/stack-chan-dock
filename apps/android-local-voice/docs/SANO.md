# sanoTTS Android integration

The existing Android app supports Piper Plus, VOICEVOX and sanoTTS-jp. Piper remains the default. The selected engine is saved and its verified installation is restored on startup. Switching is disabled while setup, preview, diagnosis or conversation is active; stop playback/conversation before changing engines.

## Pinned runtime and assets

- Source: `ayutaz/sanoTTS-jp`, commit `3eb73a91acdb8d4bc0cc10c3bc4e670555ce20bc`.
- CPU only, one synchronous native worker, W8A8 (`SAAN_INT8_ACT=1`); no GPU/Vulkan dependency.
- `saanotts-jp-v4-int8.bin`: 654,032 bytes, SHA-256 `a1eb6b0812e2ad2a228836088a3e34160cb66731492fb957a5891605db2fa1b6`.
- `k1-dict-438750.bin`: 13,702,320 bytes, SHA-256 `f162c922074d76817298b34d8a8fd35f7d195f38540303485a76c956b5d84877`.
- Both files come from the official `v1.2.0` GitHub release. The APK contains code and notices; models and dictionaries are downloaded separately.

Choose sanoTTS in voice settings, review the model conditions, and press **sanoTTSを準備**. If GitHub downloads fail, save both official files and import them one at a time with **公式モデル／辞書ファイルを取込む**. Filenames supplied by a file provider are ignored: exact size and SHA-256 determine acceptance. Invalid/cancelled imports preserve the previously verified destination. Both assets are rehashed before native loading, even if cached verification markers exist. Synthesis stays disabled until both assets are ready.

Code is MIT; model weights have the separate sanoTTS-jp Model License 1.0, including output-use conditions. Full model conditions, required v4 attribution, code license, NAIST-derived dictionary and OpenJTalk notices, and Apache-2.0 text are bundled under `assets/sano-notices` and available in the settings dialog offline. This is not a new voice or a model trained on personal recordings.

## Audio and lifecycle

The adapter implements the existing `SpeechSynthesizer`. It emits mono PCM16 at 22,050Hz through a coroutine flow with rendezvous buffering. The existing `SerialPcmAudioSink` resamples to 24,000Hz, frames PCM16LE, and handles START, credit, END, DONE and ABORT. Inference and the USB writer have separate responsibilities and the existing bounded USB queue remains in use.

The shared sink also cancels its outbound channel when the USB writer fails, waking producers blocked behind a full queue and propagating the original error. Previously a closed channel could retain those pending sends after its receiver had failed. Existing normal completion, credit handling and ordered ABORT behavior remain covered by regression tests.

An atomic generation invalidates requests on cancel/close. Native calls and handle release share a lock; an in-flight native call returns before its handle is freed. Native G2P calls are serialized across instances because the pinned upstream frontend retains shared scratch. JNI uses actual UTF-8 bytes for text rather than modified UTF-8. Cancellation cannot interrupt a synchronous C call midway, but its result is checked before emission and stale requests cannot resume the stream.

Requests are limited to 1,000 Unicode code points and 120 seconds of generated PCM. Text is split at punctuation or at 40 code points, preserving surrogate pairs. The upstream 44-token G2P limit and native arena limits remain unchanged; exceeding them produces an explicit error, never silently truncated or substituted speech. Splitting can alter prosody and arbitrary-language/voice quality has not been established. The frontend uses `saan_kanji`, the pinned NAIST-derived dictionary and OpenJTalk rules.

## Build and checks

Use the existing application dependencies and an installed NDK; no new inference framework is needed:

```sh
python scripts/build_sano.py --ndk /path/to/android-ndk-r30
./gradlew --max-workers=1 :app:testDebugUnitTest :app:assembleDebug \
  -PsanoEvaluation=true -PsanoFixtureDir=/path/to/pinned-public-assets \
  -PsanoPcmFixture=/path/to/host-0.pcm16le \
  -PvoicevoxDictionaryFixture=/path/to/open_jtalk_dic_utf_8-1.11.tar.gz
```

The script verifies the vendored source manifest, compiles ARM64 for API26 and uses 16KiB ELF segment alignment. CMake source is also included for environments already configured to use it. Build the JNI before assembling the APK. `sanoEvaluation=true` creates `jp.stackchan.localvoicepoc.sanoeval` / **StackChan sanoTTS Eval**; existing normal and VOICEVOX evaluation builds keep their application IDs. The evaluation app has independent models, preferences and USB permission. It does not import production app data. Without that property the feature is integrated into the normal app; production installation was not performed.

Pure lifecycle, policy and Dock mock checks use the existing JUnit/coroutine stack. Public model fixtures enable hash/import and loaded-runtime tests; without fixture properties those fixture-dependent checks are skipped. PC JNI checks additionally compile the same C source for Windows and run Japanese synthesis, interruption, stale-result suppression and reload. They establish host behavior, not execution of the Android ARM64 binary.

Android installation, audible quality, actual USB Dock playback/disconnection, 16KiB device execution, and coexistence with loaded LLM/STT runtimes require device validation. Neither the phone nor COM12 was accessed during this task. No push, PR, merge or public deployment was performed.
