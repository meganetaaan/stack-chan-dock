# Native build dependencies

These code dependencies are excluded from Git. Models, dictionaries, recordings and voice-state caches are provisioned separately and must not be copied into this directory. A clean clone requires manual native dependency preparation before compiling; Pocket directly imports the isolated Java API, so an absent Pocket AAR cannot be treated as a working optional build.

| App-relative destination | Preparation / provenance |
|---|---|
| app/libs/piper-plus-release.aar | Pinned locally rebuilt v1.13.0; [rebuild record](../../docs/PIPER_REBUILD.md), piper-build-manifest.json and the .aar.sha256 file |
| app/libs/piper-plus-2-isolated.aar | Pinned v2.0.1 official ARM64 core/ORT plus namespace-isolated Kotlin/JNI; [record](../../docs/PIPER2_EVALUATION.md), piper2-build-manifest.json, reviewed JNI/header source under src/main/cpp/piper2 |
| app/libs/voicevoxcore-android-0.17.0.aar | Official Core archive; run scripts/install_voicevox.py from the app root; also installs Core/ORT SOs |
| app/libs/onnx-irodori-isolated.aar | Accepted isolated ORT 1.30.0 CPU package; pocket-source-manifest.json and [source/isolation record](../src/main/cpp/pocket/SOURCE.md) |
| app/src/main/jniLibs/arm64-v8a/libirte_pocket.so | Accepted SentencePiece JNI; source and upstream reference recipe in src/main/cpp/pocket |
| app/src/main/jniLibs/arm64-v8a/libstackchan_sano.so | Build vendored pinned source with scripts/build_sano.py and an existing NDK; [instructions](../../docs/SANO.md) |
| app/src/main/jniLibs/arm64-v8a/libc++_shared.so | Canonical official NDK r30 runtime selected by scripts/select_libcxx.py; libcxx-artifact.json |

Use the already accepted local evaluation build as the source for the custom AARs/tokenizer SO. Their preparation is not automated by the Gradle build or hosted as public binary artifacts by this repository. The corresponding source revisions, transformations and reference build recipes are recorded above; another native build can have different bytes and must receive a new compatibility audit rather than blindly replacing these pins.

Accepted artifact SHA-256 values:

- `piper-plus-release.aar`: `9cfe4064bd9eecc61c21388f2c0d80ca16a1da2ef56a849593d7a91136792f85`
- `piper-plus-2-isolated.aar`: `95e952d8f666cc56b2044feda47ae87a2fffb43596921de6099f65a2d16f9e70`
- `onnx-irodori-isolated.aar`: `4dd3a4e5e9d4284d5c6a8438166ab5e0ad144260907dacb71193a54ad6fa01eb`
- `libirte_pocket.so`: `71acdf79fc81fe0d806eb9af1de9b198edf9830f2d9da6fab7a5a8352c13e1e4`

Keep the unmodified notice assets with these components. In particular, ONNX runtimes use different Java/JNI namespaces and SONAMEs; replacing the isolated packages with a stock Maven AAR can bind the wrong engine at runtime. The canonical libc++ selection must cover every native import, not merely satisfy Gradle's pickFirst. See the APK validation instructions in docs/VOICEVOX.md and scripts/check_tts_apk.py.

After supplying the dependencies, run the build and validation commands in [the integration record](../../docs/ANDROID_INTEGRATION.md). No SDK/NDK download, license acceptance, signing-key retrieval, model acquisition or device installation is performed automatically by this dependency preparation guide.
