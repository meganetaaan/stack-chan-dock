#!/usr/bin/env python3
"""Install pinned official VOICEVOX native dependencies; does not accept model terms or fetch models.

Only modifies this checkout's app/libs, app/src/main/jniLibs and app/src/main/assets/voicevox-notices.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile
import urllib.request
import zipfile
from select_libcxx import select

APP = Path(__file__).resolve().parents[1]
PACKAGES = (
    ("java_packages.zip", "https://github.com/VOICEVOX/voicevox_core/releases/download/0.17.0/java_packages.zip",
     "e22f120adfb1a680bafd01287bc7aa3f104aa7e437d5d710107a16036c38b017"),
    ("voicevox_onnxruntime-android-arm64-1.23.2.tgz", "https://github.com/VOICEVOX/onnxruntime-builder/releases/download/voicevox_onnxruntime-1.23.2/voicevox_onnxruntime-android-arm64-1.23.2.tgz",
     "43e6fc2a89ea6412d4cc20215042a3b34da22fdf05c1bb76256a686f82378d46"),
)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=APP/".toolchains/voicevox")
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    for name, url, digest in PACKAGES:
        path = args.cache/name
        if not path.exists():
            partial = path.with_suffix(path.suffix+".part")
            urllib.request.urlretrieve(url, partial)
            if hashlib.sha256(partial.read_bytes()).hexdigest() != digest:
                partial.unlink()
                raise ValueError(f"SHA-256 mismatch: {name}")
            partial.replace(path)
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError(f"Corrupt cache: {name}; remove the cached archive and retry")
    libs = APP/"app/libs"; libs.mkdir(exist_ok=True)
    native = APP/"app/src/main/jniLibs/arm64-v8a"; native.mkdir(parents=True, exist_ok=True)
    notices = APP/"app/src/main/assets/voicevox-notices"; notices.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.cache/PACKAGES[0][0]) as packages:
        aar = packages.read("jp/hiroshiba/voicevoxcore/voicevoxcore-android/0.17.0/voicevoxcore-android-0.17.0.aar")
        (libs/"voicevoxcore-android-0.17.0.aar").write_bytes(aar)
        select()
    with tarfile.open(args.cache/PACKAGES[1][0]) as archive:
        prefix = "voicevox_onnxruntime-android-arm64-1.23.2/"
        for name, destination in [("lib/libvoicevox_onnxruntime.so", native/"libvoicevox_onnxruntime.so"),
                                  ("TERMS.txt", notices/"ORT-TERMS.txt"),
                                  ("third-party-notices.html", notices/"ORT-third-party-notices.html")]:
            member = archive.getmember(prefix+name)
            if not member.isfile():
                raise ValueError("Unexpected official archive entry")
            destination.write_bytes(archive.extractfile(member).read())
    manifest = {name: {"url": url, "sha256": digest} for name, url, digest in PACKAGES}
    manifest["libc++_shared.so"] = json.loads((libs/"libcxx-artifact.json").read_text(encoding="utf-8"))
    (libs/"voicevox-artifacts.json").write_text(json.dumps(manifest, indent=2)+"\n", encoding="utf-8")
    print("Pinned official VOICEVOX Core AAR / ORT / canonical libc++ installed")

if __name__ == "__main__":
    main()
