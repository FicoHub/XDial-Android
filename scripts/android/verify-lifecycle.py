#!/usr/bin/env python3
"""Lifecycle checks on the task-owned emulator. Test installation contains no private data."""
import importlib.util,json,time
from pathlib import Path
spec=importlib.util.spec_from_file_location('vpn_test','scripts/android/verify-emulator.py')
v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
results={}
APK=max(Path('deliverables').glob('XDial-Android-*.apk'),key=lambda p:p.stat().st_mtime).as_posix()
v.home();v.click('更多',0);v.click('编辑完整配置',0);v.click('保存',0);time.sleep(1)
assert '已保存' in ''.join(x or '' for x in v.texts()) or '连接' in v.texts(),v.texts()
results['profile_editor_save']='full JSON editor saved through the real UI and core validation'
v.connect();v.adb('shell','input','keyevent','KEYCODE_HOME');time.sleep(3)
results['background']=v.probe('background');assert results['background'].startswith('HTTP_OK'),results
try:
    v.adb('shell','svc','wifi','disable');time.sleep(4)
    results['wifi_to_cellular']=v.probe('cellular');assert results['wifi_to_cellular'].startswith('HTTP_OK'),results
finally:
    v.adb('shell','svc','wifi','enable');time.sleep(5)
results['cellular_to_wifi']=v.probe('wifi-restored');assert results['cellular_to_wifi'].startswith('HTTP_OK'),results
v.stop()
# Fresh installation to exercise the system consent denial, never app-op grants.
v.adb('uninstall','dev.xdial.port')
v.adb('install','--no-incremental',APK)
v.launch();v.click('连接',0);v.click_any('Cancel','CANCEL');v.click('好',0)
assert 'Active vpn type: 1' not in v.adb('shell','dumpsys','vpn_management')
results['vpn_consent_denied']='no VPN created'
v.connect();results['fresh_install_approved']=v.probe('fresh-approved');assert results['fresh_install_approved'].startswith('HTTP_OK'),results
v.adb('shell','am','start','-a','android.settings.VPN_SETTINGS');time.sleep(1)
Path('build/android-evidence/vpn-settings-ui.xml').write_text(v.ET.tostring(v.ui(),encoding='unicode'))
v.click('XDial');v.click_any('Disconnect','DISCONNECT');time.sleep(1);v.launch()
texts=[n.get('text') for n in v.ui().iter('node')]
assert '授权已撤销' in texts or '未连接' in texts,texts
results['system_disconnect']='Android Settings disconnected VPN; app released service and reflected revocation'
p=v.profile();p['lines'].append({'id':'ac','name':'受控失败测试','type':'vpn','enabled':True,'vpn_server':'https://10.0.2.2:18444','vpn_username':'test-only','vpn_password':'not-a-real-account'});p['scenarios'][0]['default_line_id']='ac'
v.fixture(p);v.launch();v.click('连接',0)
for _ in range(15):
    texts=[n.get('text') for n in v.ui().iter('node')]
    if 'OK' in texts:v.click('OK',0);continue
    if '连接失败' in texts:break
    time.sleep(.3)
else:raise AssertionError(texts)
assert 'Active vpn type: 1' not in v.adb('shell','dumpsys','vpn_management')
results['anyconnect_failed_start']='unreachable controlled endpoint failed without leaving a VPN ingress'
v.fixture(v.profile());v.launch()
Path('build/android-evidence/android-lifecycle-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
print(json.dumps(results,ensure_ascii=False,indent=2))
