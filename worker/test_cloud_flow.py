#!/usr/bin/env python3
"""云适配器逻辑测试（假 Replicate）：验证提交/轮询/下载/输出结构。"""
import json, os, sys, threading, urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
os.environ.setdefault('WEAVEORA_CLOUD_DELAY_MS','0')
os.environ.setdefault('WEAVEORA_CLOUD_RETRIES','1')
sys.path.insert(0, os.path.dirname(__file__))
import cloud_client

PNG = b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\x0aIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01\r\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82"

class Fake(BaseHTTPRequestHandler):
    def log_message(self,*a): pass
    def _j(self,o):
        b=json.dumps(o).encode(); self.send_response(200); self.send_header("Content-Type","application/json")
        self.send_header("Content-Length",str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_POST(self):
        n=int(self.headers.get("Content-Length",0)); self.rfile.read(n)
        self._j({"id":"p1","status":"processing","urls":{"get":"http://127.0.0.1:%d/status/p1"%cloud_client_port}})
    def do_GET(self):
        if self.path.startswith("/status/"):
            self._j({"id":"p1","status":"succeeded","output":["http://127.0.0.1:%d/file.png"%cloud_client_port]})
        elif self.path.startswith("/file.png"):
            self.send_response(200); self.send_header("Content-Type","image/png"); self.send_header("Content-Length",str(len(PNG))); self.end_headers(); self.wfile.write(PNG)
        else:
            self.send_response(404); self.end_headers()

cloud_client_port = 18889
cloud_client.API = "http://127.0.0.1:%d/v1"%cloud_client_port
cloud_client.TOKEN = "fake"
cloud_client.MODEL = "a/b:ver"
srv=HTTPServer(("127.0.0.1",cloud_client_port),Fake); threading.Thread(target=srv.serve_forever,daemon=True).start()
media=cloud_client.generate_still({"positive_prompt":"a cat","negative_prompt":"text","params":{"width":512,"height":512,"steps":4}})
print("media:", len(media), media[0][1], len(media[0][0]), media[0][2])
print("RESULT", "PASS" if media and media[0][1]=="image/png" and len(media[0][0])>10 else "FAIL")
srv.shutdown()
