#!/usr/bin/env python3
"""修正：videoSchemaFramesMax 按 userId 从库读（current 可能未加载）。幂等。"""
import io

p = "api/src/main/java/studio/weaveora/engine/EngineSettingsService.java"
s = io.open(p, encoding="utf-8").read()
old = """    public Integer videoSchemaFramesMax() {
        UserEngineSettings s = current;
        if (s == null) {
            return null;
        }
        return framesMaxOf(s.videoModelSchema());
    }"""
new = """    public Integer videoSchemaFramesMax(UUID userId) {
        UserEngineSettings s = current;
        if (s == null || !userId.equals(s.getUserId())) {
            s = repo.findByUserId(userId).orElse(null);
        }
        if (s == null) {
            return null;
        }
        return framesMaxOf(s.videoModelSchema());
    }"""
assert s.count(old) == 1
s = s.replace(old, new, 1)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("EngineSettingsService: userId-based")

p2 = "api/src/main/java/studio/weaveora/job/JobService.java"
t = io.open(p2, encoding="utf-8").read()
reps = [
    ("private int motionFramesMaxFor(String engineRoute, JsonNode plan) {",
     "private int motionFramesMaxFor(String engineRoute, JsonNode plan, UUID userId) {"),
    ("Integer schemaMax = engineSettings.videoSchemaFramesMax();",
     "Integer schemaMax = engineSettings.videoSchemaFramesMax(userId);"),
    ("int hi = motionFramesMaxFor(engineRoute, plan);",
     "int hi = motionFramesMaxFor(engineRoute, plan, userId);"),
    ("int segHi = motionFramesMaxFor(engineRoute, plan);",
     "int segHi = motionFramesMaxFor(engineRoute, plan, userId);"),
]
for a, b in reps:
    n = t.count(a)
    assert n >= 1, a
    t = t.replace(a, b)
    print("  %-58s x%d" % (a[:56], n))
io.open(p2, "w", encoding="utf-8", newline="\n").write(t)
print("JobService: userId threaded")
