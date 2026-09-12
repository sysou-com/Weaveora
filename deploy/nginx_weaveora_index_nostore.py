import io, re, sys, glob
CONF = None
for pat in ("/etc/nginx/conf.d/*.conf", "/etc/nginx/nginx.conf", "/etc/nginx/sites-enabled/*"):
    for f in glob.glob(pat):
        s = io.open(f, encoding="utf-8", errors="ignore").read()
        if "/opt/weaveora/web" in s:
            CONF = f
            break
    if CONF:
        break
if not CONF:
    print("找不到 weaveora 的 nginx 配置"); sys.exit(1)
s = io.open(CONF, encoding="utf-8").read()
if "location = /weaveora/index.html" in s:
    print("已存在，跳过"); sys.exit(0)
old = """        location ^~ /weaveora/ {
            alias /opt/weaveora/web/;"""
assert old in s, "锚点未命中"
new = """        # P13：index.html 一律不缓存（发版后立刻拿到新的 chunk 指针，避免旧页面拉旧 chunk 404）
        location = /weaveora/index.html {
            alias /opt/weaveora/web/index.html;
            add_header Cache-Control "no-store, must-revalidate";
        }
        location ^~ /weaveora/ {
            alias /opt/weaveora/web/;"""
s = s.replace(old, new, 1)
io.open(CONF, "w", encoding="utf-8", newline="\n").write(s)
print("已写入", CONF)
