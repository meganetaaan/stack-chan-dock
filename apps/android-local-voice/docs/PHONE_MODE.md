# Android standalone conversation

This local evaluation adds explicit **スマホ / 接続端末** audio selection to the existing conversation pipeline. The same STT, LLM and selected Piper 1.13 / Piper 2.0.1 / VOICEVOX / sanoTTS / Pocket engine are reused. Models, licenses, engine preferences, conversation history and USB protocol are retained. App ID `jp.stackchan.localvoicepoc.sanoeval` and the existing debug signing certificate are unchanged. This update is versionCode 4 / `0.1.3-phone-sano-eval`; the launcher name is simply `スタックちゃん`.

## User flow

The initial surface is the conversation screen, including when assets need setup. Select **スマホ** at the top. Existing installations default to **接続端末** until explicitly changed; the selection is saved. Connecting/disconnecting USB never changes it. Conversation preparation and model conditions live in settings. Permission, missing assets and a disconnected selected device each have a short actionable state on the main screen. Benchmark timing, logs and thread comparison remain behind **詳細な音声診断**; no migration, model hashes or implementation history appears on the ordinary conversation screen.

In phone mode the start action requests `RECORD_AUDIO` only when tapped. Granting permission changes readiness but does not start recording automatically: tap **話しはじめる** or the automatic-conversation action once more. Denial never starts capture; **権限の設定** opens this app's Android permission settings. Return to the app and explicitly start after allowing it. With already prepared models no USB connection or new model download is required. If models are missing, open **設定で準備** first.

Phone mode uses the existing `AndroidMicrophoneSource` (16kHz mono PCM16, 20ms chunks matching VAD) and `AndroidPcmAudioSink`. The same `ConversationEngine` switches its audio devices only after stop/cancellation/join completes. No alternate STT/LLM service or new audio wrapper is introduced. All selected voices retain their sample rates through AudioTrack. Push-to-talk input is capped at 30 seconds; automatic capture keeps its existing duration/speech endpoint bounds.

## Lifecycle and focus

- The microphone is closed before recognition and speaker output. Automatic mode starts its next listen only after playback drain finishes; no background listening or automatic resume is added.
- Switching mode first blocks new starts/model mutations, cancels preview/conversation, waits for old work, then swaps the source/sink and saves the choice. It does not close loaded models or change the selected TTS. A second tap cannot submit the same push-to-talk recording twice.
- Phone conversation requests transient speech audio focus before capture starts. Delayed focus is not accepted; loss, transient loss or ducking requests stop the conversation. Abandoned request callbacks cannot stop a later session. Normal completion, explicit stop, mode change, background and ViewModel shutdown abandon the request. Focus gain does not restart recording/playback. The ordinary phone preview also obtains focus.
- Activity `onStop` immediately stops phone AudioRecord/AudioTrack, cancels preview and stops the existing conversation engine. `onResume` only refreshes permission and foreground state. Permission results never resume an old request.
- Each microphone capture owns its recorder and stop flag; cleanup from an older stream cannot release a new recording. Setup/read errors propagate, all initialization exits release resources, and PCM handoff is bounded without silent dropping.
- USB disconnect still stops an active **接続端末** session. It does not stop a **スマホ** session. Dock remote conversation start/stop is rejected in phone mode; phone speech and status are not routed to the Dock. Existing USB attach uses the same singleTask Activity.

`RECORD_AUDIO` is declared with optional microphone and USB-host features. Android 15+ focus requirements are handled by requiring a foreground Activity; no microphone foreground service or background start is introduced. Platform sources: [audio focus](https://developer.android.com/media/optimize/audio-focus), [runtime permissions](https://developer.android.com/training/permissions/requesting).

## UI review and its limits

The explicitly requested `frontend-design-review` skill was not found. Both cloud catalog pages (37 skills), the executor catalog, checkout `.agents/skills`, local `~/.codex/skills`, plugin cache and vendor skill locations were checked. The executor catalog also reported Windows URI inspection failures, so the actual Windows skill directories were searched directly. The named skill was not applied; the user-authorized fallback was a normal source/UI review.

The review kept the existing Material3 components and hierarchy: conversation title/status, explicit audio choice, short setup/permission recovery, conversation history and start/send/stop actions. Settings retains source/license information and manual model options. Source layout was checked for 344/360dp widths, dark mode and 1.5x font; short microphone-action labels avoid crowding. This is **source review, not a rendered screenshot or device UI pass**.

Android Studio Compose previews: `PhonePermissionPreview` (344dp, 1.5x font), `PhonePreparationPreview` (360dp) and the existing dark large-font conversation preview in `StackChanScreen.kt`. They render state only and do not open a microphone or run models. The host has no configured AVD; no new emulator/framework was added, no physical phone was operated and no permissions were granted during this work.

## Validation

The full JVM suite includes existing engine/model/USB regressions and new tests for starting without USB, denied-permission readiness, all five selected-TTS readiness states, missing assets, mode-change/start gates, real coordinator STT→LLM→TTS with fake phone audio, duplicate send, stop/restart, switching away during speech, permission failure/recovery, and no microphone reopening before speaker drain. These tests do not invoke AudioRecord, AudioTrack, native model inference, external STT/LLM, GPU or COM hardware.

Kotlin compilation, Android lint, project structure and APK assembly are run with one Gradle worker, CPU1 and below-normal build priority. Static APK verification checks unchanged signing identity, ID/version, microphone permission and optional USB feature, 16KiB alignment and preservation of every native library from the prior Pocket APK. No TTS model/native binary is changed.

**Unverified on this integrated APK:** actual Android microphone permission/one-time revocation, audio focus arbitration/phone calls, physical input/output/Bluetooth routing, audible feedback/endpointing/quality, background device behavior, integrated memory/latency, LLM/STT runtime execution and real Dock USB regression. External authentication or inference was not performed. User-operated device validation remains necessary.

No upload, public release, push, PR, merge, flash, COM or NucBox GPU operation is performed. The completed APK's name, size and SHA-256 are reported before any new distribution approval.
