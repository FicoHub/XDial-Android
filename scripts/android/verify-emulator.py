#!/usr/bin/env python3
"""Exercise only the task-owned emulator, through real consent and VPN UI."""
import base64, json, os, re, subprocess, time, xml.etree.ElementTree as ET
from pathlib import Path
_SDK=Path('build/android-sdk')
ADB=os.environ.get('ANDROID_HOME',str(_SDK.resolve() if (_SDK/'platform-tools').exists() else Path.home()/'Library/Android/sdk'))+'/platform-tools/adb'
SERIAL='emulator-5580'
EVIDENCE=Path('build/android-evidence')
def adb(*args):
    return subprocess.check_output([ADB,'-s',SERIAL,*args],text=True,stderr=subprocess.STDOUT)
def ui():
    adb('shell','uiautomator','dump','/sdcard/xdial-test-ui.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/xdial-test-ui.xml'))
def texts():
    return [n.get('text') for n in ui().iter('node')]
def scroll(down=True):
    size=re.findall(r'(\d+)x(\d+)',adb('shell','wm','size'))[-1];w,h=int(size[0]),int(size[1])
    a,b=(int(h*.75),int(h*.3)) if down else (int(h*.3),int(h*.75))
    adb('shell','input','swipe',str(w//2),str(a),str(w//2),str(b),'250');time.sleep(.4)
def click(text,scrolls=6):
    for attempt in range(5+scrolls):
        tree=ui()
        for n in tree.iter('node'):
            if n.get('text')==text:
                x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
                adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));return
        if attempt>=4: scroll()
        else: time.sleep(.4)
    raise AssertionError('UI text missing: '+text)
def click_any(*options):
    for _ in range(10):
        t=texts()
        for o in options:
            if o in t: return click(o,0)
        time.sleep(.4)
    raise AssertionError('UI text missing: '+'/'.join(options))
def home():
    for _ in range(4): adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(.2)
    launch()
    for _ in range(4): scroll(False)
def launch():
    adb('shell','am','start','-n','dev.xdial.port/.MainActivity');time.sleep(.5)
def connect(timeout=40):
    home();click('连接',0)
    end=time.time()+timeout
    while time.time()<end:
        t=texts()
        if 'OK' in t:click('OK',0);continue
        if 'Allow' in t:click('Allow',0);continue
        if 'ALLOW' in t:click('ALLOW',0);continue
        if '已连接' in t:return
        if '连接失败' in t:raise AssertionError('connect failed: '+' | '.join(x for x in t if x))
        time.sleep(.3)
    raise AssertionError('VPN did not become connected')
def stop():
    home();t=texts()
    if '断开' in t:
        click('断开',0)
        for _ in range(20):
            if '连接' in texts(): break
            time.sleep(.3)
        else: raise AssertionError('VPN cleanup did not finish')
        for _ in range(60):
            if 'Active vpn type: -1' in adb('shell','dumpsys','vpn_management'): break
            time.sleep(.25)
        else: raise AssertionError('Android did not release the VPN within 15 seconds')
        time.sleep(2)
def fixture(profile):
    stop()
    raw=base64.b64encode(json.dumps(profile).encode()).decode()
    result=adb('shell','am','instrument','-w','-e','profile_b64',raw,'dev.xdial.probe/.FixtureRunner')
    assert 'FIXTURE_SAVED' in result,result
    time.sleep(.3)
def probe(path=None,dns=None,udp=False):
    adb('shell','am','force-stop','dev.xdial.probe')
    args=['shell','am','start','-n','dev.xdial.probe/.ProbeActivity']
    args+=['-e','dns',dns] if dns else (['--ez','udp','true'] if udp else ['-e','url','http://10.0.2.2:18080/'+path])
    adb(*args)
    for _ in range(10):
        for n in ui().iter('node'):
            s=n.get('text','')
            if s.startswith(('HTTP_OK','DNS_OK','UDP_OK','PROBE_FAILED')):return s
        time.sleep(.3)
    raise AssertionError('Probe did not return')

def profile(password=None,split=False):
    p={'profile_id':'android-test','lines':[{'id':'direct','name':'直连','type':'direct','enabled':True}], 'rule_sets':[], 'scenarios':[{'id':'default','name':'验证场景','bindings':[],'default_line_id':'direct'}], 'active_scenario_id':'default'}
    if password:
        p['lines'].append({'id':'ss','name':'受控 Shadowsocks','type':'shadowsocks','enabled':True,'ss_server':'10.0.2.2','ss_port':18388,'ss_method':'aes-128-gcm','ss_password':password})
        p['scenarios'][0]['default_line_id']='ss'
    if split:
        p['rule_sets']=[{'id':'local','name':'测试目标直连','type':'manual','enabled':True,'cidrs':['10.0.2.2/32']}]
        p['scenarios'][0]['bindings']=[{'rule_set_id':'local','line_id':'direct'}]
    return p
if __name__=='__main__':
    results={}
    fixture(profile());connect();results['direct']=probe('direct');assert results['direct'].startswith('HTTP_OK'),results
    results['dns']=probe(dns='example.com');assert results['dns'].startswith('DNS_OK'),results
    results['direct_udp']=probe(udp=True);assert results['direct_udp'].startswith('UDP_OK'),results
    stop();results['after_disconnect']=probe('after-disconnect');assert results['after_disconnect'].startswith('HTTP_OK'),results
    connect();results['reconnect']=probe('reconnect');assert results['reconnect'].startswith('HTTP_OK'),results
    fixture(profile('xdial-test-only-not-a-real-secret'));connect();results['shadowsocks']=probe('shadowsocks');assert results['shadowsocks'].startswith('HTTP_OK'),results
    results['shadowsocks_udp']=probe(udp=True);assert results['shadowsocks_udp'].startswith('UDP_OK'),results
    fixture(profile('wrong-test-password'));connect();results['wrong_password']=probe('must-not-reach-endpoint');assert results['wrong_password'].startswith('PROBE_FAILED'),results
    fixture(profile('wrong-test-password',True));connect();results['scenario_rule_direct']=probe('scenario-rule-direct');assert results['scenario_rule_direct'].startswith('HTTP_OK'),results
    stop();fixture(profile());launch()
    EVIDENCE.joinpath('android-functional-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
    print(json.dumps(results,ensure_ascii=False,indent=2))
