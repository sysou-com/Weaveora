import json,time,urllib.request,urllib.error,sys
B="http://localhost:8080"; PASS="Passw0rd!23"
def call(m,p,pay=None,tok=None,ws=None,to=60):
    h={"Content-Type":"application/json"}
    if tok: h["Authorization"]="Bearer "+tok
    if ws: h["X-Workspace-Id"]=ws
    data=json.dumps(pay).encode() if pay is not None else None
    req=urllib.request.Request(B+p,data=data,headers=h,method=m)
    try:
        with urllib.request.urlopen(req,timeout=to) as r: return r.status,json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code,json.loads(e.read().decode() or "{}")
    except Exception as e:
        return -1,{"err":type(e).__name__+":"+str(e)[:80]}
print("t0",flush=True)
email="seg2.%d@weaveora.dev"%int(time.time()*1000)
_,r=call("POST","/api/v1/auth/register",{"email":email,"password":PASS,"displayName":"Seg2"},to=60)
if "accessToken" not in r: print("reg-fail",str(r)[:120]); sys.exit(1)
at=r["accessToken"]
_,me=call("GET","/api/v1/me",tok=at)
ws=me["workspaces"][0]["id"]
_,p=call("POST","/api/v1/projects",{"title":"seg120b","mode":"video","aspectRatio":"16:9","durationSec":120},at,ws)
pid=p["id"]
_,br=call("POST","/api/v1/projects/%s/briefs"%pid,{"rawText":"一座城市从清晨到深夜的 120 秒蒙太奇，不要人脸。","mode":"video"},at,ws)
print("generate-start",flush=True)
t1=time.time()
st,g=call("POST","/api/v1/projects/%s/director/generate"%pid,{"briefId":br["id"]},at,ws,to=1500)
el=time.time()-t1
print("gen:",st,"elapsed:",round(el,1),"rev:",g.get("revisionNo") if isinstance(g,dict) else g,flush=True)
if isinstance(g,dict) and st!=200:
    print("err:",g.get("code"),str(g.get("message") or g.get("error") or "")[:160],flush=True)
plan=g.get("plan",{}) if isinstance(g,dict) else {}
shots=plan.get("shots") or []
print("shots:",len(shots),"sum:",round(sum(x.get("duration_sec",0) for x in shots),2),
      "max:",round(max((x.get("duration_sec",0) for x in shots),default=0),2),
      "segs:",len(plan.get("segments") or []),flush=True)
for i,x in enumerate(shots[:40]):
    print("  shot",i,x.get("duration_sec"),(x.get("prompt") or "")[:26],flush=True)
ok=(st==200 and len(shots)>10 and
    abs(sum(x.get("duration_sec",0) for x in shots)-120)<=1.0 and
    all(x.get("duration_sec",0)<=10.01 for x in shots))
print("RESULT","PASS" if ok else "FAIL",flush=True)
