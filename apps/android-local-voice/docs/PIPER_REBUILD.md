# Piper Plus AAR recovery (2026-10-10)

The known Windows checkouts and `stack-chan-dock-tts` APK/build manifest did not contain the original Piper AAR or its native libraries. No `/root` search was performed in this follow-up. The earlier recorded production AAR hash was `b43d4aeb46af952db7205106dfed68a51bab19fc8343370479a467caf9e3b688`; the recovered functionality uses a new local build, not that binary.

The official source is [Piper Plus v1.13.0](https://github.com/ayutaz/piper-plus/tree/v1.13.0), commit `7d3aa34e7acf5f8c91d6ad280aefbb369905db2a`. Its [Android workflow](https://github.com/ayutaz/piper-plus/blob/v1.13.0/.github/workflows/android-build.yml) supplies the native build recipe. That release has no downloadable Android AAR. The source was cloned into an independent directory; no other checkout was edited.

## Actual local build

The build evidence archive contains `prepare_piper_build.py`, `compile_piper_bindings.py`, `package_piper_aar.py`, configure/build logs, API inspection and hashes. The helper scripts record the known local toolchain paths used here. Adjust those paths before replaying on another machine. They use existing approved tools; no Android SDK/NDK license acceptance or publishing step is involved.

1. Clone the official tag into `piper-source` and verify the exact commit above.
2. Run `prepare_piper_build.py`. It downloads Microsoft's official ORT Android 1.20.0 AAR and Linux header archive. The AAR SHA-256 is checked against Maven's checksum; both payload digests are recorded. Only arm64 native code and header files are used from those packages.
3. Add this packaging-only adjustment to the end of the local upstream `CMakeLists.txt`:

```cmake
if(ANDROID AND TARGET piper_plus)
  set_target_properties(piper_plus PROPERTIES VERSION "" SOVERSION "")
endif()
```

Without it, the desktop `.so.1` SONAME can become an Android JNI dependency that the package manager does not extract. There are no changes to synthesis or phonemization source code.

4. Configure and build the official native source. Here `NDK` points to the already installed r30, `ORT` to `piper-dependencies/ort-android`, and CMake/Ninja are already installed. The official workflow uses r26c; this local build uses r30/Clang 21, API 24 and shared libc++, and records that difference.

```sh
cmake -S piper-source -B piper-build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
  -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release \
  -DPIPER_PLUS_BUILD_SHARED=ON -DBUILD_TESTS=OFF \
  -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 \
  -DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384 \
  -DONNXRUNTIME_DIR="$ORT" -DONNXRUNTIME_INCLUDE_DIR="$ORT/include" \
  -DONNXRUNTIME_LIB="$ORT/lib/libonnxruntime.so"
cmake --build piper-build --target piper_plus --parallel 4
```

5. Copy `piper-build/libpiper_plus.so` to `piper-source/android/piper-plus/src/main/jniLibs/arm64-v8a/`. Configure `piper-source/android/piper-plus/src/main/cpp` into `piper-jni-build-approved` with the same NDK/ABI/API/STL/page flags, then build `piper_plus_jni`.
6. Run `compile_piper_bindings.py`. It compiles the three unchanged official Kotlin files with existing Kotlin 2.4.0, JDK 17, `android-37.0/android.jar`, coroutines 1.11.0 and JVM target 11. It does not use the upstream Android Gradle publishing project. The compiled `create(Context, String, String, String)`, `getSampleRate()`, `synthesize(String, int)` and `close()` match the existing reflection adapter.
7. Run `package_piper_aar.py`. It strips native debug symbols, packages the classes/native code, official NDK r30 libc++ and original notices, and invokes this repository's existing `isolate_piper_onnx.py`. Piper's ORT SONAME becomes `libonnxrtpiper.so`; RunAnywhere and VOICEVOX retain their independent runtime names. It writes `app/libs/piper-plus-release.aar`, `.aar.sha256` and `piper-build-manifest.json`. Run `scripts/select_libcxx.py` (also called by either AAR installer) to select the pinned NDK r30 runtime as the app-local canonical copy.

The resulting AAR is 9,827,976 bytes, SHA-256 `9cfe4064bd9eecc61c21388f2c0d80ca16a1da2ef56a849593d7a91136792f85`. Copying this saved artifact and running `install_piper_aar.sh` avoids rebuilding it. The byte hash of another build may differ with toolchain or archive timestamps; re-run the artifact and APK checks rather than assuming equivalence.

Piper compiled with r30 imports `std::__ndk1::__hash_memory`, absent in Core's older libc++. The canonical r30 runtime SHA-256 is `7466ed097631a564ed6bff0b47768caa76bf03270d2047b5a65c03f497b824df` (Android API note 21, 16 KiB alignment). It supplies the required symbol and passes static coverage against the older Core and RunAnywhere runtimes as well. `select_libcxx.py` selects these pinned bytes from the rebuilt Piper AAR; its result and provenance are recorded in `app/libs/libcxx-artifact.json`. The original r30 toolchain NOTICE is retained. Runtime ABI/coexistence still requires phone testing.

## Validation scope

`check_project.py` checks the installed checksum and isolated ORT entry. Static ELF inspection checks the Piper/JNI SONAME dependency chain, required symbols, 16 KiB alignment, API notes (Piper/JNI 24, Piper ORT 21), and coverage by the app's canonical NDK r30 libc++. The actual evaluation APK contains all three Piper libraries, official Kotlin classes and the VOICEVOX native pair.

These checks and compiled instrumentation tests are not an Android runtime test. Native coexistence, switching with real models, audible playback, phone cold/warm synthesis and LLM coexistence memory remain unverified. No phone install, flash, push, PR, merge or secret retrieval was performed. MIT/BSD notices for Piper, ORT, fmt, spdlog, JSON, Open JTalk and MeCab are included under `assets/piper-notices/`; no additional click-through model or commercial license was required to rebuild the existing runtime.
