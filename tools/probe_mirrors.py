#!/usr/bin/env python3
"""
Probe: find Hugging Face copies of the two models Scriber gets from GitHub releases, byte-identical
(same SHA-256 as the catalogue), pinned to a commit. GitHub's k2-fsa release downloads time out.
"""
import json, urllib.request, urllib.parse

WANT = {
    "silero_vad.onnx": ("9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6", 643854),
    "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx": ("357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b", 29596978),
}

def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "scriber-probe"}), timeout=60) as r:
        return json.loads(r.read().decode())

repos = set()
for q in ["silero_vad", "vad", "speaker-embedding", "campplus", "3dspeaker", "speaker-recognition", "sherpa-onnx-vad", "silero"]:
    for author in ["csukuangfj", "k2-fsa", None]:
        params = {"search": q, "limit": "100"}
        if author: params["author"] = author
        try:
            for m in get("https://huggingface.co/api/models?" + urllib.parse.urlencode(params)):
                repos.add(m["id"])
        except Exception as e:
            print("search failed", q, author, e)
print("candidate repos:", len(repos))

found = {}
for repo in sorted(repos):
    try:
        info = get(f"https://huggingface.co/api/models/{repo}")
        sha = info.get("sha")
        tree = get(f"https://huggingface.co/api/models/{repo}/tree/{sha}?recursive=true")
    except Exception as e:
        continue
    for f in tree:
        lfs = f.get("lfs") or {}
        oid = lfs.get("oid") or ""
        for name, (want, size) in WANT.items():
            if oid == want or (f.get("size") == size and f["path"].endswith(name.split("_")[-1])):
                url = f"https://huggingface.co/{repo}/resolve/{sha}/{f['path']}"
                found.setdefault(name, []).append({"repo": repo, "path": f["path"], "oid": oid, "size": f.get("size"), "match": oid == want, "url": url})
print("PROBE_JSON_BEGIN"); print(json.dumps(found, indent=1)); print("PROBE_JSON_END")
