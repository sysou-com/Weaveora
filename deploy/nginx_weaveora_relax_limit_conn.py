import io, glob
CONF = None
for pat in ("/etc/nginx/conf.d/*.conf", "/etc/nginx/nginx.conf", "/etc/nginx/sites-enabled/*"):
    for f in glob.glob(pat):
        s = io.open(f, encoding="utf-8", errors="ignore").read()
        if "/opt/weaveora/web" in s:
            CONF = f; break
    if CONF: break
s = io.open(CONF, encoding="utf-8").read()
old = """        location ^~ /weaveora/api/ {
            proxy_pass http://127.0.0.1:8080/api/;"""
new = """        location ^~ /weaveora/api/ {
            # P13：资产库一次会拉很多小文件（缩略图/音频），站点级 limit_conn 20 会把它们打成 50x/404
            limit_conn conn 300;
            proxy_pass http://127.0.0.1:8080/api/;"""
n = s.count(old)
if n == 0:
    print("无需修改（可能已加）")
else:
    s = s.replace(old, new)
    io.open(CONF, "w", encoding="utf-8", newline="\n").write(s)
    print("已为 %d 个 weaveora api location 放宽 limit_conn 到 300" % n)
