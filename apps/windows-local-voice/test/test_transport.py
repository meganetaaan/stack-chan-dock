import json,pathlib,struct,sys,types,unittest
from unittest.mock import patch
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'src'))
from cdc_audio import Dock,Frame,HEADER,encode,assert_target,HELLO_ACK,ERROR,MIC_STOP,SPEAKER_CREDIT,SPEAKER_DONE
class WireSerial:
    def __init__(self,data=b''):self.data=bytearray(data);self.writes=[]
    @property
    def in_waiting(self):return min(1,len(self.data))
    def read(self,n):result=bytes(self.data[:n]);del self.data[:n];return result
    def write(self,data):self.writes.append(data);return len(data)
    def close(self):self.closed=True
class TransportTests(unittest.TestCase):
    def bare(self):
        d=object.__new__(Dock);d.buf=bytearray();d.stream=100;d.control_sequence=0;d.max_payload=4096;d.capabilities=None
        d.frames_received=0;d.crc_errors=0;d.serial=WireSerial();d.active_mic=None;d.active_speaker=None
        return d
    def test_fragmented_frame_after_noise_and_invalid_crc(self):
        good=encode(Frame(0,HELLO_ACK,0,3,0,struct.pack('<II',4096,3967)))
        bad=bytearray(good);bad[-1]^=1
        d=self.bare();d.serial=WireSerial(b'boot noise'+bad+good)
        frame=d.receive(1)
        self.assertEqual(frame.flags,HELLO_ACK);self.assertEqual(d.crc_errors,1);self.assertEqual(d.frames_received,1)
    def test_stale_stream_error_does_not_fail_current_ack(self):
        d=self.bare();d.serial=WireSerial(encode(Frame(0,ERROR,99,0,16000,struct.pack('<I',3)))+encode(Frame(0,SPEAKER_DONE,101,1,16000)))
        self.assertEqual(d.wait_control(SPEAKER_DONE,101).stream_id,101)
    def test_peer_frame_over_negotiated_limit_is_rejected(self):
        d=self.bare();d.max_payload=32;d.serial=WireSerial(encode(Frame(1,0,101,0,16000,b'\0'*34)))
        with self.assertRaisesRegex(RuntimeError,'negotiated payload'):d.receive(1)
    def test_invalid_speaker_rate_is_rejected_and_close_aborts(self):
        d=self.bare();d.serial=WireSerial(encode(Frame(0,SPEAKER_CREDIT,101,0,24000,struct.pack('<I',4096))))
        with self.assertRaisesRegex(RuntimeError,'sample rate'):d.play_pcm(b'\0'*320)
        d.close()
        self.assertTrue(d.serial.closed)
        self.assertEqual(HEADER.unpack_from(d.serial.writes[-1])[3],36)
    def test_stop_retries_only_same_stream_until_ack(self):
        d=self.bare();controls=[];calls=0
        d.control=lambda code,sid,rate:controls.append((code,sid,rate))
        def acknowledge(*args,**kwargs):
            nonlocal calls
            calls+=1
            if calls==1:raise TimeoutError('dropped ack')
            return Frame(0,19,101,0,16000)
        d.wait_control=acknowledge;d.active_mic=101;d.stop_microphone(101)
        self.assertEqual(controls,[(MIC_STOP,101,16000)]*2);self.assertIsNone(d.active_mic)
    def test_hello_negotiates_audio_without_events_and_honors_payload(self):
        d=self.bare();d.serial=WireSerial(encode(Frame(0,HELLO_ACK,0,1,0,struct.pack('<II',512,3967))))
        hello=d.hello();self.assertEqual(hello['maxPayload'],512)
        sent=d.serial.writes[0];maximum,caps=struct.unpack('<II',sent[20:-4])
        vectors=json.loads((pathlib.Path(__file__).resolve().parents[3]/'contracts/usb-cdc-v2/negotiation-vectors.json').read_text())
        canonical=next(v['capabilities'] for v in vectors['helloPayloads'] if v['name']=='required-audio-only')
        self.assertEqual(caps&canonical,canonical);self.assertFalse(caps&(1<<10));self.assertTrue(caps&(1<<4))
        d.serial.data.extend(encode(Frame(0,SPEAKER_CREDIT,101,2,16000,struct.pack('<I',2048)))+encode(Frame(0,SPEAKER_DONE,101,3,16000)))
        result=d.play_pcm(b'\0'*1600)
        packets=[packet for packet in d.serial.writes if HEADER.unpack_from(packet)[2]==2]
        self.assertEqual(result['bytesSent'],1600);self.assertEqual(sum(len(p)-24 for p in packets),1600)
        self.assertTrue(all(len(p)-24<=512 for p in packets))
    def test_identity_mismatch_is_refused_before_open(self):
        ports=types.SimpleNamespace(comports=lambda:[types.SimpleNamespace(device='COM12',vid=0x303a,pid=0x1001,serial_number='OTHER')])
        serial_tools=types.ModuleType('serial.tools');serial_tools.list_ports=ports
        serial_module=types.ModuleType('serial');serial_module.tools=serial_tools
        with patch.dict(sys.modules,{'serial':serial_module,'serial.tools':serial_tools}):
            with self.assertRaisesRegex(RuntimeError,'identity mismatch'):assert_target('COM12','EXPECTED')
if __name__=='__main__':unittest.main()
