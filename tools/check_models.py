#!/usr/bin/env python3
"""Streams every catalogue file, hashing on the fly (nothing is saved), and checks size, SHA-256
and that every redirect stays on the allowed hosts. Exits non-zero on any mismatch."""
import hashlib, json, sys, urllib.parse, urllib.request

ALLOWED = ["huggingface.co", "hf.co", "github.com", "githubusercontent.com"]

def allowed(host):
    h = (host or "").lower().rstrip(".")
    return any(h == d or h.endswith("." + d) for d in ALLOWED)

class Recorder(urllib.request.HTTPRedirectHandler):
    def __init__(self): self.hosts = []
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        self.hosts.append(urllib.parse.urlparse(newurl).hostname)
        return super().redirect_request(req, fp, code, msg, headers, newurl)

catalog = json.load(open(sys.argv[1]))
bad = 0
for pack in catalog["packs"]:
    for f in pack["files"]:
        rec = Recorder()
        opener = urllib.request.build_opener(rec)
        h = hashlib.sha256(); n = 0
        try:
            with opener.open(urllib.request.Request(f["url"], headers={"User-Agent": "Scriber-check"}), timeout=120) as r:
                while True:
                    b = r.read(1 << 22)
                    if not b: break
                    h.update(b); n += len(b)
        except Exception as e:
            print(f"FAIL {pack['id']}/{f['path']}: {e}"); bad += 1; continue
        hosts = [urllib.parse.urlparse(f["url"]).hostname] + rec.hosts
        problems = []
        if n != f["size"]: problems.append(f"size {n} != {f['size']}")
        if h.hexdigest() != f["sha256"]: problems.append(f"sha256 {h.hexdigest()} != {f['sha256']}")
        if not all(allowed(x) for x in hosts): problems.append(f"hosts {hosts}")
        status = "OK  " if not problems else "FAIL"
        bad += bool(problems)
        print(f"{status} {pack['id']}/{f['path']} {n} bytes via {' -> '.join(hosts)} {'; '.join(problems)}", flush=True)
print("All files match the catalogue" if not bad else f"{bad} problem(s)")
sys.exit(1 if bad else 0)
