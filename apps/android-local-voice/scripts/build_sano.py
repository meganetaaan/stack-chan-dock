"""Build only the pinned CPU1/W8A8 sano JNI, using an already installed Android NDK."""
from pathlib import Path
import argparse, hashlib, json, subprocess, platform

app = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--ndk', type=Path, required=True)
args = parser.parse_args()
native = app/'app/src/main/cpp'
source = native/'sano'
manifest = json.loads((source/'SOURCE.json').read_text())
assert manifest['revision'] == '3eb73a91acdb8d4bc0cc10c3bc4e670555ce20bc'
for name, expected in manifest['files'].items():
    assert hashlib.sha256((source/name).read_bytes()).hexdigest() == expected, name
host_tags = {'Windows': 'windows-x86_64', 'Linux': 'linux-x86_64', 'Darwin': 'darwin-x86_64'}
system = platform.system()
if system not in host_tags:
    raise SystemExit(f'Unsupported NDK build host: {system}')
host = host_tags[system]
toolchain = args.ndk/'toolchains/llvm/prebuilt'/host
clang = toolchain/'bin'/('clang.exe' if system == 'Windows' else 'clang')
out = app/'app/src/main/jniLibs/arm64-v8a/libstackchan_sano.so'
out.parent.mkdir(parents=True, exist_ok=True)
sources = [source/name for name in ['saanotts.c','saanotts_stream.c','saanotts_int8.c','fft.c','g2p.c','jdict.c','accent.c','njd_rules.c','label_ids.c','saan_kanji.c']]
command = [clang, '--target=aarch64-linux-android26', '--sysroot='+str(toolchain/'sysroot'),
    '-std=c99','-O3','-fPIC','-shared','-DSAAN_INT8_ACT=1','-DCHARSET_UTF_8','-DLABEL_IDS_EXTERNAL_SCRATCH=1',
    '-I'+str(source),'-I'+str(source/'openjtalk'),native/'sano-jni.c',*sources,*sorted((source/'openjtalk').glob('*.c')),
    '-lm','-Wl,-z,max-page-size=16384','-Wl,-z,common-page-size=16384','-o',out]
subprocess.run(list(map(str,command)),check=True)
(app/'app/libs/sano-artifact.json').write_text(json.dumps({'source':manifest['repository'],'revision':manifest['revision'],
    'abi':'arm64-v8a','min_sdk':26,'w8a8':True,'cpu_threads':1,'gpu':False,
    'bytes':out.stat().st_size,'sha256':hashlib.sha256(out.read_bytes()).hexdigest()},indent=2)+'\n')
print('Built',out)
