# -*- coding: utf-8 -*-
"""把「第3镜 起始帧」任务的参考图（定妆照）按 storage key 落成可点开的 URL，便于和生成图并排核对。"""
import os
import subprocess

ENV = dict(l.strip().split('=', 1) for l in open('/etc/weaveora/weaveora-api.env', encoding='utf-8')
           if '=' in l and not l.strip().startswith('#'))
os.environ['PGPASSWORD'] = ENV.get('WEAVEORA_DB_PASSWORD', '')
S = '/opt/weaveora/data/storage'
D = '/opt/weaveora/web/_share'


def q(sql):
    r = subprocess.run(['psql', '-h', '127.0.0.1', '-U', 'weaveora', '-d', 'weaveora', '-tA', '-F', '|', '-c', sql],
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    return r.stdout.decode('utf-8', 'replace').strip()


row = q("select jsonb_array_elements_text(payload->'referenceSubjects') || '|' || "
        "jsonb_array_elements_text(payload->'referenceKeys') from generation_jobs "
        "where kind='still' and payload->>'shot_no'='3' order by created_at desc limit 1;")
print('== 第3镜起始帧 任务的参考图（主体 | storage key）==')
for line in row.splitlines():
    if '|' not in line:
        continue
    subj, key = [x.strip() for x in line.split('|', 1)]
    src = os.path.join(S, key)
    ext = key.rsplit('.', 1)[-1]
    dst = 'kf3_ref_%s.%s' % (subj, ext)
    kind = q("select kind from assets where storage_key='%s';" % key)
    ok = os.path.exists(src)
    if ok:
        subprocess.run(['cp', '-f', src, os.path.join(D, dst)])
        subprocess.run(['chmod', '644', os.path.join(D, dst)])
    print('   %-6s kind=%-9s %s  →  https://sysou.com/weaveora/_share/%s' % (subj, kind, 'OK' if ok else '缺失', dst))
