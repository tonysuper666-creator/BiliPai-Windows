import packet_inventory as previous
from packet_inventory import BASE,H,MAIN,artifact_rows,read,sha,wide
# Separate inventory: previous preflight runs and their packet graph stay intact.
# Window33 is already installed in actual79 and must not be applied twice.
PACKETS=[(name,digest,['exact-hunks.json','audio-caller-transform.json'] if name=='stable-video-owner-download-binding-parity' else hunks)
         for name,digest,hunks in previous.PACKETS if name!='stable-native-video-window-capture-parity']
PACKETS += [
 ('stable-video-holder-mini-binding-parity','dc9639c0dd2bb22c8fe2083bb38369e8334f6d84754db7d9dc3e8077c485e694',[]),
 ('stable-today-watch-feedback-io-parity','b00dbfaa9d34a196dca9cbb5473250dc4547647e36ed294a4530d734bc921ce4',['exact-hunks.json']),
 ('stable-video-root-story-feed-parity','abfb888bc537b4c9ddbc2b729ad9b3b0d5029f15c93b5532abb965b4cd7e58ee',['exact-hunks.json']),
 ('stable-video-root-portrait-rendering-parity','516a40bba7b5f0a93ad6d4c195840113a1b691fe3a386e08a911c63cd2062c03',['exact-hunks.json']),
 ('stable-original-now-playing-owner-parity','7da3f226c2f02d62dc6d0de7ea0771f1e125420f1d90a2a33089e5ab19ef3805',['exact-hunks.json']),
 ('stable-ready-root-now-playing-consumer-parity','d254f729b51e5abcbd02705e7767c669a44230b70baf16bc2fd817302e685c06',['install-contract.json']),
 ('stable-original-danmaku-local-click-parity','c526ad761da1b2892a5e3d1515068d04e5cf919b14682c34d260248696d33b47',['exact-hunks.json']),
 ('stable-video-section-route-active-delta','31799a4098c9635e0ace60356ebde86a78aeb6f0ac1253ab05438fb1b4d3dbb0',['exact-hunks.json']),
]
def inspect():
    previous.PACKETS=PACKETS
    return previous.inspect()
