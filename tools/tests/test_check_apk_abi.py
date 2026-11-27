from pathlib import Path
import sys,tempfile,unittest,zipfile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from check_apk_abi import check

class ApkAbiTests(unittest.TestCase):
    def apk(self, entries):
        directory=tempfile.TemporaryDirectory();self.addCleanup(directory.cleanup)
        path=Path(directory.name)/'test.apk'
        with zipfile.ZipFile(path,'w') as z:
            for name,data in entries.items():z.writestr(name,data)
        return path

    def elf(self, machine, bits=2):
        header=bytearray(20);header[:4]=b'\x7fELF';header[4]=bits;header[5]=1
        header[18:20]=machine.to_bytes(2,'little');return header

    def test_dex_only_is_abi_neutral(self):check(self.apk({'classes.dex':b'dex'}))
    def test_native_arm64_and_x86_64_are_valid(self):
        check(self.apk({'lib/arm64-v8a/a.so':self.elf(183),'lib/x86_64/a.so':self.elf(62)}))
    def test_32_bit_and_mislabeled_libraries_are_rejected(self):
        for name,data in [('lib/armeabi-v7a/a.so',self.elf(40,1)),('lib/armeabi-v7a/a.so',self.elf(183)),('lib/arm64-v8a/a.so',self.elf(62))]:
            with self.subTest(name=name),self.assertRaises(ValueError):check(self.apk({name:data}))
    def test_bad_headers_are_rejected(self):
        with self.assertRaises(ValueError):check(self.apk({'lib/arm64-v8a/a.so':b'not-elf'}))
