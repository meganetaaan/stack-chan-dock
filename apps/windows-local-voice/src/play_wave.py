import argparse,json
from cdc_audio import Dock,read_pcm_wave
from cancel_signal import cancellation_event
from target_args import add_target_args
parser=argparse.ArgumentParser();parser.add_argument('wave');add_target_args(parser);args=parser.parse_args()
pcm,rate=read_pcm_wave(args.wave)
dock=Dock(args.port,args.serial_number)
cancelled=cancellation_event()
try:
    hello=dock.hello()
    playback=dock.play_pcm(pcm,rate,cancelled=cancelled.is_set)
    print(json.dumps({'hello':hello,'playback':playback,'crcErrors':dock.crc_errors}))
finally:
    dock.close()
