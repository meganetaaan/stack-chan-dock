# VOICEVOX selectable TTS

The conversation engine consumes the same `SpeechSynthesizer.AudioChunk(sampleRate, ShortArray)` for either backend. Backend-specific loading no longer belongs to the common playback interface. Settings and first-run setup select Piper Plus or **VOICEVOX:ずんだもん**. Only Zundamon normal style **3** is exposed in the app. The official 0.vvm container also includes other voice metadata; those characters and styles are not exposed.

Piper's model store, manual import, recommended voice, runtime adapter and USB playback remain available. Its optional AAR is still installed by the existing `install_piper_aar.sh`. VOICEVOX does not replace its files.

## Build

Use an already approved JDK 17 / Android SDK 37 environment. Install the pinned official dependencies:

```sh
python3 scripts/install_voicevox.py
./scripts/dev.sh ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The install script validates the official Core 0.17.0 `java_packages.zip` and ORT Android arm64 1.23.2 archive hashes before copying the original AAR and shared libraries. Binaries remain ignored by Git. The archive hashes and provenance are recorded in `app/libs/voicevox-artifacts.json`. It does not fetch a model or accept model/character terms. The optional Piper AAR must also be installed to produce an APK with both working runtimes.

The application retains minSdk 26. VOICEVOX is disabled below **API 29**, matching the Core JNI Android ABI note. Its ORT binary carries API 27, so API 29 is the limiting native requirement. VOICEVOX classes do not initialize JNI while the Piper backend is selected.

For distribution, pass `-PnativeNdkPath=/absolute/path/to/already-installed/android-ndk-r30`. The build reads `Pkg.Revision` from that directory and uses its official strip tool without requesting an SDK/NDK download. Native debug information in some upstream RunAnywhere AARs otherwise makes the APK unnecessarily large. Perform a clean app build when making this packaging change: incremental APK rewriting can retain large unreferenced ZIP regions. The original dependency artifacts remain unchanged, and all backends stay included. `check_tts_apk.py` compares every allocated ELF section and LOAD segment of the stripped libc++ with the pinned original, rather than expecting debug-symbol bytes to remain identical.

`libvoicevox_onnxruntime.so` loads by absolute `nativeLibraryDir` path, separately from RunAnywhere's `libonnxruntime.so` and Piper's isolated ORT. Legacy native packaging extracts libraries to that directory. One app-local `libc++_shared.so` is selected by `select_libcxx.py`: the corrected Piper r30 build requires the official NDK r30 runtime bundled in its AAR, while a Core-only build uses the original Core runtime. Both are pinned and provenance is recorded. Verify the resulting APK hash and ABI imports when changing any native dependency. A packaging pick-first alone is not proof of compatibility. The original Core runtime lacks `std::__ndk1::__hash_memory` required by the rebuilt Piper.

## Assets and terms

The app asks each installation's user to confirm the approved [model terms](https://github.com/VOICEVOX/voicevox_vvm/releases/download/0.16.4/TERMS.txt), [runtime terms](https://github.com/VOICEVOX/onnxruntime-builder/releases/tag/voicevox_onnxruntime-1.23.2), and [Zundamon voice guidelines](https://zunko.jp/con_ongen_kiyaku.html). Repository development approval is not written into another user's app preferences. The credit **VOICEVOX:ずんだもん** appears during setup, in settings and during conversation. Publishing synthesized speech requires that credit and compliance with the linked terms.

`files/voicevox/0.16.4/` stores the pinned 0.vvm and Open JTalk UTF-8 dictionary 1.11. Download sizes and SHA-256 values are pinned. The VVM digest comes from GitHub's official release asset metadata. The dictionary digest was calculated from the official SourceForge archive; it is an integrity pin, not a publisher signature. Every dictionary file including COPYING has its own pinned hash. Verified cached bytes are rehashed before loading. A corrupted download marker is invalidated so setup can repair the file.

Only fixed dictionary filenames under the archive's expected prefix are extracted. Tar headers/checksums, sizes, required files and hashes are checked; links, traversal, duplicates and special entries are rejected. Extraction uses a staging directory and backup. An interrupted replacement recovers the backup on the next attempt. Model/range downloads reuse the existing bounded-size download and atomic-file installation mechanism.

Original Core, runtime, dictionary and model notices are included under `assets/voicevox-notices/`; dictionary COPYING is also kept with the installed dictionary. Models and dictionary bytes are downloaded directly by the app rather than bundled into the APK.

## Synthesis and cancellation

The official Java API returns a complete WAV synchronously. Synthesis runs on `Dispatchers.Default`; loading and asset work run on `Dispatchers.IO`. WAV decoding validates RIFF boundaries and chunks and accepts only **24 kHz / mono / PCM16**. Unknown padded RIFF chunks are allowed. The existing conversation engine, resampling, queue and USB playback consume the decoded PCM.

A generation counter invalidates a request on stop. A new request cannot re-enable an earlier delayed result. Coroutine cancellation is also checked after JNI returns. This suppresses playback but does not interrupt Core's native synchronous computation.

Model unloading on backend switch is explicit. Core Java 0.17 uses finalizers for Synthesizer/OpenJtalk native handles; immediate process-memory reclamation is not guaranteed. Phone measurements with Whisper and the selected LLM loaded remain necessary.

## Verification and device measurements

JVM tests cover PCM signed samples, malformed/unsupported WAVs, odd RIFF padding, delayed synthesis after stop and a newer request, backend readiness, tar traversal/links, missing files, and (with a fixture) official dictionary extraction and crash/corruption recovery:

```sh
./scripts/dev.sh ./gradlew :app:testDebugUnitTest \
  -PvoicevoxDictionaryFixture=/absolute/path/open_jtalk_dic_utf_8-1.11.tar.gz
```

`VoicevoxDeviceTest` is an opt-in instrumentation test after assets have been prepared in the app. It records load and first/repeated synthesis times, process PSS and native heap in `voice-diagnostics/voicevox-device-benchmark.txt`. It neither downloads assets nor accepts terms. It does not measure LLM coexistence. Run it only after separately authorizing installation/testing on a phone. Measure concurrent Whisper/LLM/VOICEVOX memory, native load/coexistence, USB playback, stopping and low-memory behavior on the actual target phone. PC timings must not be used as phone estimates.

The corrected 2026-10-10 evaluation build includes a locally rebuilt Piper Plus v1.13.0 AAR from official source commit `7d3aa34e7acf5f8c91d6ad280aefbb369905db2a`. The previous production AAR was not recovered; the build manifest records the new hash and toolchain. All 24 packaged native libraries pass static 16 KiB ELF alignment and the canonical libc++ symbol checks. Device loading and 16 KiB Android execution have not been tested.

## Evaluation APK and installation impact

Build with `-PvoicevoxEvaluation=true` to use `jp.stackchan.localvoicepoc.voicevoxeval`, debug signing, versionCode 1, versionName `0.1.0-poc-voicevox-eval`, and label `StackChan VOICEVOX Eval`. This package coexists with `jp.stackchan.localvoicepoc` and has separate models, preferences and USB permissions. It cannot update that production package. Android may ask which app should handle the USB device; choose deliberately. No phone installation was performed.

The normal build keeps `jp.stackchan.localvoicepoc`, versionCode 1 and versionName `0.1.0-poc`. Publishing an update still requires the actual installed app's signing certificate and an appropriate versionCode. Debug APK certificate equality with a known local debug build does not establish compatibility with an unseen production installation. Private signing keys were not inspected.

## Minimal GUI audition

1. Open the evaluation app on Android 10 or later. In first-run setup, select `VOICEVOX:ずんだもん`; in a fully prepared app, open Settings and select the same backend.
2. Tap `VOICEVOX を準備`, review the three terms links and tap `同意して準備`. Wait for download, hash verification, dictionary extraction and model loading.
3. Tap `スマホで音声を試聴`. The phone speaker plays `こんにちは。スタックちゃんです。` in normal style 3. `試聴を停止` cancels playback and suppresses delayed synthesis results. The first-run VOICEVOX card is available before downloading LLM/Whisper. LLM and USB are not required for this audition.
4. To use Piper, select `Piper Plus` and load its existing imported or recommended assets, then use the same preview button. Piper voice terms remain separate and must be reviewed before a fresh model download.

The VOICEVOX model download is 58,214,379 bytes and the dictionary archive is 23,643,819 bytes: **81,858,198 bytes (81.9 MB / 78.07 MiB)** total. Downloaded archives, extracted dictionary and staging/backup during preparation need additional storage. This excludes APK transfer and LLM/Whisper downloads. The evaluation package does not reuse the production package's stored files. Only normal style 3 is selectable in Android; the three PC audition files do not imply additional Android styles.

`TtsBackendDeviceTest` compiles a no-model native load check and a Piper → VOICEVOX → Piper synthesis check using user-prepared assets. It was not executed on a phone. JVM tests additionally cover preview sample rates, cancellation before playback, abort after failure and mutation/conversation gates while previewing.


## ローカル診断 APK: 再生開始時間

VOICEVOX を準備した後、初期設定または設定の音声カードで「開始時間を診断（CPU 1/2/4/auto）」を押します。1・2・4・auto(0) の順で一つずつロードし、各設定で初回と warm 2 回、計 12 回の試聴を行います。「診断結果をコピー」は結果全体を Android のクリップボードへコピーします。画面には末尾の結果を表示します。結果はクラウドへ送信せず、アプリプロセス内に保持します。

- `workStart`: 各試行の要求からバックグラウンド処理開始まで。
- `query`: `createAudioQuery`（OpenJtalk・音高/長さ推定・Java JSON変換を含む）。
- `synthesisWav`: `synthesis(query,3).perform()`（Java JSON変換・推論・WAV生成を含む）。通常の `tts()` はネイティブ内部で一括処理するため、診断には分割APIの変換オーバーヘッドがあります。
- `wavDecode`: 完成WAVからPCM16への変換。
- `audioTrackCreate` と `playApi`: API実行区間。`playCall`、`firstWriteCall/Return` は要求からのAPI時刻です。最初のwriteは最大240フレーム（24kHzで10ms）。その後も10ms単位でwriteし出力を観測するため、通常の全文writeと呼出し条件が異なります。PCM・声・音高・速度・バッファ容量は変えません。
- `firstPositivePlaybackHeadObserved`: 再生ヘッドが正になった最初の観測。観測の遅れを含む上限で、実際の発音開始ではありません。
- `firstAudioTimestampObserved` / `frameZeroOutputEstimate`: AudioTimestamp が取得できた観測時刻と、framePosition/sampleRate から算出したframe0の出力時刻推定。取得できない端末・経路では項目がありません。API `play()` の時刻ともスピーカーから聞こえる時刻とも異なります。
- `prePhonemeLength` / `pcmAbs100Offset`: queryの無音設定と、PCMの振幅絶対値100に最初に達する位置。振幅しきい値は可聴性の判定ではありません。
- `pssKiB` / `nativeHeapBytes`: 各試行後のプロセススナップショット。ピークやVOICEVOX単独の使用量ではありません。LLMのロード有無も記録します。

初回は新しいSynthesizerをロードした後の最初の合成です。プロセス・OSファイルキャッシュ・ORT singletonは維持されており、process-cold計測ではありません。固定順の比較には端末温度や周波数の時間変化も含まれます。

スレッド数を切り替える前にモデルを明示unloadし、Synthesizer/OpenJtalkを参照から外します。Core Java 0.17はfinalizerでnative handleを解放するため、PhantomReferenceのqueue到着を待ちます。GC要求だけで解放済みとは判断しません。約3秒のポーリング内に回収を確認できなければ比較を停止し、未回収のまま次のエンジンを作りません。allocatorやORT singletonのためPSSが元の値に戻る保証はありません。

停止は同期ネイティブ計算自体を中断できません。処理が戻った後にキャンセルと世代番号を確認し、古いPCM・未完了結果を破棄します。再生を停止・解放し、回収を確認した後に通常設定CPU4を復元します。復元中も再生・バックエンド変更・会話開始を禁止します。回収/復元に失敗した場合は結果に理由を残し、VOICEVOX未準備として再準備が必要になります。

通常VOICEVOXは従来の同期 `tts`、Piperは既存のFlowを使います。端末用回帰テストには通常VOICEVOXと分割query経路のPCM一致チェックを追加しましたが、端末実行は別途必要です。この診断実装の配布・実機インストール・Drive/Library更新は行っていません。
