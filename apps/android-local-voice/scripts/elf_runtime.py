"""Fingerprint ELF64 runtime content while allowing non-allocated symbols to be stripped."""
import hashlib
import struct


def runtime_fingerprint(data):
    if data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected little-endian ELF64")
    shoff = struct.unpack_from("<Q", data, 40)[0]
    shentsize, shnum, shstrndx = struct.unpack_from("<HHH", data, 58)
    headers = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i*shentsize) for i in range(shnum)]
    names_offset, names_size = headers[shstrndx][4:6]
    names = data[names_offset:names_offset+names_size]
    allocated = []
    for name_offset, kind, flags, address, offset, size, link, info, align, entsize in headers:
        if not flags & 2:  # SHF_ALLOC: only these sections are part of runtime loading.
            continue
        name = names[name_offset:].split(b"\0",1)[0].decode("utf-8")
        allocated.append(dict(name=name,type=kind,flags=flags,address=address,size=size,alignment=align,
            sha256=None if kind==8 else hashlib.sha256(data[offset:offset+size]).hexdigest()))
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    loads = []
    for i in range(phnum):
        kind, flags, offset, vaddr, paddr, filesz, memsz, align = struct.unpack_from("<IIQQQQQQ", data, phoff+i*phentsize)
        if kind == 1:
            loads.append(dict(flags=flags,offset=offset,address=vaddr,filesz=filesz,memsz=memsz,alignment=align))
    return dict(identity=data[:24].hex(),allocated_sections=sorted(allocated,key=lambda x:x["name"]),load_segments=loads)
