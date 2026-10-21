"""Explicit opt-in short device microphone recording for the local ASR adapter."""
import argparse,json,sys,wave
from cdc_audio import Dock
from cancel_signal import cancellation_event
from target_args import add_target_args
parser=argparse.ArgumentParser();parser.add_argument('seconds',type=float);parser.add_argument('output');add_target_args(parser);args=parser.parse_args()
seconds=args.seconds
if not 0<seconds<=5: raise ValueError('Recording must be 0-5 seconds')
dock=Dock(args.port,args.serial_number)
cancelled=cancellation_event()
try:
    hello=dock.hello()
    def ready(): print('MIC_READY',file=sys.stderr,flush=True)
    pcm,stats=dock.record(seconds,cancelled=cancelled.is_set,on_started=ready)
    if cancelled.is_set(): raise InterruptedError('Microphone cancelled')
    if not pcm: raise RuntimeError('No microphone PCM received')
    with wave.open(args.output,'wb') as output:
        output.setnchannels(1); output.setsampwidth(2); output.setframerate(16000)
        output.writeframes(pcm)
    print(json.dumps({'hello':hello,'microphone':stats,'crcErrors':dock.crc_errors}))
finally:
    dock.close()
