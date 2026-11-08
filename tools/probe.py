#!/usr/bin/env python3
"""Prints library versions and model file facts (size, sha256, pinned revision) as JSON.
Run in CI only; used to pin the model catalogue. No secrets involved."""
import json, sys, urllib.request, re, hashlib, os

def get(url, headers=None):
    req = urllib.request.Request(url, headers={"User-Agent": "scriber-probe", **(headers or {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()

def maven(base, group, artifact):
    url = f"{base}/{group.replace('.', '/')}/{artifact}/maven-metadata.xml"
    try:
        xml = get(url).decode()
        vs = re.findall(r"<version>([^<]+)</version>", xml)
        return {"latest": re.search(r"<latest>([^<]+)</latest>", xml).group(1) if "<latest>" in xml else None,
                "release": re.search(r"<release>([^<]+)</release>", xml).group(1) if "<release>" in xml else None,
                "last10": vs[-10:]}
    except Exception as e:
        return {"error": str(e)}

out = {"maven": {}, "github": {}, "hf": {}}
G = "https://dl.google.com/android/maven2"
C = "https://repo1.maven.org/maven2"
for base, g, a in [
    (G, "com.google.ai.edge.litertlm", "litertlm-android"),
    (G, "androidx.biometric", "biometric"),
    (G, "androidx.work", "work-runtime-ktx"),
    (G, "androidx.compose", "compose-bom"),
    (G, "androidx.activity", "activity-compose"),
    (G, "androidx.core", "core-ktx"),
    (G, "androidx.lifecycle", "lifecycle-runtime-compose"),
    (G, "com.android.application", "com.android.application.gradle.plugin"),
    (C, "com.google.crypto.tink", "tink-android"),
    (C, "net.zetetic", "sqlcipher-android"),
    (C, "org.jetbrains.kotlinx", "kotlinx-serialization-json"),
    (C, "org.jetbrains.kotlinx", "kotlinx-coroutines-android"),
    (C, "org.jetbrains.kotlin", "kotlin-gradle-plugin"),
    (C, "org.apache.commons", "commons-compress"),
]:
    out["maven"][f"{g}:{a}"] = maven(base, g, a)

gh_headers = {"Accept": "application/vnd.github+json"}
tok = os.environ.get("GH_TOKEN")
if tok:
    gh_headers["Authorization"] = f"Bearer {tok}"
try:
    rel = json.loads(get("https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/latest", gh_headers))
    out["github"]["sherpa_latest"] = {"tag": rel["tag_name"], "date": rel["published_at"],
        "aars": [{"name": x["name"], "size": x["size"], "digest": x.get("digest"), "url": x["browser_download_url"]}
                 for x in rel["assets"] if x["name"].endswith(".aar")]}
except Exception as e:
    out["github"]["sherpa_latest"] = {"error": str(e)}
for tag, pats in [("asr-models", ["silero_vad", "parakeet-tdt-0.6b-v2-int8", "parakeet-tdt-0.6b-v3-int8"]),
                  ("speaker-segmentation-models", ["pyannote-segmentation-3-0"]),
                  ("speaker-recongition-models", ["titanet", "eres2net", "campplus"])]:
    try:
        rel = json.loads(get(f"https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/{tag}", gh_headers))
        out["github"][tag] = [{"name": x["name"], "size": x["size"], "digest": x.get("digest"), "url": x["browser_download_url"]}
                              for x in rel["assets"] if any(p in x["name"] for p in pats)]
    except Exception as e:
        out["github"][tag] = {"error": str(e)}

for repo in ["litert-community/gemma-4-E4B-it-litert-lm", "litert-community/gemma-4-E2B-it-litert-lm",
             "google/gemma-4-E4B-it-litert-lm", "google/gemma-4-E2B-it-litert-lm",
             "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
             "csukuangfj/sherpa-onnx-pyannote-segmentation-3-0",
             "google/medasr"]:
    try:
        info = json.loads(get(f"https://huggingface.co/api/models/{repo}"))
        tree = json.loads(get(f"https://huggingface.co/api/models/{repo}/tree/main?recursive=true"))
        out["hf"][repo] = {"sha": info.get("sha"), "gated": info.get("gated"), "license": (info.get("cardData") or {}).get("license"),
            "lastModified": info.get("lastModified"),
            "files": [{"path": f["path"], "size": f.get("size"), "sha256": (f.get("lfs") or {}).get("oid")}
                      for f in tree if f.get("type") == "file"]}
    except Exception as e:
        out["hf"][repo] = {"error": str(e)}

print("PROBE_JSON_BEGIN")
print(json.dumps(out, indent=1))
print("PROBE_JSON_END")
