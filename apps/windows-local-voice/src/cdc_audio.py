"""Local Stack-chan CDC v2 transport. No motion, expressions, network or firmware writes."""
import struct, time, zlib, wave
from dataclasses import dataclass

HEADER = struct.Struct('<HBBHHIII')
MAGIC, VERSION = 0x5343, 2
HELLO, HELLO_ACK, ERROR = 1, 2, 3
MIC_START, MIC_STARTED, MIC_STOP, MIC_STOPPED = 16, 17, 18, 19
SPEAKER_START, SPEAKER_CREDIT, SPEAKER_END, SPEAKER_DONE, SPEAKER_ABORT = 32, 33, 34, 35, 36
STREAM_ID_CAPABILITY = 1 << 9
REQUIRED_AUDIO_CAPABILITIES = 551
HOST_AUDIO_CAPABILITIES = REQUIRED_AUDIO_CAPABILITIES | (1 << 3) | (1 << 4)

@dataclass
class Frame:
    type: int
    flags: int
    stream_id: int
    sequence: int
    sample_rate: int
    payload: bytes = b''

def encode(f):
    if len(f.payload) > 4096: raise ValueError('Payload exceeds v2 limit')
    body = HEADER.pack(MAGIC, VERSION, f.type, f.flags, f.stream_id, f.sequence, f.sample_rate, len(f.payload)) + f.payload
    return body + struct.pack('<I', zlib.crc32(body))

def assert_target(port,expected_serial):
    from serial.tools import list_ports
    if not expected_serial: raise ValueError('Explicit target USB serial required')
    candidates = [p for p in list_ports.comports() if p.device.upper() == port.upper()]
    if len(candidates) != 1: raise RuntimeError('Target COM port not uniquely present')
    p = candidates[0]
    if (p.vid,p.pid) != (0x303a,0x1001) or (p.serial_number or '').upper() != expected_serial.upper():
        raise RuntimeError('USB identity mismatch; refusing to open')
    return p

class Dock:
    def __init__(self, port,expected_serial):
        import serial
        assert_target(port,expected_serial)
        self.serial = serial.Serial(port=None, baudrate=115200, timeout=0.05, write_timeout=3)
        self.serial.dtr = False
        self.serial.rts = False
        self.serial.port = port
        self.serial.open()
        self.buf = bytearray()
        self.control_sequence = 0
        self.stream = 100
        self.active_mic = None
        self.active_speaker = None
        self.frames_received = 0
        self.crc_errors = 0
        self.capabilities = None
        self.max_payload = 4096

    def send(self,f):
        if len(f.payload)>self.max_payload: raise ValueError('Payload exceeds negotiated limit')
        data = encode(f)
        n = self.serial.write(data)
        if n != len(data): raise RuntimeError('Short serial write')

    def control(self, code, stream=0, sample_rate=0, payload=b''):
        self.send(Frame(0,code,stream,self.control_sequence,sample_rate,payload))
        self.control_sequence += 1

    def receive(self, timeout=5):
        deadline = time.monotonic()+timeout
        while time.monotonic()<deadline:
            while len(self.buf)>=HEADER.size:
                if self.buf[:2]!=b'CS':
                    del self.buf[0]; continue
                magic,version,kind,flags,sid,seq,rate,n = HEADER.unpack_from(self.buf)
                if version!=2 or n>4096:
                    del self.buf[0]; continue
                length=HEADER.size+n+4
                if len(self.buf)<length: break
                raw=bytes(self.buf[:length]); del self.buf[:length]
                if zlib.crc32(raw[:-4])!=struct.unpack('<I',raw[-4:])[0]:
                    self.crc_errors += 1; continue
                if n>self.max_payload: raise RuntimeError('Frame exceeds negotiated payload limit')
                self.frames_received += 1
                f=Frame(kind,flags,sid,seq,rate,raw[20:-4])
                return f
            self.buf.extend(self.serial.read(max(1,min(self.serial.in_waiting,32768))))
        raise TimeoutError('CDC frame timeout')

    def check_error(self,f,stream):
        if f.type==0 and f.flags==ERROR and f.stream_id in (0,stream):
            code=struct.unpack('<I',f.payload.ljust(4,b'\0')[:4])[0]
            raise RuntimeError('Device protocol error code '+str(code)+' stream '+str(f.stream_id))

    def wait_control(self,code,stream=0,timeout=5):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            f=self.receive(max(0.01,deadline-time.monotonic()))
            self.check_error(f,stream)
            if f.type==0 and f.flags==code and f.stream_id==stream: return f
        raise TimeoutError('Control acknowledgement timeout '+str(code))

    def hello(self):
        self.control(HELLO,payload=struct.pack('<II',4096,HOST_AUDIO_CAPABILITIES))
        f=self.wait_control(HELLO_ACK)
        if len(f.payload)!=8: raise RuntimeError('Invalid HELLO_ACK payload')
        max_payload,self.capabilities=struct.unpack('<II',f.payload)
        if not 8<=max_payload<=4096: raise RuntimeError('Invalid negotiated payload limit')
        self.max_payload=max_payload
        required=REQUIRED_AUDIO_CAPABILITIES | (1 << 4)
        if self.capabilities & required != required: raise RuntimeError('MIC, speaker, credit or STREAM_ID unsupported')
        return {'maxPayload':max_payload,'capabilities':self.capabilities,'protocol':2}

    def new_stream(self):
        self.stream += 1
        return self.stream

    def stop_microphone(self,sid):
        deadline=time.monotonic()+5
        while time.monotonic()<deadline:
            self.control(MIC_STOP,sid,16000)
            try:
                self.wait_control(MIC_STOPPED,sid,timeout=0.5)
                self.active_mic=None
                return
            except TimeoutError: continue
        raise TimeoutError('MIC_STOPPED acknowledgement deadline')

    def record(self,duration=1,cancelled=lambda:False,on_started=lambda:None):
        if not 0<duration<=5: raise ValueError('Short microphone test required')
        sid=self.new_stream(); self.active_mic=sid
        self.control(MIC_START,sid,16000)
        self.wait_control(MIC_STARTED,sid)
        started=time.monotonic(); data=bytearray(); seq=0; gaps=0
        try:
            on_started()
            while time.monotonic()-started<duration:
                if cancelled(): raise InterruptedError('Microphone cancelled')
                try: f=self.receive(0.1)
                except TimeoutError: continue
                self.check_error(f,sid)
                if f.type==1 and f.stream_id==sid:
                    if f.sample_rate!=16000 or len(f.payload)%2: raise RuntimeError('Unexpected microphone format')
                    if f.sequence!=seq: gaps+=1
                    seq=f.sequence+1
                    data.extend(f.payload)
        finally:
            self.stop_microphone(sid)
        samples=struct.unpack('<'+'h'*(len(data)//2),data) if data else ()
        rms=(sum(x*x for x in samples)/len(samples))**0.5 if samples else 0
        return bytes(data),{'bytes':len(data),'frames':seq,'sequenceGaps':gaps,'wallSeconds':time.monotonic()-started,'pcmSeconds':len(data)/32000,'rms':rms,'peak':max(map(abs,samples),default=0),'stopAcknowledged':True}

    def play_pcm(self,pcm,rate=16000,abort_after=None,cancelled=lambda:False):
        if rate not in (8000,16000,24000) or len(pcm)%2: raise ValueError('PCM format unsupported')
        if len(pcm)>rate*2*15: raise ValueError('Playback capped at 15 seconds')
        sid=self.new_stream(); self.active_speaker=(sid,rate)
        self.control(SPEAKER_START,sid,rate)
        start=time.monotonic(); credit=0; offset=0; sequence=0; ended=False; aborted=False; credit_events=0
        while time.monotonic()-start<20:
            if cancelled() or (abort_after is not None and time.monotonic()-start>=abort_after):
                self.control(SPEAKER_ABORT,sid,rate)
                aborted=True; break
            while credit>=2 and offset<len(pcm):
                n=min(credit,self.max_payload,len(pcm)-offset)&~1
                self.send(Frame(2,0,sid,sequence,rate,pcm[offset:offset+n]))
                sequence+=1; offset+=n; credit-=n
            if offset==len(pcm) and not ended:
                self.control(SPEAKER_END,sid,rate); ended=True
            try: f=self.receive(0.1)
            except TimeoutError: continue
            self.check_error(f,sid)
            if f.type==0 and f.stream_id==sid:
                if f.flags in (SPEAKER_CREDIT,SPEAKER_DONE) and f.sample_rate!=rate:
                    raise RuntimeError('Unexpected speaker control sample rate')
                if f.flags==SPEAKER_CREDIT:
                    if len(f.payload)!=4: raise RuntimeError('Bad credit payload')
                    credit+=struct.unpack('<I',f.payload)[0]; credit_events+=1
                elif f.flags==SPEAKER_DONE:
                    self.active_speaker=None
                    return {'bytesSent':offset,'pcmSeconds':len(pcm)/(rate*2),'wallSeconds':time.monotonic()-start,'creditEvents':credit_events,'doneAcknowledged':True,'aborted':False}
        if aborted:
            # CDC v2 abort intentionally stops without SPEAKER_DONE. Confirm
            # subsequent readiness with a new operation, rather than await it.
            self.active_speaker=None
            return {'bytesSent':offset,'wallSeconds':time.monotonic()-start,'abortCommandSent':True,'doneAcknowledgementExpected':False,'aborted':True}
        raise TimeoutError('Speaker playback deadline')

    def close(self):
        try:
            if self.active_mic is not None:
                self.control(MIC_STOP,self.active_mic,16000)
            if self.active_speaker is not None:
                self.control(SPEAKER_ABORT,*self.active_speaker)
        finally:
            self.serial.close()

def read_pcm_wave(filename):
    with wave.open(str(filename),'rb') as w:
        if (w.getnchannels(),w.getsampwidth(),w.getcomptype())!=(1,2,'NONE'): raise ValueError('Expected PCM16 mono WAV')
        return w.readframes(w.getnframes()),w.getframerate()
