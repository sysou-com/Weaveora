import io
import json
import shutil

SRC = "/opt/weaveora/workflows/flux2_dev_edit_api.json"
DST = "/opt/weaveora/workflows/flux2_dev_edit_api_1p5mp.json"

shutil.copyfile(SRC, DST)
s = io.open(DST, encoding="utf-8").read()
old = '"megapixels": 1.0'
n = s.count(old)
s = s.replace(old, '"megapixels": 1.545')
io.open(DST, "w", encoding="utf-8").write(s)
d = json.load(io.open(DST, encoding="utf-8"))
print("replaced=%d" % n)
print("slots=", [(k, d[k]["class_type"], d[k]["inputs"].get("megapixels")) for k in ("13", "16", "19")])
print("target_window=", d["41"]["inputs"]["width"], d["41"]["inputs"]["height"], d["42"]["inputs"]["width"], d["42"]["inputs"]["height"])
