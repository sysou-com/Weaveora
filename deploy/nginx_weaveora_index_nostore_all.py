import io, glob
CONF = None
for pat in ("/etc/nginx/conf.d/*.conf", "/etc/nginx/nginx.conf", "/etc/nginx/sites-enabled/*"):
    for f in glob.glob(pat):
        s = io.open(f, encoding="utf-8", errors="ignore").read()
        if "/opt/weaveora/web" in s:
            CONF = f
            break
    if CONF:
        break
s = io.open(CONF, encoding="utf-8").read()
BLOCK = """        location = /weaveora/index.html {
            alias /opt/weaveora/web/index.html;
            add_header Cache-Control "no-store, must-revalidate";
        }
"""
anchor = """        location ^~ /weaveora/ {
            alias /opt/weaveora/web/;"""
n = s.count(anchor)
added = 0
out = []
i = 0
while True:
    j = s.find(anchor, i)
    if j < 0:
        out.append(s[i:])
        break
    prefix = s[max(0, j - 300):j]
    if "location = /weaveora/index.html" not in prefix:
        out.append(s[i:j]); out.append(BLOCK); added += 1
    else:
        out.append(s[i:j])
    i = j
    # 继续下一轮（不消费 anchor，由下一轮 find 处理）
    i = j + len(anchor)
    out.append(anchor)
s2 = "".join(out)
io.open(CONF, "w", encoding="utf-8", newline="\n").write(s2)
print("发现 %d 处 weaveora location，补了 %d 处 no-store" % (n, added))
