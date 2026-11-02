"""One-time encrypted transfer to the TV. Credentials never enter ADB arguments or output."""
import base64,json,os,re,shlex,subprocess
from pathlib import Path
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
root=Path(__file__).resolve().parents[2]
local=root/'.local/eufy-status'
target=json.loads((local/'tv.json').read_text())
adb=[str(root/'.local/android-tools/platform-tools/adb'),'-P',str(target['port']),'-s',target['serial'],'shell','am','broadcast','-n','com.mazzei.cleanframe/.EufyProvision']
public=subprocess.run(adb+['-a','com.mazzei.cleanframe.EUFY_KEY'],capture_output=True,text=True,timeout=20)
match=re.search(r'data="([A-Za-z0-9+/=]+)"',public.stdout)
if not match:raise SystemExit('TV setup key unavailable. Open CleanFrame on the TV first.')
key=serialization.load_der_public_key(base64.b64decode(match[1]))
account=json.loads((local/'credentials.json').read_text())
cache=local/'session.json'
if cache.exists():
    saved=json.loads(cache.read_text())
    if saved.get('clientId')==account['clientId']:account['cache']=saved
secret=os.urandom(32);iv=os.urandom(12)
def b64(data):return base64.b64encode(data).decode()
envelope={'key':b64(key.encrypt(secret,padding.OAEP(mgf=padding.MGF1(hashes.SHA1()),algorithm=hashes.SHA256(),label=None))),
          'iv':b64(iv),'data':b64(AESGCM(secret).encrypt(iv,json.dumps(account).encode(),None))}
result=subprocess.run(adb+['-a','com.mazzei.cleanframe.EUFY_SETUP','--es','envelope',shlex.quote(json.dumps(envelope,separators=(',',':')))],capture_output=True,text=True,timeout=20)
if 'result=1' not in result.stdout:raise SystemExit('Encrypted setup was not accepted. Keep CleanFrame visible and try again.')
print('Encrypted setup delivered. Confirm Connect on the TV. No credentials were printed.')
