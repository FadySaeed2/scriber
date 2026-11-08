#!/usr/bin/env python3
"""Fourth probe: test library versions."""
import json, urllib.request, re
def meta(base, g, a):
    try:
        xml = urllib.request.urlopen(f"{base}/{g.replace('.', '/')}/{a}/maven-metadata.xml", timeout=60).read().decode()
        vs = re.findall(r"<version>([^<]+)</version>", xml)
        return [v for v in vs if re.fullmatch(r"[\d.]+", v)][-6:]
    except Exception as e:
        return str(e)
G = "https://dl.google.com/android/maven2"; C = "https://repo1.maven.org/maven2"
out = {f"{g}:{a}": meta(b, g, a) for b, g, a in [
    (C, "org.robolectric", "robolectric"), (C, "io.github.takahirom.roborazzi", "roborazzi"),
    (G, "androidx.test", "runner"), (G, "androidx.test", "rules"), (G, "androidx.test.ext", "junit"), (G, "androidx.test", "core"),
    (G, "androidx.lifecycle", "lifecycle-runtime-compose"), (G, "androidx.lifecycle", "lifecycle-viewmodel-compose"),
    (G, "androidx.work", "work-testing"), (G, "androidx.fragment", "fragment-ktx"), (G, "androidx.activity", "activity-compose"),
    (C, "org.jetbrains.kotlinx", "kotlinx-coroutines-test")]}
print("PROBE_JSON_BEGIN"); print(json.dumps(out, indent=1)); print("PROBE_JSON_END")
