#!/usr/bin/env python3
"""W6 导出 E2E：视频→still→导出 zip→校验 edit_list.json。本地 api + stub worker。"""
import io,json,os,subprocess,sys,time,urllib.request,urllib.error,zipfile
API="http://localhost:8080"; PASS="Passw0rd!23"; HERE=os.path.dirname(__file__)
def call(m,p,pay=None,tok=None,ws=None,raw=False):
    h={"Content-Type":"application/json"}
    if tok: h["Authorization"]="Bearer "+tok
    if ws: h["X-Workspace-Id"]=ws
    data=json.dumps(pay).encode() if pay is not None else None
    req=urllib.request.Request(API+p,data=data,headers=h,method=m)
    try:
        with urllib.request.urlopen(req,timeout=120) as r:
            b=r.read()
            return r.status, b if raw else (json.loads(b) if b else {})
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]
def wait_done(tok,ws,pid):
    proc=subprocess.Popen([sys.executable,os.path.join(HERE,"stub_worker.py")],env=dict(os.environ),stdout=subprocess.DEVNULL)
    try:
        for _ in range(60):
            st,j=call("GET","/api/v1/projects/%s/jobs"%pid,tok=tok,ws=ws)
            if j and all(x["state"] in ("succeeded","failed") for x in j): return j
            time.sleep(2)
        return None
    finally:
        proc.terminate()
email="w6.%d@weaveora.dev"%int(time.time()*1000)
_,r=call("POST","/api/v1/auth/register",{"email":email,"password":PASS,"displayName":"W6"})
at=r["accessToken"]
_,me=call("GET","/api/v1/me",tok=at); ws=me["workspaces"][0]["id"]
_,p=call("POST","/api/v1/projects",{"title":"W6 导出","mode":"video","aspectRatio":"16:9","durationSec":6},at,ws)
pid=p["id"]
_,br=call("POST","/api/v1/projects/%s/briefs"%pid,{"rawText":"一片叶子在雨夜城市灯光里旋转，6 秒。","mode":"video"},at,ws)
_,g=call("POST","/api/v1/projects/%s/director/generate"%pid,{"briefId":br["id"]},at,ws)
rid=g["revisionId"]
call("POST","/api/v1/projects/%s/revisions/%s/approve"%(pid,rid),{},at,ws)
_,jobs=call("POST","/api/v1/projects/%s/jobs"%pid,{"revisionId":rid,"kind":"still"},at,ws)
print("still jobs:",len(jobs))
jobs=wait_done(at,ws,pid); print("states:",[j["state"] for j in (jobs or [])])
st,exp=call("POST","/api/v1/projects/%s/exports"%pid,{"revisionId":rid},at,ws)
print("export:",st, exp.get("id")[:8] if isinstance(exp,dict) else exp)
eid=exp["id"]
st,data=call("GET","/api/v1/exports/%s/download"%eid,tok=at,ws=ws,raw=True)
print("download:",st,"bytes",len(data))
zf=zipfile.ZipFile(io.BytesIO(data))
names=zf.namelist()
print("zip entries:",[n for n in names if not n.endswith("/")][:6])
el=json.loads(zf.read("edit_list.json"))
print("edit_list:",el["version"],el["fps"],len(el["tracks"][0]["clips"]),"clips; total",el["duration_sec"])
ok= st==200 and "edit_list.json" in names and len(el["tracks"][0]["clips"])>0
print("RESULT","PASS" if ok else "FAIL")
