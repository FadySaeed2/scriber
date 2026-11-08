#!/usr/bin/env python3
"""Second probe: toolchain compatibility facts (AAR minimum compileSdk/AGP, POM deps, Gradle versions)."""
import json, urllib.request, re, zipfile, io

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "scriber-probe"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()

G = "https://dl.google.com/android/maven2"
C = "https://repo1.maven.org/maven2"
out = {}
xml = get(f"{G}/com/android/application/com.android.application.gradle.plugin/maven-metadata.xml").decode()
vs = re.findall(r"<version>([^<]+)</version>", xml)
out["agp_stable"] = [v for v in vs if re.fullmatch(r"\d+\.\d+\.\d+", v)][-25:]
gv = json.loads(get("https://services.gradle.org/versions/all"))
out["gradle_stable"] = [v["version"] for v in gv if not v["snapshot"] and not v["nightly"] and not v["rcFor"] and not v["milestoneFor"] and not v["broken"]][:15]

def aar(base, g, a, v):
    p = f"{base}/{g.replace('.', '/')}/{a}/{v}/{a}-{v}"
    res = {}
    try:
        pom = get(p + ".pom").decode()
        res["deps"] = re.findall(r"<groupId>([^<]+)</groupId>\s*<artifactId>([^<]+)</artifactId>\s*<version>([^<]+)</version>", pom)[:30]
    except Exception as e:
        res["pom_error"] = str(e)
    try:
        z = zipfile.ZipFile(io.BytesIO(get(p + ".aar")))
        for n in z.namelist():
            if n.endswith("aar-metadata.properties"):
                res["aar_metadata"] = z.read(n).decode()
        res["jni"] = sorted({n.split('/')[1] for n in z.namelist() if n.startswith("jni/")})
        res["size"] = sum(i.file_size for i in z.infolist())
    except Exception as e:
        res["aar_error"] = str(e)
    return res

for base, g, a, v in [
    (G, "com.google.ai.edge.litertlm", "litertlm-android", "0.19.1"),
    (G, "com.google.ai.edge.litertlm", "litertlm-android", "0.16.1"),
    (G, "androidx.core", "core-ktx", "1.17.0"),
    (G, "androidx.core", "core-ktx", "1.19.1"),
    (G, "androidx.activity", "activity-compose", "1.13.0"),
    (G, "androidx.biometric", "biometric", "1.2.0-alpha05"),
    (G, "androidx.biometric", "biometric", "1.4.0-beta01"),
    (G, "androidx.work", "work-runtime", "2.11.2"),
    (G, "androidx.work", "work-runtime", "2.12.0"),
    (G, "androidx.compose.ui", "ui", "1.10.0"),
    (G, "androidx.lifecycle", "lifecycle-runtime-compose-android", "2.11.0"),
    (G, "androidx.compose.material3", "material3-android", "1.4.0"),
]:
    out[f"{g}:{a}:{v}"] = aar(base, g, a, v)
for path in ["androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom",
             "androidx/compose/compose-bom/2026.06.01/compose-bom-2026.06.01.pom"]:
    try:
        pom = get(f"{G}/{path}").decode()
        out[path] = re.findall(r"<artifactId>(ui|material3|foundation)</artifactId>\s*<version>([^<]+)</version>", pom)
    except Exception as e:
        out[path] = str(e)
# Tink pom
try:
    out["tink"] = re.findall(r"<artifactId>([^<]+)</artifactId>\s*<version>([^<]+)</version>", get(f"{C}/com/google/crypto/tink/tink-android/1.24.0/tink-android-1.24.0.pom").decode())[:15]
except Exception as e:
    out["tink"] = str(e)
# sherpa aar classes check
try:
    z = zipfile.ZipFile(io.BytesIO(get("https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar")))
    names = z.namelist()
    out["sherpa_aar"] = {"jni": sorted({n.split('/')[1] for n in names if n.startswith("jni/")}),
                         "has_classes": "classes.jar" in names,
                         "manifest": z.read("AndroidManifest.xml").decode()[:400]}
    cj = zipfile.ZipFile(io.BytesIO(z.read("classes.jar")))
    out["sherpa_classes"] = sorted(n for n in cj.namelist() if n.endswith(".class") and "$" not in n)[:120]
    km = [n for n in cj.namelist() if n.endswith(".kotlin_module")]
    out["sherpa_kotlin_module"] = km
except Exception as e:
    out["sherpa_aar"] = str(e)
print("PROBE_JSON_BEGIN")
print(json.dumps(out, indent=1))
print("PROBE_JSON_END")
