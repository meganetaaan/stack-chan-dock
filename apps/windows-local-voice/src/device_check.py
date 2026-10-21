import argparse,json
from cdc_audio import Dock,assert_target
from target_args import add_target_args
parser=argparse.ArgumentParser();add_target_args(parser);args=parser.parse_args()
port=assert_target(args.port,args.serial_number)
dock=Dock(args.port,args.serial_number)
try:
    hello=dock.hello()
    print(json.dumps({'port':port.device,'protocol':hello['protocol'],'capabilities':hello['capabilities'],'maxPayload':hello['maxPayload'],'crcErrors':dock.crc_errors,'recordingStarted':False,'playbackStarted':False}))
finally:dock.close()
