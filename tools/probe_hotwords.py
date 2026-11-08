#!/usr/bin/env python3
"""
Probe: does giving Parakeet the medicine list (sherpa-onnx hotwords) help, and does it work at all?

Runs on a CI machine, never on the phone. Downloads the pinned Parakeet files and test recordings,
writes Scriber's medicine list as hotwords, builds the bpe.vocab sherpa-onnx needs from tokens.txt,
and transcribes:
  1. the six public medical dictations (word error rate, greedy vs beam vs beam + hints)
  2. sentences full of medicine names spoken by a synthetic voice (how many names come out right)
"""
import json, os, re, subprocess, sys, time, urllib.request, tarfile, wave

subprocess.run([sys.executable, "-m", "pip", "install", "-q", "--break-system-packages", "numpy", "sherpa-onnx==2.0.1"], check=True)
import importlib, site  # noqa: E402
site.addsitedir(site.getusersitepackages()); importlib.invalidate_caches()
import numpy as np  # noqa: E402
import sherpa_onnx  # noqa: E402
print("sherpa-onnx", sherpa_onnx.__version__ if hasattr(sherpa_onnx, "__version__") else "?")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
W = "/tmp/hw"; os.makedirs(W, exist_ok=True)
cat = json.load(open(f"{ROOT}/core/src/main/resources/model_catalog.json"))
packs = {p["id"]: p for p in cat["packs"]}

def fetch(url, dest):
    if not os.path.exists(dest):
        urllib.request.urlretrieve(url, dest)
    return dest

pk = {f["path"]: fetch(f["url"], f"{W}/{f['path']}") for f in packs["asr-parakeet-v2"]["files"] if not f["path"].endswith(".wav")}
bench = {f["path"]: fetch(f["url"], f"{W}/bench_{f['path']}") for f in packs["bench-medical-audio"]["files"]}

toks = open(pk["tokens.txt"], encoding="utf-8").read().splitlines()
print("TOKENS head:", toks[:12]); print("TOKENS tail:", toks[-6:]); print("TOKENS count:", len(toks))

# bpe.vocab: one piece per line with a score; earlier pieces (merged first) score higher.
vocab = f"{W}/bpe.vocab"
with open(vocab, "w", encoding="utf-8") as f:
    for line in toks:
        parts = line.rsplit(" ", 1)
        if len(parts) != 2: continue
        piece, idx = parts
        if piece.startswith("<") and piece.endswith(">"): continue
        f.write(f"{piece}\t{-float(idx)}\n")

meds = []
for line in open(f"{ROOT}/core/src/main/resources/medicines_au_nz.txt", encoding="utf-8"):
    line = line.strip()
    if not line or line.startswith("#"): continue
    g, _, b = line.partition("|")
    for n in [g] + b.split(","):
        n = n.strip()
        if n and re.fullmatch(r"[A-Za-z][A-Za-z \-]+", n): meds.append(n)
hot = f"{W}/hotwords.txt"
open(hot, "w").write("\n".join(sorted(set(meds))) + "\n")
print("HOTWORDS:", len(set(meds)))

def recognizer(method, hotwords=None, score=1.5):
    kw = dict(encoder=pk["encoder.int8.onnx"], decoder=pk["decoder.int8.onnx"], joiner=pk["joiner.int8.onnx"],
              tokens=pk["tokens.txt"], num_threads=4, model_type="nemo_transducer", decoding_method=method)
    if hotwords:
        kw.update(hotwords_file=hotwords, hotwords_score=score, modeling_unit="bpe", bpe_vocab=vocab)
    return sherpa_onnx.OfflineRecognizer.from_transducer(**kw)

def read(path):
    with wave.open(path) as w:
        sr = w.getframerate(); n = w.getnframes(); ch = w.getnchannels()
        a = np.frombuffer(w.readframes(n), dtype=np.int16).astype(np.float32) / 32768
        if ch > 1: a = a.reshape(-1, ch).mean(axis=1)
    return a, sr

def norm(t):
    t = t.lower(); t = re.sub(r"[\[\]{}]", " ", t); t = re.sub(r"[^a-z0-9' ]", " ", t)
    return t.split()

def wer(ref, hyp):
    r, h = norm(ref), norm(hyp)
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(h)], len(r)

refs = {}
key = None
for line in open(bench["transcript.txt"], encoding="utf-8"):
    m = re.match(r"^#\s*(\S+)\s*$", line)
    if m: key = m.group(1); refs[key] = ""; continue
    if key: refs[key] += line
print("REF KEYS:", list(refs))

def decode(rec, a, sr):
    s = rec.create_stream(); s.accept_waveform(sr, a); rec.decode_stream(s); return s.result.text

configs = [("greedy", dict(method="greedy_search")), ("beam", dict(method="modified_beam_search")),
           ("beam+hints 1.5", dict(method="modified_beam_search", hotwords=hot, score=1.5)),
           ("beam+hints 3.0", dict(method="modified_beam_search", hotwords=hot, score=3.0))]
results = {}
for name, cfg in configs:
    try:
        rec = recognizer(**cfg)
    except Exception as e:
        results[name] = f"FAILED: {e}"; continue
    errs = words = 0; t0 = time.time(); outs = {}
    for f in sorted(bench):
        if not f.endswith(".wav") or f == "2.wav": continue
        a, sr = read(bench[f]); out = decode(rec, a, sr); outs[f] = out
        e, n = wer(refs.get(f, ""), out); errs += e; words += n
    results[name] = {"wer": round(errs / max(words, 1), 4), "errors": errs, "words": words, "secs": round(time.time() - t0, 1), "sample": outs.get("1.wav", "")[:300]}
    print(name, results[name], flush=True)

# Synthetic voice reading sentences with medicine names.
tts_url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-alan-medium.tar.bz2"
tdir = f"{W}/tts"
try:
    if not os.path.isdir(tdir):
        tarfile.open(fetch(tts_url, f"{W}/tts.tar.bz2")).extractall(tdir)
    mdir = os.path.join(tdir, os.listdir(tdir)[0])
    onnx = [x for x in os.listdir(mdir) if x.endswith(".onnx")][0]
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=f"{mdir}/{onnx}", tokens=f"{mdir}/tokens.txt", data_dir=f"{mdir}/espeak-ng-data"), num_threads=4)))
    picks = ["olanzapine", "quetiapine", "aripiprazole", "paliperidone", "zuclopenthixol", "clozapine", "lurasidone", "amisulpride",
             "sertraline", "escitalopram", "mirtazapine", "venlafaxine", "desvenlafaxine", "vortioxetine", "agomelatine", "lithium",
             "lamotrigine", "sodium valproate", "carbamazepine", "lorazepam", "diazepam", "zopiclone", "melatonin", "methylphenidate",
             "lisdexamfetamine", "atomoxetine", "guanfacine", "naltrexone", "acamprosate", "buprenorphine", "benztropine", "propranolol",
             "metformin", "atorvastatin", "pantoprazole", "levothyroxine", "apixaban", "cariprazine", "brexpiprazole", "flupentixol"]
    templates = ["Continue {m} at night.", "He was started on {m} last week.", "We will increase the {m} slowly.", "She stopped taking {m} in March."]
    counts = {}
    for name, cfg in configs:
        try: rec = recognizer(**cfg)
        except Exception as e: counts[name] = f"FAILED: {e}"; continue
        hit = 0; misses = []
        for i, m in enumerate(picks):
            audio = tts.generate(templates[i % len(templates)].format(m=m), sid=0, speed=1.0)
            out = decode(rec, np.array(audio.samples, dtype=np.float32), audio.sample_rate)
            ok = m.replace(" ", "") in re.sub(r"[^a-z]", "", out.lower())
            hit += ok
            if not ok: misses.append(f"{m} -> {out}")
        counts[name] = {"right": hit, "of": len(picks), "misses": misses[:12]}
        print(name, counts[name], flush=True)
    results["synthetic"] = counts
except Exception as e:
    results["synthetic"] = f"FAILED: {e}"

print("PROBE_JSON_BEGIN"); print(json.dumps(results, indent=1)); print("PROBE_JSON_END")
