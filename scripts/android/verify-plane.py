#!/usr/bin/env python3
"""In-flight Wi-Fi simulation against the local fake AnyConnect server.

Prereq: build/fakeanyconnect -listen 0.0.0.0:18443 running on the host, task
emulator emulator-5580 booted with adbd as root (adb root), app installed.

Cases (CASES env, default all):
  stall20 / stall35  every packet to the VPN server black-holed for N s, then
                     restored; the session must survive without reconnecting
  lag                UDP to the server blocked, 900 ms added egress delay
                     and 1 % loss on eth0 (tc netem); fresh connect, small
                     and 512 KB fetches through the tunnel
Usage: python3 scripts/android/verify-plane.py <label>
"""
import importlib.util, json, os, re, subprocess, sys, time
from pathlib import Path
spec = importlib.util.spec_from_file_location('v', str(Path(__file__).with_name('verify-emulator.py')))
v = importlib.util.module_from_spec(spec); spec.loader.exec_module(v)
adb = v.adb
label = sys.argv[1] if len(sys.argv) > 1 else 'run'
OUT = Path(f'build/android-evidence/plane-{label}.json')
SERVER, PORT = '10.0.2.2', '18443'
TARGET = 'http://10.99.0.1:18090/'
res = {}


def rec(k, ok, d=''):
    res[k] = {'ok': bool(ok), 'detail': str(d)[:300]}
    OUT.write_text(json.dumps(res, ensure_ascii=False, indent=2))
    print(('PASS ' if ok else 'FAIL ') + k + ' | ' + str(d)[:220], flush=True)


def profile():
    return {'profile_id': 'android-plane-test',
            'lines': [{'id': 'direct', 'name': '直连', 'type': 'direct', 'enabled': True},
                      {'id': 'ac', 'name': '测试 AnyConnect', 'type': 'vpn', 'enabled': True,
                       'vpn_server': f'https://{SERVER}:{PORT}', 'vpn_username': 'plane',
                       'vpn_password': 'plane-test-only-not-a-secret', 'allow_insecure': True}],
            'rule_sets': [], 'scenarios': [{'id': 'default', 'name': '飞机测试', 'bindings': [], 'default_line_id': 'ac'}],
            'active_scenario_id': 'default'}


def fetch(path, wait=60):
    adb('shell', 'am', 'force-stop', 'dev.xdial.probe')
    adb('shell', 'am', 'start', '-n', 'dev.xdial.probe/.ProbeActivity', '-e', 'url', TARGET + path)
    end = time.time() + wait
    while time.time() < end:
        try:
            nodes = list(v.ui().iter('node'))
        except Exception:
            time.sleep(1); continue
        for n in nodes:
            s = n.get('text', '')
            if s.startswith(('HTTP_OK', 'PROBE_FAILED')):
                return ' '.join(s.split())
        time.sleep(.5)
    return 'TIMEOUT'


def notif():
    out = adb('shell', 'dumpsys', 'notification', '--noredact')
    m = re.search(r'pkg=dev\.xdial\.port.*?android\.title=String \(([^)]*)\)', out, re.S)
    return m.group(1).replace('XDial · ', '') if m else '?'


def drop(add, *extra):
    adb('shell', 'iptables', '-I' if add else '-D', 'OUTPUT', '-d', SERVER, *extra, '-j', 'DROP')


def server_events():
    log = Path('build/android-evidence/fakeanyconnect.log').read_text()
    return log.count('CSTP_CONNECT'), log.count('CSTP_CLOSED')


def stall(sec):
    if notif() != '已连接':
        v.connect(60)
    ok = fetch('before').startswith('HTTP_OK 200')
    rec(f'stall{sec}.before', ok)
    (c0, x0), states = server_events(), []
    drop(True, '-p', 'tcp', '--dport', PORT)
    t0 = time.time()
    try:
        while time.time() - t0 < sec:
            states.append(notif()); time.sleep(1)
    finally:
        drop(False, '-p', 'tcp', '--dport', PORT)
    for _ in range(15):
        states.append(notif()); time.sleep(1)
    t1 = time.time()
    after = fetch('after', 90)
    c1, x1 = server_events()
    seen = sorted(set(states))
    rec(f'stall{sec}.session_kept', c1 == c0 and x1 == x0 and seen == ['已连接'],
        f'new tunnels={c1 - c0} closed tunnels={x1 - x0} states={seen}')
    rec(f'stall{sec}.traffic_after', after.startswith('HTTP_OK 200'), f'{round(time.time() - t1)}s {after[:40]}')


def lag():
    v.stop()
    drop(True, '-p', 'udp')
    adb('shell', 'tc', 'qdisc', 'add', 'dev', 'eth0', 'root', 'netem', 'delay', '900ms', 'loss', '1%')
    try:
        p = adb('shell', 'ping', '-c', '3', '-W', '5', SERVER)
        m = re.search(r'= [\d.]+/([\d.]+)/', p)
        rec('lag.emulated_rtt', m and float(m.group(1)) > 800, (m.group(1) + ' ms') if m else p[-100:])
        t0 = time.time()
        try:
            v.connect(120); up = True
        except AssertionError as e:
            up = False; rec('lag.connect_error', False, e)
        rec('lag.connects', up, f'{round(time.time() - t0)}s')
        r = fetch('lag-small', 90)
        rec('lag.small_fetch', r.startswith('HTTP_OK 200'), r[:40])
        t0 = time.time()
        # toybox nc closes on stdin EOF, so keep stdin open for the window.
        got = adb('shell', "(printf 'GET /big HTTP/1.0\\r\\n\\r\\n'; sleep 120) | nc 10.99.0.1 18090 | wc -c").strip()
        rec('lag.bulk_512k', got.isdigit() and int(got) >= 512 << 10, f'{got} bytes within 120s window')
    finally:
        adb('shell', 'tc', 'qdisc', 'del', 'dev', 'eth0', 'root')
        drop(False, '-p', 'udp')


if __name__ == '__main__':
    v.launch(); v.dismiss_default()
    v.fixture(profile())
    v.connect(60)
    for case in os.environ.get('CASES', 'stall20 stall35 lag').split():
        try:
            lag() if case == 'lag' else stall(int(case[5:]))
        except Exception as ex:
            rec(case + '.error', False, repr(ex))
    print(json.dumps(res, ensure_ascii=False))
