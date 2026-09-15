# -*- coding: utf-8 -*-
"""查两件事：① 第6镜 23:49 那张到底是不是新生成（比对文件哈希/大小）；
   ② 方案里「第3镜两条记录」到底是不是我误判的重复（看它们各自是什么）。"""
import hashlib
import json
import os
import subprocess

ENV = dict(l.strip().split('=', 1) for l in open('/etc/weaveora/weaveora-api.env', encoding='utf-8')
           if '=' in l and not l.strip().startswith('#'))
os.environ['PGPASSWORD'] = ENV.get('WEAVEORA_DB_PASSWORD', '')
S = '/opt/weaveora/data/storage'
P = '0a000003-a095-1b93-81a0-95b699dd0000'


def q(sql):
    r = subprocess.run(['psql', '-h', '127.0.0.1', '-U', 'weaveora', '-d', 'weaveora', '-tA', '-F', ' | ', '-c', sql],
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    return r.stdout.decode('utf-8', 'replace').strip()


print('== 第6镜 still 资产（最近 4 张：时间/大小/md5前10）==')
rows = q("select to_char(created_at at time zone 'Asia/Shanghai','MM-DD HH24:MI:SS'), storage_key from assets "
         "where shot_no=6 and kind='still' order by created_at desc limit 4;").splitlines()
for line in rows:
    ts, key = [x.strip() for x in line.split('|')]
    p = os.path.join(S, key)
    if os.path.exists(p):
        h = hashlib.md5(open(p, 'rb').read()).hexdigest()[:10]
        print('   %s  %9d B  md5=%s' % (ts, os.path.getsize(p), h))
    else:
        print('   %s  (文件缺失)' % ts)

print('== 方案里 shot_no=3 的两条记录差异 ==')
det_rows = q("select jsonb_pretty(s) from prompt_revisions r, jsonb_array_elements(r.schema_json->'shots') s "
             "where r.project_id='%s' and s->>'shot_no'='3' order by r.revision_no desc limit 2;" % P)
print(det_rows[:2600])
