# Piper Plus 2 runtime evaluation

This update keeps Piper 1.13, VOICEVOX and sanoTTS and adds **Piper 2.0.1** as a fourth selectable backend. The app ID remains `jp.stackchan.localvoicepoc.sanoeval`; versionCode is 2 and the same local debug signing key is used, so an ordinary update retains the existing app's models, preferences and USB permissions.

## What is updated

The v2 runtime is pinned to `ayutaz/piper-plus@54c6efc0495f1ecae9d91f9935bf8319be233ac2` / `v2.0.1` (the patch release after v2.0.0). The official Android ARM64 core/ONNX Runtime archive SHA-256 is `c5814da74a12826c73efb384d3e6e9435611326755ee965d7789ab5097483fe8`, verified against the GitHub release asset digest and published checksum list. Kotlin/JNI APIs match the existing v1.13 bindings; the reserved C ABI remains API version 1.

The new AAR is locally packaged from those official native binaries and the pinned upstream JNI/Kotlin source. Java package `com.piperplus2`, core `libpiper2plus.so`, JNI `libpiper2plus_jni.so` and ORT `libonnxrpiper2.so` isolate it from the old Piper and other engines. All native libraries have 16KiB ELF alignment. The v2 distribution's ORT is not byte-identical to the existing v1 Maven ORT, so each keeps its own library; the comparison is of the two packaged runtimes, not a claim about one isolated algorithm change. The core synthesis/G2P binary is not rebuilt or modified except the equal-length SONAME references and symbol stripping.

JNI changes are limited to namespace isolation, legacy option values for comparison, and finite/OOM/120-second PCM guards. The old Piper AAR and native bytes remain unchanged. The shared adapter uses request generations so starting a later request cannot revive cancelled PCM; failed/cancelled loading releases its candidate handle. Both engines use CPU; the existing Android thread setting is `num_threads=0` (auto). Native calls remain synchronous, so stop suppresses the result after a native call returns rather than interrupting C code midway.

## Model is deliberately unchanged

Both Piper versions read the existing `PiperPreferences` paths and reuse the same model/config/dictionary. No cache-directory rename, model replacement, new voice, speaker encoder, personal recording, or external inference service is added. Select either Piper version to reload the existing installation automatically; switching is disabled during setup, playback or conversation.

The recommended Japanese baseline is the existing 22,050Hz **Tsukuyomi-chan 6-language FP16** model:

- Hugging Face revision `36b59c825c36bd386b8960cf3f604382f52f2a87`.
- Model 39,652,717 bytes; SHA-256 `5289e9b6eaf21080803b7fe1c4dc85b5491d4c216121207a41df18dd5f68e5d7`.
- Config 6,901 bytes; SHA-256 `516058f405ec914140f34832a9d8bb5d8272ba62af9bc7ffb29349715a539780`.
- OpenJTalk dictionary and the existing terms-confirmation record are retained.

Upstream's v2 migration guide explicitly permits existing piper-plus ONNX inference. This is distinct from its incompatibility with upstream rhasspy/Piper voices. This task additionally verified the exact model/config with the official v2.0.1 Windows C API: four successful, finite, non-silent Japanese runs. That establishes host compatibility; it does not establish Android native execution or audible quality.

For comparison, both Android bindings use the existing v1.13 settings: speaker 0, language auto, noise 0.667, noise_w 0.8, length_scale 1.0 and sentence silence 0.2s. V2 defaults changed to noise 0.4/noise_w 0.5, so accepting its defaults would confound a runtime comparison. The model card recommends length_scale 1.5; it is not adopted only for v2 here because that would also change the comparison condition.

The newer `zs-v1` experimental model is not installed. Its model-quality caveats and incomplete all-runtime validation make it a separate evaluation condition. G2P 1.0.1 alone is not a TTS engine. The pinned C API's speaker encoder remains a placeholder and `encode` returns an unimplemented error; it is not exposed or advertised here.

## Same preview and existing logs

Use the existing **スマホで音声を試す** button after choosing a backend. Each click speaks the same fixed sentence, **こんにちは。スタックちゃんです。**, and adds a copyable report plus an app-local `voice-diagnostics/tts-preview.jsonl` record. No new benchmark framework or microphone capture is added.

The report records generation request, native start/duration for Piper, first PCM, AudioTrack play/write calls, observed positive playback head, estimated frame-zero output timestamp when available, generation completion and playback completion. Derived request-to-PCM/play/completion intervals exclude the preceding model-hash check. PSS and native heap snapshots before/after, model SHA-256, backend, iteration, options and error type identify the comparison condition. Memory peaks and acoustic onset are not measured. Playback completion means the existing sink's drain/finish API returned; hardware measurements remain necessary.

The existing VOICEVOX thread-diagnosis button and USB playback trace logging remain available. Normal conversation continues through the existing `SerialPcmAudioSink`, its resampler, 24kHz PCM16LE mono framing, credit and START/END/DONE/ABORT protocol. A PC mock additionally replays actual v2-generated PCM through that sink.

## Conditions and validation limits

Runtime code is MIT and its third-party notices are bundled under `piper2-notices`. The recommended voice continues to follow the Tsukuyomi-chan corpus's actual conditions; the corpus is not treated as an unrestricted MIT/CC-BY grant merely because a release attribution file uses a label. Required credit and output-use conditions are visible in the Piper settings and the existing official-terms link remains available. No new model conditions or new terms confirmation are introduced for the unchanged voice. The new APK does not contain model, dictionary or personal voice bytes.

Builds use the existing Android/Kotlin/NDK tools, one Gradle worker and low priority; no GPU/Vulkan load is used. JVM regression checks cover the old engines' readiness, cancellation and stale-result suppression, timing/error bookkeeping, and the existing USB contract, credit, stop/restart, disconnect and resampler tests. Static APK checks cover signature continuity, versionCode/app ID, native symbols/namespace isolation, 16KiB alignment, all native dependencies and canonical libc++ imports. The Windows smoke test uses CPU1 and fixed public text.

Unverified: Android ARM64 loading/synthesis, audible quality, real Dock playback/disconnect/interruption, device 16KiB-page execution, memory peaks and coexistence with loaded LLM/STT models. No Android install, hardware flash, COM12 operation, push, release, public deploy or Drive upload was performed by this update.

## Primary sources

- [v2.0.1 release](https://github.com/ayutaz/piper-plus/releases/tag/v2.0.1)
- [Pinned Android source](https://github.com/ayutaz/piper-plus/tree/54c6efc0495f1ecae9d91f9935bf8319be233ac2/android)
- [Pinned migration guide](https://github.com/ayutaz/piper-plus/blob/54c6efc0495f1ecae9d91f9935bf8319be233ac2/docs/migration/v1.12-to-v2.0.md)
- [Fixed model](https://huggingface.co/ayousanz/piper-plus-tsukuyomi-chan/tree/36b59c825c36bd386b8960cf3f604382f52f2a87)
- [Corpus conditions](https://tyc.rei-yumesaki.net/material/corpus/)
- [Separate zs-v1 experiment](https://huggingface.co/ayousanz/piper-plus-base/blob/main/releases/zs-v1/README.md)
