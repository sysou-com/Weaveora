#!/usr/bin/env python3
"""生成一个临时 access token（HS256），仅用于服务端自查接口是否通。

用法（在 VPS 上）：
    python3 /tmp/mint_token.py <user-uuid>
密钥从 /etc/weaveora/weaveora-api.env 读取（WEAVEORA_JWT_SECRET）。
"""
import base64
import hashlib
import hmac
import json
import sys
import time
import uuid


def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def main() -> None:
    uid = sys.argv[1]
    env = dict(
        line.strip().split("=", 1)
        for line in open("/etc/weaveora/weaveora-api.env", encoding="utf-8")
        if "=" in line and not line.strip().startswith("#")
    )
    secret = env["WEAVEORA_JWT_SECRET"].strip().encode()
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "sub": uid,
        "type": "access",
        "iat": now,
        "exp": now + 900,
        "jti": str(uuid.uuid4()),
    }
    signing_input = b64(json.dumps(header, separators=(",", ":")).encode()) + "." + b64(
        json.dumps(payload, separators=(",", ":")).encode()
    )
    sig = hmac.new(secret, signing_input.encode(), hashlib.sha256).digest()
    print(signing_input + "." + b64(sig))


if __name__ == "__main__":
    main()
