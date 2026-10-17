#!/usr/bin/env python3
"""Check both TTS native packages and Kotlin contracts without launching Android."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile
from elf_runtime import runtime_fingerprint

APP = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk", type=Path)
args = parser.parse_args()

with zipfile.ZipFile(args.apk) as archive:
    names = set(archive.namelist())
    required = {"lib/arm64-v8a/"+name for name in (
        "libpiper_plus.so", "libpiper_plus_jni.so", "libonnxrtpiper.so",
        "libvoicevox_core_java_api.so", "libvoicevox_onnxruntime.so", "libc++_shared.so",
    )}
    assert required.issubset(names), required - names
    assert "lib/arm64-v8a/libonnxruntime.so" in names  # RunAnywhere remains installed too.
    native_names = sorted(n for n in names if n.endswith(".so"))
    for name in native_names:
        assert name.startswith("lib/arm64-v8a/"), name
        data = archive.read(name)
        assert data[:6] == b"\x7fELF\x02\x01", name
        phoff = struct.unpack_from("<Q",data,32)[0]
        phentsize,phnum = struct.unpack_from("<HH",data,54)
        alignments = [struct.unpack_from("<Q",data,phoff+i*phentsize+48)[0]
            for i in range(phnum) if struct.unpack_from("<I",data,phoff+i*phentsize)[0] == 1]
        assert alignments and min(alignments) >= 16384, (name,alignments)
    for name in ("libpiper_plus.so","libonnxrtpiper.so"):
        data = archive.read("lib/arm64-v8a/"+name)
        assert data.count(b"libonnxrtpiper.so") == 1, name
        assert b"libonnxruntime.so" not in data, name
    jni = archive.read("lib/arm64-v8a/libpiper_plus_jni.so")
    assert b"libpiper_plus.so\x00" in jni
    assert b"libpiper_plus.so.1" not in jni
    for method in ("nativeCreate","nativeSynthesize","nativeSynthStart","nativeSynthNext",
                   "nativeFree","nativeSampleRate","nativeNumSpeakers","nativeNumLanguages"):
        assert ("Java_com_piperplus_PiperPlusNative_"+method).encode() in jni, method
    dex = b"".join(archive.read(n) for n in names if n.endswith(".dex"))
    for descriptor in (b"Lcom/piperplus/PiperPlus;",b"Lcom/piperplus/PiperPlusNative;",
        b"Ljp/hiroshiba/voicevoxcore/blocking/Synthesizer;",b"Ljp/stackchan/localvoicepoc/voicevox/SelectableSynthesizer;"):
        assert descriptor in dex, descriptor
    canonical = (APP/"app/src/main/jniLibs/arm64-v8a/libc++_shared.so").read_bytes()
    packaged_cpp = archive.read("lib/arm64-v8a/libc++_shared.so")
    # Gradle strips non-runtime debug/symbol sections; every allocated section and LOAD stays identical.
    assert runtime_fingerprint(packaged_cpp) == runtime_fingerprint(canonical)
    assert "assets/piper-notices/NDK-r30-toolchain-NOTICE.txt" in names
    assert "assets/voicevox-notices/MODEL-TERMS-0.16.4.txt" in names
print(json.dumps(dict(apk=str(args.apk),native_count=len(native_names),elf_16k=True,
    piper_jni_exports=True,isolated_ort=True,both_backend_classes=True,
    canonical_libcxx_sha256=hashlib.sha256(canonical).hexdigest(),
    packaged_libcxx_sha256=hashlib.sha256(packaged_cpp).hexdigest(),
    canonical_libcxx_runtime_matches=True,device_execution=False),indent=2))
