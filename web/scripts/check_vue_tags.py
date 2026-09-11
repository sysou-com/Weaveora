#!/usr/bin/env python3
"""检查 .vue 模板标签是否平衡（section/template/div/label）。"""
import re
import sys

for p in sys.argv[1:]:
    rc = 0
    s = open(p, encoding="utf-8").read()
    t = s[s.index("<template>") + 10 : s.rindex("</template>")]
    bad = 0
    for tag in ["section", "template", "div", "label", "nav", "span", "p"]:
        o = len(re.findall(r"<" + tag + r"[ >]", t))
        c = len(re.findall(r"</" + tag + r">", t))
        flag = "OK" if o == c else "<<< UNBALANCED"
        if o != c:
            bad += 1
        print("%-9s open=%-3d close=%-3d %s" % (tag, o, c, flag))
    print(p, "=>", "BALANCED" if bad == 0 else "FAIL")
    if bad:
        rc = 1
sys.exit(rc)
