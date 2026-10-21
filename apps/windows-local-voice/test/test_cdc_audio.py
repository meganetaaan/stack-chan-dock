import json, pathlib, unittest, sys
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'src'))
from cdc_audio import Frame, encode, Dock
class WireTests(unittest.TestCase):
    def test_published_wire_vectors(self):
        vectors=json.loads((pathlib.Path(__file__).resolve().parents[3]/'contracts/usb-cdc-v2/test-vectors.json').read_text())
        for vector in vectors['validFrames']:
            with self.subTest(name=vector['name']):
                f=vector['frame']
                actual=encode(Frame(f['type'],f.get('flags',0),f.get('streamId',0),f['sequence'],f.get('sampleRate',0),bytes.fromhex(f.get('payloadHex',''))))
                self.assertEqual(actual.hex(),vector['encodedHex'])
        print('Published valid frame vectors checked:',len(vectors['validFrames']))
    def test_payload_limit_before_writing(self):
        with self.assertRaises(ValueError): encode(Frame(2,0,1,0,16000,b'\0'*4097))
    def test_bounded_microphone(self):
        dock=object.__new__(Dock)
        with self.assertRaises(ValueError): dock.record(60)
    def test_bounded_speaker(self):
        dock=object.__new__(Dock)
        with self.assertRaises(ValueError): dock.play_pcm(b'\0'*(32000*16))
if __name__=='__main__': unittest.main()
