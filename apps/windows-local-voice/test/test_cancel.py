import struct,unittest,sys,pathlib
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'src'))
from cdc_audio import Dock,Frame,MIC_START,MIC_STARTED,MIC_STOP,MIC_STOPPED,SPEAKER_START,SPEAKER_ABORT,SPEAKER_CREDIT
class FakeSerial:
    closed=False
    def close(self):self.closed=True
class CancellationTests(unittest.TestCase):
    def dock(self):
        dock=object.__new__(Dock);dock.stream=100;dock.active_mic=None;dock.active_speaker=None
        dock.max_payload=4096
        dock.serial=FakeSerial();dock.controls=[]
        dock.control=lambda code,*args,**kwargs:dock.controls.append(code)
        dock.wait_control=lambda code,stream,*args,**kwargs:Frame(0,code,stream,0,16000)
        return dock
    def test_cancel_mic_sends_stop_and_releases_port(self):
        dock=self.dock()
        with self.assertRaises(InterruptedError):dock.record(3,cancelled=lambda:True)
        self.assertEqual(dock.controls,[MIC_START,MIC_STOP])
        self.assertIsNone(dock.active_mic)
        dock.close();self.assertTrue(dock.serial.closed)
    def test_cancel_speaker_after_credit_sends_abort_and_releases_port(self):
        dock=self.dock();calls=iter([False,True])
        dock.receive=lambda timeout:Frame(0,SPEAKER_CREDIT,101,0,16000,struct.pack('<I',4096))
        result=dock.play_pcm(b'\0'*4096,cancelled=lambda:next(calls))
        self.assertTrue(result['aborted']);self.assertEqual(result['bytesSent'],0)
        self.assertEqual(dock.controls,[SPEAKER_START,SPEAKER_ABORT])
        self.assertIsNone(dock.active_speaker)
        dock.close();self.assertTrue(dock.serial.closed)
if __name__=='__main__':unittest.main()
