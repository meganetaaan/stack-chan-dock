#!/usr/bin/env python3
"""Select a pinned canonical arm64 libc++ after either local AAR installation."""
import hashlib
import json
from pathlib import Path
import zipfile

APP = Path(__file__).resolve().parents[1]
NDK_R30_SHA256 = "7466ed097631a564ed6bff0b47768caa76bf03270d2047b5a65c03f497b824df"
CORE_SHA256 = "d523468d62d9b603cb3354294d70d4b2feabf2c3f1e43b0c96c9aabf32813708"
ENTRY = "jni/arm64-v8a/libc++_shared.so"

def select():
    candidates = [
        (APP/"app/libs/piper-plus-release.aar", NDK_R30_SHA256, "official Android NDK r30 (30.0.16248370) runtime in locally rebuilt Piper AAR"),
        (APP/"app/libs/voicevoxcore-android-0.17.0.aar", CORE_SHA256, "official VOICEVOX Core 0.17.0 AAR"),
    ]
    for path, digest, source in candidates:
        if not path.exists():
            continue
        with zipfile.ZipFile(path) as archive:
            if ENTRY not in archive.namelist():
                continue
            data = archive.read(ENTRY)
        if hashlib.sha256(data).hexdigest() != digest:
            # An older/different Piper AAR must be audited separately; Core is the existing fallback.
            if path.name.startswith("piper-"):
                continue
            raise ValueError("Official Core libc++ integrity mismatch")
        destination = APP/"app/src/main/jniLibs/arm64-v8a/libc++_shared.so"
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
        record = dict(sha256=digest, bytes=len(data), source=source, source_aar=path.name,
            rationale="NDK r30 Piper imports std::__ndk1::__hash_memory, absent in Core's older runtime; all packaged imports require a static audit")
        (APP/"app/libs/libcxx-artifact.json").write_text(json.dumps(record,indent=2)+"\n",encoding="utf-8")
        print("Canonical libc++ selected:", source)
        return record
    print("No pinned canonical libc++ source installed yet")
    return None

if __name__ == "__main__":
    select()
