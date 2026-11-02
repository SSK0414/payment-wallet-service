#!/usr/bin/env python3
import base64, hashlib, hmac, json, os, sys, time

def enc(value):
    return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=").decode()

def token(user_id):
    secret = os.environ.get("JWT_SECRET", "local-development-secret-change-me-32bytes-minimum").encode()
    header, payload = enc({"alg": "HS256", "typ": "JWT"}), enc({"sub": user_id, "iat": int(time.time()), "exp": int(time.time()) + 3600})
    body = f"{header}.{payload}"
    signature = base64.urlsafe_b64encode(hmac.new(secret, body.encode(), hashlib.sha256).digest()).rstrip(b"=").decode()
    return f"{body}.{signature}"

if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {sys.argv[0]} USER_UUID")
    print(token(sys.argv[1]))
