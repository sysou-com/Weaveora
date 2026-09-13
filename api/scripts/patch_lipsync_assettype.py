#!/usr/bin/env python3
"""修 Asset 类型引用（JobService 里用全限定名，避免 import 冲突）。幂等。"""
import io

p = "api/src/main/java/studio/weaveora/job/JobService.java"
s = io.open(p, encoding="utf-8").read()
AQ = "studio.weaveora.asset.domain.Asset"
reps = [
    ("    private Asset pickNewestAsset(", "    private %s pickNewestAsset(" % AQ),
    ("    private List<Asset> newestVoicePerLine(UUID projectId, UUID workspaceId, int shotNo) {",
     "    private List<%s> newestVoicePerLine(UUID projectId, UUID workspaceId, int shotNo) {" % AQ),
    ("        Map<Integer, Asset> newest = new java.util.TreeMap<>();\n        for (Asset a : all) {",
     "        Map<Integer, %s> newest = new java.util.TreeMap<>();\n        for (%s a : all) {" % (AQ, AQ)),
    ('            Asset clip = pickNewestAsset(projectId, workspaceId, shotNo, "clip");\n'
     '            Asset still = clip != null ? clip : pickNewestAsset(projectId, workspaceId, shotNo, "still");',
     '            %s clip = pickNewestAsset(projectId, workspaceId, shotNo, "clip");\n'
     '            %s still = clip != null ? clip : pickNewestAsset(projectId, workspaceId, shotNo, "still");' % (AQ, AQ)),
    ("            List<Asset> voices = newestVoicePerLine(projectId, workspaceId, shotNo);",
     "            List<%s> voices = newestVoicePerLine(projectId, workspaceId, shotNo);" % AQ),
    ("            for (Asset a : voices) {", "            for (%s a : voices) {" % AQ),
    ("        List<Asset> all = assetRepo", "        List<%s> all = assetRepo" % AQ),
    ("        List<Asset> list = assetRepo", "        List<%s> list = assetRepo" % AQ),
]
for a, b in reps:
    if a in s:
        s = s.replace(a, b, 1)
        print("  ok: %s" % a.strip()[:56])
    else:
        print("  skip(already): %s" % a.strip()[:56])
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("done")
