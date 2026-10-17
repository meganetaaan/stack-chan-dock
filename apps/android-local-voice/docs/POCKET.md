# Pocket TTS Japanese — private Dock evaluation

The fifth `POCKET` backend retains Piper 1.13, Piper Plus 2.0.1, VOICEVOX and sanoTTS. App ID `jp.stackchan.localvoicepoc.sanoeval` and the existing local debug certificate are retained; versionCode 3 / versionName `0.1.2-pocket-ja-sano-eval` allow an ordinary update without clearing existing settings, models or USB permissions. Previous APKs are preserved.

## Accepted implementation and model

Ported from the read-only `dev.irte.voice` v0.4.1-pocket-ja evaluation: stock Pocket TTS 3.3.0 Japanese 6-layer FP32 graphs, default female_03 voice state including Japanese BOS, ONNX Runtime 1.30.0 CPU1, temperature 0.3, LSD steps 1, fixed seed-0 noise. VersionCode 6 adds optional local reference voice encoding and selection; see [reference voices](POCKET_REFERENCE_VOICES.md). No quantization, GPU provider or inference service is added. The Android-safe explicit Unicode whitespace ranges in `PocketText.kt` are unchanged. `PocketTokenizer.kt` and the accepted ARM64 JNI bytes are unchanged.

Model pins are in `app/src/main/assets/pocket-model-manifest.json`, byte-identical to the accepted evaluation manifest (SHA-256 `f4150f04019876f1f322a2a06845111efbabae08212c7ed783918aece54770b6`). The graph, config, tokenizer, fixed voice and deterministic noise hashes are checked before loading. The imported manifest must itself match the APK's exact bytes; a ZIP cannot supply a different trust manifest.

The same `pocket-tts-ja-6l-female03-fp32-v1.zip` can be imported:

- ZIP: 191,921,727 bytes; SHA-256 `408939da4678395856b7f63d76fec4c635505dee9ae5fe88212117177bd11e02`.
- Extracted model files: 449,274,848 bytes plus manifest. Not embedded in the APK.
- Supply the compatible ZIP separately. This source repository does not publish the private evaluation archive or its download location. The upstream checkpoint alone is not an importable ONNX pack; the exact manifest and converted-file hashes must match.
- [Model source](https://huggingface.co/takuma104/pocket-tts-ja): weights `ce597c6d2efa5bac163079d6318591de9dbad67a`, config `562a82cba331499b75a050b7e200f4ee3241b9e9`, voice `119685fd7a51356429c6d25e37d570b423c17663`.
- Reviewed exporter `e801e7d6c2692121a39e80ae525cb5265174a495`; SentencePiece `31646a467d2051eb904e0b45de3a73e91fe1c1e3`.

Unknown paths, directories, duplicate entries, incomplete ZIPs, excess lengths and modified hashes are rejected. Import stages the entire installation and commits only after every check; rejected/cancelled imports preserve the previous installation. A successful replacement removes the previous copy. At least model size + 512MiB free storage is required for staging. Normal app-private files and settings do not migrate from `dev.irte.voice`; select its already downloaded ZIP once in this app.

## Lifecycle and audio integration

`PocketSynthesizer` implements the same `SpeechSynthesizer` interface as other engines. A single runtime serializes model loading, inference and release. It stays resident for warm utterances while selected. Selecting another engine closes it; ViewModel/ConversationEngine shutdown uses the existing close path. Loading requires the same 1.5GiB available-memory preflight as the accepted phone build. The standalone phone baseline sampled about 747–807MB process PSS; the integrated app's peak with LLM/STT loaded remains unmeasured. Closing native sessions does not promise immediate allocator/OS memory reclamation.

Each request has a cancellation generation and a coroutine check. Stop terminates the active ORT RunOptions, closes the bounded PCM handoff and invalidates queued/stale requests. Close waits until native use has exited before releasing sessions/tokenizer/voice tensors. The next request resets cancellation only while owning the serialized runtime and cancellation lock. Partial load candidates close on failure or cancellation. Limits remain 1000 input UTF-16 characters, the accepted text/token chunk limits (96 tokens maximum per sentence), finite 1920-sample frames and 30 seconds of output; exceeding a limit is an error, not successful completion.

PCM conversion preserves the accepted build's clip/32767/round-to-nearest conversion. The stream emits 24kHz PCM16 mono in 80ms frames through a rendezvous channel, keeping producer memory bounded. Conversation playback uses the existing `SerialPcmAudioSink`, USB credit, START/END/DONE/ABORT and disconnect handling. No USB writer is added. Since source and Dock are both 24kHz, the existing resampler passes sample values unchanged. The phone preview uses the existing AudioTrack path and generic timing/copyable report; there is no Pocket-specific diagnostics screen. Runtime comparison metrics are recorded by the existing preview diagnostics.

## Conditions

Code and weights have separate licenses. Pocket TTS/exporter code is MIT; SentencePiece is Apache 2.0 with bundled third-party notices; ONNX Runtime is MIT with its notices. The Japanese weights, derivative FP32 graphs and fixed voice state use [CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/) (attribution and noncommercial conditions). Attribution, source, license links and conversion changes are bundled and displayed in the existing voice setup pattern. The confirmation record is separate from other engines. The model evaluation is noncommercial. The application source is published separately under the repository license; no weights, model ZIP, voice WAV or derived voice state is published with it.

## Validation scope

PC CPU1 tests compare Japanese preparation/token IDs and 101 Unicode whitespace cases with the accepted evaluation, compare three generated Float PCM results bit-for-bit with v0.4.1 host output, and check callback stop, explicit stop, stale-request cancellation and deterministic re-speech. This proves the ported orchestration's host behavior; it does not imply bit-identical output across CPU architectures or Android timing.

JVM regression tests cover verified ZIP import/rollback/cancellation/corruption/duplicate entries, missing models, memory preflight, serialized duplicate utterances, bounded output, stop during native work and blocked playback, subsequent re-speech, release/switch and readiness gates. Actual Pocket-generated host PCM is replayed through the existing USB mock and checked byte-for-byte. Existing engine and USB contract/disconnect tests remain part of the full test run. Kotlin compilation, Android lint, signed APK identity/certificate/version, native dependency/namespace checks and 16KiB alignment are verified in the delivery evidence.

The user accepted the standalone Android v0.4.1 build on razr 50 ultra, including custom text. **This integrated APK has not been installed or run on Android or a real Dock.** USB playback/underruns, physical stop/disconnect/reconnect, integrated memory peaks, LLM/STT coexistence and device-side native loading remain to be checked. No COM device, flash, GPU/Vulkan run, push, PR, merge or public release is performed.

## Minimal phone steps

1. Update the existing Dock evaluation app using the new APK; retain app data.
2. In voice settings choose **Pocket 日本語**, tap **評価済みPocketモデルZIPを取り込む**, select the existing ZIP and confirm the noncommercial model conditions. Wait for **準備完了**.
3. Tap the existing **スマホで音声を確認** once, then stop and retry. Connect the Dock and run the usual conversation; check a short utterance, stop/re-speech and disconnect/reconnect before longer use.

If memory preflight fails, close other apps or use the existing lighter conversation-model option and reload Pocket. A selected Pocket engine can be switched back to any of the four existing engines from idle settings.
