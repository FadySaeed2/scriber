#!/usr/bin/env python3
"""Third probe: hashes of small files without published SHA-256, MedASR packaging, redirect hosts."""
import json, urllib.request, hashlib, http.client, urllib.parse

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k): return None

def chain(url):
    hosts = []
    for _ in range(8):
        u = urllib.parse.urlparse(url)
        hosts.append(u.hostname)
        c = http.client.HTTPSConnection(u.hostname, timeout=60)
        c.request("HEAD", u.path + ("?" + u.query if u.query else ""), headers={"User-Agent": "scriber-probe"})
        r = c.getresponse()
        loc = r.getheader("Location")
        if r.status in (301, 302, 303, 307, 308) and loc:
            url = urllib.parse.urljoin(url, loc)
            continue
        return {"status": r.status, "hosts": hosts, "len": r.getheader("Content-Length")}
    return {"hosts": hosts, "error": "too many redirects"}

def sha(url):
    h = hashlib.sha256(); n = 0
    req = urllib.request.Request(url, headers={"User-Agent": "scriber-probe"})
    with urllib.request.urlopen(req, timeout=600) as r:
        while True:
            b = r.read(1 << 20)
            if not b: break
            h.update(b); n += len(b)
    return {"size": n, "sha256": h.hexdigest()}

out = {}
HF = "https://huggingface.co"
for url in [
    f"{HF}/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/84b478d541cdbf0c296a7ab5f1e20a6f8f998114/tokens.txt",
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
]:
    out[url] = sha(url)
for url in [
    f"{HF}/litert-community/gemma-4-E2B-it-litert-lm/resolve/3d48132a00cf76de57d926de30f5c5c5ed9b8c0d/gemma-4-E2B-it.litertlm",
    f"{HF}/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/84b478d541cdbf0c296a7ab5f1e20a6f8f998114/encoder.int8.onnx",
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
]:
    out["chain " + url] = chain(url)
gh = {"Accept": "application/vnd.github+json"}
import os
if os.environ.get("GH_TOKEN"): gh["Authorization"] = "Bearer " + os.environ["GH_TOKEN"]
rel = json.loads(urllib.request.urlopen(urllib.request.Request("https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/asr-models", headers=gh)).read())
out["gh_medasr"] = [{"name": x["name"], "size": x["size"], "digest": x.get("digest")} for x in rel["assets"] if "medasr" in x["name"].lower()]
for repo in ["csukuangfj/sherpa-onnx-medasr-ctc-en-int8-2025-12-25", "csukuangfj/sherpa-onnx-medasr-ctc-en-2025-12-25"]:
    try:
        info = json.loads(urllib.request.urlopen(f"{HF}/api/models/{repo}").read())
        tree = json.loads(urllib.request.urlopen(f"{HF}/api/models/{repo}/tree/main?recursive=true").read())
        files = []
        for f in tree:
            if f.get("type") != "file": continue
            e = {"path": f["path"], "size": f.get("size"), "sha256": (f.get("lfs") or {}).get("oid")}
            if e["sha256"] is None and (e["size"] or 0) < 5_000_000:
                e.update(sha(f"{HF}/{repo}/resolve/{info['sha']}/{f['path']}"))
            files.append(e)
        out[repo] = {"sha": info["sha"], "gated": info.get("gated"), "license": (info.get("cardData") or {}).get("license"), "files": files}
    except Exception as ex:
        out[repo] = str(ex)
print("PROBE_JSON_BEGIN")
print(json.dumps(out, indent=1))
print("PROBE_JSON_END")
