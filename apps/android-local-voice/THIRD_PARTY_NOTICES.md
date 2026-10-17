# Third-party components

VOICEVOX selectable TTS adds the following components; original notices are retained in `app/src/main/assets/voicevox-notices/` and dictionary COPYING is preserved on installation:

- VOICEVOX Core Java/Android 0.17.0: MIT; official `java_packages.zip`.
- VOICEVOX ONNX Runtime Android arm64 1.23.2: official runtime terms and bundled third-party notices; `ORT-TERMS.txt` and `ORT-third-party-notices.html`.
- Official 0.vvm 0.16.4: model terms and voicebank terms. Only Zundamon normal style 3 is selectable. Required credit: **VOICEVOX:ずんだもん**.
- Open JTalk UTF-8 dictionary 1.11: NAIST/UniDic/HTS/Open JTalk BSD-style terms in `Open-JTalk-dictionary-COPYING.txt`.
- LLVM libc++ shared runtime: Core/runtime notices are retained. The corrected Piper r30 build uses the official NDK r30 runtime and includes its complete toolchain NOTICE under `assets/piper-notices/`.
- Gson 2.10.1 (Apache-2.0), Jakarta Annotation 2.1.1 and Jakarta Validation 3.0.2 (their published Eclipse/Apache license notices).

See [VOICEVOX setup and validation](docs/VOICEVOX.md) for exact versions, integrity verification, native compatibility and remaining device checks.

The corrected evaluation build includes a locally rebuilt official Piper Plus v1.13.0 runtime and ORT 1.20.0. Original MIT/BSD notices for Piper, ORT (including third-party notices), fmt 10.0.0, spdlog 1.12.0, nlohmann JSON 3.11.2, pyopenjtalk-plus 0.4.1.post8, Open JTalk and MeCab are packaged under `assets/piper-notices/`. See [Piper build provenance](docs/PIPER_REBUILD.md). No new Piper voice model is bundled or accepted automatically.

このリポジトリ自身のコードは`LICENSE`のMIT Licenseです。以下は別ライセンスです。
実際に使用・配布する版のライセンスとモデルカードを必ず確認してください。

- LiteRT-LM 0.14.0（Apache License 2.0）
- Gemma 4 E2B IT model（Gemma Terms of Use）
- Gemma 4 E4B IT model（Gemma Terms of Use）
- Agents A1 4B Q4_K_M GGUF model（Apache License 2.0）
- RunAnywhere Kotlin SDK 0.20.10、ONNX backend、llama.cpp backend（Apache License 2.0）
- ONNX Runtime（MIT License。[LICENSE](https://github.com/microsoft/onnxruntime/blob/main/LICENSE)および[ThirdPartyNotices.txt](https://github.com/microsoft/onnxruntime/blob/main/ThirdPartyNotices.txt)）
- sherpa-onnx v1.12.20（Apache License 2.0。Kotlin API定義の一部を変更して使用）
- Whisper Small ONNX model（コードとモデルweightはMIT License。[OpenAI Whisper LICENSE](https://github.com/openai/whisper/blob/main/LICENSE)）
- android-vad WebRTC 2.0.10（MIT License。[LICENSE](https://github.com/gkonovalov/android-vad/blob/main/LICENSE.md)）
- usb-serial-for-android 3.10.0（MIT License）
- Piper Plus Android AAR（MIT License。[Piper Plus LICENSE](https://github.com/ayutaz/piper-plus/blob/v1.13.0/LICENSE.md)。AARへ取り込まれる第三者コンポーネントのnoticeも配布前に確認する）
- Piper Plusつくよみちゃん6言語FP16音声モデル（つくよみちゃんコーパス利用条件）
- OpenJTalk dictionary from the Piper Plus v1.13.0 release archive（辞書アーカイブに同梱されたライセンスおよび[Piper Plus v1.13.0 release](https://github.com/ayutaz/piper-plus/releases/tag/v1.13.0)のnoticeに従う）
- 利用者が選択した任意のPiper Plus日本語音声モデル（モデルごとに条件が異なるため、配布元のモデルカードとデータセット利用条件を個別確認する。アプリは任意モデルを同梱しない）
- AndroidX / Jetpack Compose（Apache License 2.0。[Android Open Source Project license information](https://source.android.com/docs/setup/about/licenses)）
- Kotlin coroutines（Apache License 2.0。[kotlinx.coroutines LICENSE](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt)）
- Model Context Protocol Kotlin SDK 0.14.0（Apache License 2.0）
- Ktor 3.4.3（Apache License 2.0）
- kotlinx.serialization 1.11.0（Apache License 2.0）

各LLMのモデルカードと利用条件は、アプリ内のモデル設定から確認できます。

このPoCにはPiper音声モデル、OpenJTalk辞書、LLM／STTモデル本体を同梱していません。
推奨Piper音声と辞書は、利用条件の確認後に各配布元から端末へ直接取得します。
ローカル開発用のPiper Plus AARは作業用ZIPへ含める場合がありますが、再配布時はPiper Plusと同梱物のライセンスを別途確認してください。

## Pocket TTS Japanese (private noncommercial evaluation)

Pocket TTS 3.3.0 / takuma104 Japanese 6-layer / female_03 uses separately licensed CC BY-NC 4.0 weights and derivative FP32 graphs/voice state. Code is MIT, ONNX Runtime 1.30.0 is MIT, and Google SentencePiece is Apache 2.0 with its third-party notices. Credits, conditions, source pins and modification notes are packaged in `app/src/main/assets/pocket-notices`, `pocket-model-manifest.json` and [docs/POCKET.md](docs/POCKET.md). Models are imported separately and are not embedded. This delivery is restricted to the user's private noncommercial evaluation scope.

## sanoTTS-jp

The optional sanoTTS-jp backend uses pinned source `ayutaz/sanoTTS-jp@3eb73a91acdb8d4bc0cc10c3bc4e670555ce20bc` (MIT), its separately licensed v4 model, NAIST-derived dictionary and OpenJTalk frontend. Full conditions and required v4 attribution are packaged in `app/src/main/assets/sano-notices` and displayed by the app. Model/dictionary bytes are fetched or imported separately and verified against the hashes documented in [docs/SANO.md](docs/SANO.md).
