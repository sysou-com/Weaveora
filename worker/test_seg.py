import json,time,urllib.request,urllib.error
B="http://localhost:8080"; PASS="Passw0rd!23"
def call(m,p,pay=None,tok=None,ws=None):
    h={"Content-Type":"application/json"}
    if tok: h["Authorization"]="Bearer "+tok
    if ws: h["X-Workspace-Id"]=ws
    data=json.dumps(pay).encode() if pay is not None else None
    req=urllib.request.Request(B+p,data=data,headers=h,method=m)
    try:
        with urllib.request.urlopen(req,timeout=300) as r: return r.status,json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code,json.loads(e.read().decode() or "{}")
email="seg.%d@weaveora.dev"%int(time.time()*1000)
_,r=call("POST","/api/v1/auth/register",{"email":email,"password":PASS,"displayName":"Seg"})
at=r["accessToken"]; _,me=call("GET","/api/v1/me",tok=at); ws=me["workspaces"][0]["id"]
_,p=call("POST","/api/v1/projects",{"title":"seg120","mode":"video","aspectRatio":"16:9","durationSec":120},at,ws)
pid=p["id"]
_,br=call("POST","/api/v1/projects/%s/briefs"%pid,{"rawText":"一座城市从清晨到深夜的 120 秒蒙太奇，不要人脸。","mode":"video"},at,ws)
st,g=call("POST","/api/v1/projects/%s/director/generate"%pid,{"briefId":br["id"]},at,ws)
print("gen:",st, g.get("code") if isinstance(g,dict) and st!=200 else g.get("revisionNo"))
plan=g.get("plan",{}) if isinstance(g,dict) else {}
shots=plan.get("shots") or []
print("shots:",len(shots),"sum:",round(sum(x.get("duration_sec",0) for x in shots),2),"max:",max((x.get("duration_sec",0) for x in shots),default=0))
print("segments:",len(plan.get("segments") or []))
ok= st==200 and abs(sum(x.get("duration_sec",0) for x in shots)-120)<=0.5 and all(x.get("duration_sec",0)<=10 for x in shots)
print("RESULT","PASS" if ok else "FAIL")
