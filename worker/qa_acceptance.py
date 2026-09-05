#!/usr/bin/env python3
"""W7 质量验收（自动化版 §31 场景）。用法：
  python qa_acceptance.py --base http://localhost:8080 --engine stub
  # engine stub：自动拉起本地 stub worker 完成生成；engine cloud：依赖运行中的云 worker（如 VPS systemd）
"""
import argparse, json, os, subprocess, sys, time, urllib.request, urllib.error, zipfile, io
HERE = os.path.dirname(__file__)
PASS = "Passw0rd!23"
RESULTS = []

def log(name, ok, extra=""):
    RESULTS.append((name, ok))
    print(("[PASS] " if ok else "[FAIL] ") + name + (" | " + str(extra) if extra else ""))

class Api:
    def __init__(self, base): self.base=base.rstrip("/"); self.at=None; self.ws=None
    def _req(self, m, p, pay=None, raw=False, timeout=300):
        h={"Content-Type":"application/json"}
        if self.at: h["Authorization"]="Bearer "+self.at
        if self.ws: h["X-Workspace-Id"]=self.ws
        data=json.dumps(pay).encode() if pay is not None else None
        req=urllib.request.Request(self.base+p, data=data, headers=h, method=m)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                b=r.read(); return r.status, (b if raw else (json.loads(b) if b else {}))
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode() or "{}")
    def register(self, tag):
        st,r=self._req("POST","/api/v1/auth/register",{"email":("%s.%d@weaveora.dev"%(tag,int(time.time()*1000))),"password":PASS,"displayName":tag})
        self.at=r["accessToken"]; self._req("GET","/api/v1/me")
        _,me=self._req("GET","/api/v1/me"); self.ws=me["workspaces"][0]["id"]
    def post(self,p,pay): return self._req("POST",p,pay)
    def get(self,p): return self._req("GET",p)

def run_worker_until_done(api, pid, base):
    env=dict(os.environ); env["WEAVEORA_API_BASE"]=base
    proc=subprocess.Popen([sys.executable, os.path.join(HERE,"stub_worker.py")], env=env,
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(120):
            st,j=api.get("/api/v1/projects/%s/jobs"%pid)
            if j and all(x["state"] in ("succeeded","failed","cancelled") for x in j): return j
            time.sleep(2)
        return None
    finally:
        proc.terminate()
        try: proc.wait(timeout=5)
        except Exception: proc.kill()

def main():
    ap=argparse.ArgumentParser(); ap.add_argument("--base",default="http://localhost:8080")
    ap.add_argument("--engine",default="stub",choices=["stub","cloud"]); a=ap.parse_args()
    # S1 图片闭环
    api=Api(a.base); api.register("qaimg")
    st,p=api.post("/api/v1/projects",{"title":"QA 图片","mode":"image","aspectRatio":"16:9"})
    pid=p["id"]
    st,br=api.post("/api/v1/projects/%s/briefs"%pid,{"rawText":"被水淹的巴洛克图书馆，月光从穹顶落下，不要人，电影静帧。","mode":"image"})
    st,g=api.post("/api/v1/projects/%s/director/generate"%pid,{"briefId":br["id"]})
    pos=g["plan"].get("positive_prompt","") if st==200 else ""
    log("S1 generate 图片方案(正向词长度)", st==200 and len(pos)>20, len(pos))
    rid=g.get("revisionId") if st==200 else None
    if rid:
        st,ap1=api.post("/api/v1/projects/%s/revisions/%s/approve"%(pid,rid),{})
        log("S1 approve", st==200 and ap1.get("projectStatus")=="approved", ap1.get("projectStatus"))
        st,jobs=api.post("/api/v1/projects/%s/jobs"%pid,{"revisionId":rid,"kind":"still","count":1})
        log("S1 创建图片任务", st==200 and len(jobs)==1, len(jobs))
        if a.engine=="stub": jobs=run_worker_until_done(api,pid,a.base)
        else:
            for _ in range(150):
                _,jobs=api.get("/api/v1/projects/%s/jobs"%pid)
                if jobs and all(x["state"] in ("succeeded","failed") for x in jobs): break
                time.sleep(3)
        log("S1 图片生成成功", bool(jobs) and all(x["state"]=="succeeded" for x in jobs),
            [x["state"] for x in (jobs or [])])
        _,assets=api.get("/api/v1/projects/%s/assets"%pid)
        has_png=any(x["kind"]=="still" and x["mime"]=="image/png" for x in assets)
        log("S1 资产 png 落库", has_png, len(assets))
        if assets:
            st,raw=api._req("GET","/api/v1/assets/%s/download"%assets[0]["id"],raw=True)
            log("S1 资产可下载(字节>1k)", st==200 and len(raw)>1024, len(raw))

    # S2 主体分档安全
    api2=Api(a.base); api2.register("qasafe")
    _,p2=api2.post("/api/v1/projects",{"title":"QA 安全","mode":"image","aspectRatio":"1:1"})
    pid2=p2["id"]
    _,br2=api2.post("/api/v1/projects/%s/briefs"%pid2,{"rawText":"真实人物的肖像照，像某位明星，电影光效，竖构图。","mode":"image"})
    st2,g2=api2.post("/api/v1/projects/%s/director/generate"%pid2,{"briefId":br2["id"]})
    log("S2 真人分档拦截(422 BRIEF_BLOCKED)", st2==422 and isinstance(g2,dict) and g2.get("code")=="BRIEF_BLOCKED",
        str(g2.get("message",""))[:70])

    # S3 视频 12s
    api3=Api(a.base); api3.register("qavid")
    _,p3=api3.post("/api/v1/projects",{"title":"QA 视频","mode":"video","aspectRatio":"16:9","durationSec":12})
    pid3=p3["id"]
    _,br3=api3.post("/api/v1/projects/%s/briefs"%pid3,{"rawText":"一只纸船在暴雨城市运河漂过霓虹，12 秒，孤独。","mode":"video"})
    st,g3=api3.post("/api/v1/projects/%s/director/generate"%pid3,{"briefId":br3["id"]})
    plan=g3["plan"] if st==200 else {}
    shots=plan.get("shots") or []
    sm=sum(x.get("duration_sec",0) for x in shots)
    ok_shots= len(shots)>=2 and abs(sm-12)<=0.5 and all(x.get("duration_sec",0)<=10 for x in shots)
    log("S3 视频方案(≥2镜·和12±.5·单镜≤10s)", ok_shots, "shots=%d sum=%.1f"%(len(shots),sm))
    rid3=g3["revisionId"] if st==200 else None
    stj,jj=api3.post("/api/v1/projects/%s/jobs"%pid3,{"revisionId":rid3,"kind":"still"})
    log("S3 未确认建任务 409", stj==409 and jj.get("code")=="REVISION_NOT_APPROVED", jj.get("code"))
    p2c=json.loads(json.dumps(plan))
    p2c["shots"][1]["positive_prompt"]="close up of paper boat under neon rain reflections on canal water at night, cinematic, moody"
    stp,pp=api3._req("PATCH","/api/v1/projects/%s/revisions/%s"%(pid3,rid3),{"plan":p2c})
    log("S3 改第2镜(PATCH 200, source=user)", stp==200 and pp.get("source")=="user", pp.get("source"))
    stA,apA=api3.post("/api/v1/projects/%s/revisions/%s/approve"%(pid3,rid3),{})
    log("S3 整版确认", stA==200 and apA.get("projectStatus")=="approved", apA.get("projectStatus"))
    stl,lock=api3._req("PATCH","/api/v1/projects/%s/revisions/%s"%(pid3,rid3),{"plan":p2c})
    log("S3 已确认后改稿 409 REVISION_LOCKED", stl==409 and lock.get("code")=="REVISION_LOCKED", lock.get("code"))
    _,jobs3=api3.post("/api/v1/projects/%s/jobs"%pid3,{"revisionId":rid3,"kind":"still"})
    if a.engine=="stub": jobs3=run_worker_until_done(api3,pid3,a.base)
    else:
        for _ in range(150):
            _,jobs3=api3.get("/api/v1/projects/%s/jobs"%pid3)
            if jobs3 and all(x["state"] in ("succeeded","failed") for x in jobs3): break
            time.sleep(3)
    log("S3 关键帧全部成功", bool(jobs3) and all(x["state"]=="succeeded" for x in jobs3),
        [x["state"] for x in (jobs3 or [])])
    stx,ex=api3.post("/api/v1/projects/%s/exports"%pid3,{"revisionId":rid3})
    log("S3 导出成功", stx==200 and isinstance(ex,dict) and "id" in ex, ex.get("id","")[:8])
    if stx==200 and isinstance(ex,dict):
        stz,raw=api3._req("GET","/api/v1/exports/%s/download"%ex["id"],raw=True)
        zf=zipfile.ZipFile(io.BytesIO(raw)) if stz==200 else None
        names=zf.namelist() if zf else []
        el=json.loads(zf.read("edit_list.json")) if zf and "edit_list.json" in names else {}
        clips=(el.get("tracks") or [{}])[0].get("clips",[]) if el else []
        ok_zip= stz==200 and "edit_list.json" in names and len(clips)>0
        log("S3 edit_list 导出包有效", ok_zip, el.get("version") if el else None)
    print()
    print("== SUMMARY ==")
    fails=[r for r in RESULTS if not r[1]]
    for n,ok in RESULTS: print(("PASS " if ok else "FAIL ")+n)
    print("RESULT", "ALL_PASS" if not fails else "HAS_FAILURE(%d)"%len(fails))

if __name__ == "__main__":
    main()
