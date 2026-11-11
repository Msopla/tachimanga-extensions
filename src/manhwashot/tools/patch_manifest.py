"""Cambia versionCode/versionName en un AndroidManifest.xml binario (AXML)."""
import struct
import sys


def read_pool(d, off):
    _, hsz, _ = struct.unpack_from('<HHI', d, off)
    n, _, flags, so, _ = struct.unpack_from('<IIIII', d, off + 8)
    offs = struct.unpack_from('<%dI' % n, d, off + hsz)
    if flags & 0x100:
        raise SystemExit('Solo se admite string pool UTF-16')
    out = []
    for o in offs:
        p = off + so + o
        ln = struct.unpack_from('<H', d, p)[0]
        out.append((p, ln, d[p + 2:p + 2 + 2 * ln].decode('utf-16le')))
    return out


def main(src, dst, code, name):
    d = bytearray(open(src, 'rb').read())
    pool_off = 8
    pool = read_pool(d, pool_off)
    strings = [s for _, _, s in pool]
    vc, vn = strings.index('versionCode'), strings.index('versionName')
    pool_size = struct.unpack_from('<I', d, pool_off + 4)[0]
    off = pool_off + pool_size
    done = set()
    while off < len(d):
        ctype, hsz, csz = struct.unpack_from('<HHI', d, off)
        if ctype == 0x0102:  # START_ELEMENT
            attr_start, attr_size, attr_count = struct.unpack_from('<HHH', d, off + 16 + 8)
            base = off + 16 + attr_start
            for i in range(attr_count):
                a = base + i * attr_size
                _, aname, raw = struct.unpack_from('<iii', d, a)
                if aname == vc:
                    struct.pack_into('<I', d, a + 16, int(code))
                    done.add('code')
                elif aname == vn:
                    p, ln, old = pool[raw]
                    if len(name) != ln:
                        raise SystemExit('versionName debe tener %d caracteres (actual %r)' % (ln, old))
                    d[p + 2:p + 2 + 2 * ln] = name.encode('utf-16le')
                    done.add('name')
        off += csz
    if done != {'code', 'name'}:
        raise SystemExit('No se encontraron versionCode/versionName')
    open(dst, 'wb').write(d)


if __name__ == '__main__':
    main(*sys.argv[1:5])
