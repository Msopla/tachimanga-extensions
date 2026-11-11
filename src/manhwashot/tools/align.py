"""Reescribe el APK dejando resources.arsc sin comprimir y alineado a 4 bytes.

La firma v1 (META-INF) no cubre la estructura del ZIP, así que puede
ejecutarse después de jarsigner, igual que zipalign.
"""
import sys
import zipfile


def main(src, dst):
    zin = zipfile.ZipFile(src)
    with zipfile.ZipFile(dst, 'w') as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            new = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            new.external_attr = info.external_attr
            if info.filename == 'resources.arsc':
                new.compress_type = zipfile.ZIP_STORED
                header_end = zout.fp.tell() + 30 + len(info.filename.encode())
                new.extra = b'\x00' * ((4 - header_end % 4) % 4)
            else:
                new.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(new, data)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
