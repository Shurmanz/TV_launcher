"""Минимальный zipalign: выравнивает несжатые записи ZIP на 4 байта (как zipalign -p 4)."""
import struct, sys, zipfile

def align(src, dst, a=4):
    zin = zipfile.ZipFile(src)
    with open(dst, "wb") as out:
        zout = zipfile.ZipFile(out, "w")
        for info in zin.infolist():
            data = zin.read(info.filename)
            ni = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            ni.compress_type = info.compress_type
            ni.external_attr = info.external_attr
            if info.compress_type == zipfile.ZIP_STORED:
                hdr = 30 + len(info.filename.encode())
                pad = (a - (out.tell() + hdr + 4) % a) % a
                ni.extra = struct.pack("<HH", 0xD935, pad) + b"\0" * pad  # поле выравнивания Android
            zout.writestr(ni, data)
        zout.close()

if __name__ == "__main__":
    align(sys.argv[1], sys.argv[2])
