"""Cooperative stop from the owning launcher, without a forced COM-port exit."""
import os,signal,sys,threading
def cancellation_event():
    cancelled=threading.Event()
    def stop(*_): cancelled.set()
    signal.signal(signal.SIGINT,stop)
    def listen():
        pending=b''
        while True:
            chunk=os.read(sys.stdin.fileno(),1024)
            if not chunk:return
            pending+=chunk
            if b'cancel\n' in pending:
                cancelled.set();return
    if not sys.stdin.isatty():
        threading.Thread(target=listen,daemon=True).start()
    return cancelled
