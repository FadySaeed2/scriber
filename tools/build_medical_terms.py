"""
Builds core/src/main/resources/medical_terms.txt from MeSH (U.S. National Library of Medicine),
for Scriber's second opinion: the words and short phrases that mark a place in a transcript as
medical, so a difference there is worth showing. Everyday English words are left out, judged by
wordfreq; only descriptors from the medical branches of MeSH are used (anatomy, diseases, chemicals
and drugs, techniques and procedures, psychiatry and psychology, physiology).

Run by the "Medical terms" workflow, which commits the result to the medical-terms branch.
"""
import re, sys, urllib.request
from wordfreq import zipf_frequency

COMMON = 4.6          # wordfreq Zipf: words at least this common are everyday English
BRANCHES = ("A", "C", "D", "E", "F01", "F02", "F03", "G09")

def fetch():
    for year in (2026, 2025):
        url = f"https://nlmpubs.nlm.nih.gov/projects/mesh/MESH_FILES/asciimesh/d{year}.bin"
        try:
            data = urllib.request.urlopen(url, timeout=300).read().decode("utf-8", "replace")
            print("MeSH", year, len(data), "chars")
            return year, data
        except Exception as e:
            print("could not fetch", url, e)
    sys.exit(1)

def common(w):
    return all(zipf_frequency(p, "en") >= COMMON for p in w.split("-"))

def main():
    year, data = fetch()
    words, phrases, kept = set(), set(), 0
    for rec in data.split("*NEWRECORD"):
        fields = [l.split(" = ", 1) for l in rec.splitlines() if " = " in l]
        trees = [v for k, v in fields if k == "MN"]
        if not any(t.startswith(BRANCHES) for t in trees):
            continue
        kept += 1
        names = [v.split("|")[0] for k, v in fields if k in ("MH", "ENTRY", "PRINT ENTRY")]
        for n in names:
            t = n.strip().lower()
            if "," in t or not re.fullmatch(r"[a-z]+(?:[ -][a-z]+)*", t):
                continue
            toks = t.split()
            for w in toks:
                if len(w) >= 5 and not common(w):
                    words.add(w)
            if 2 <= len(toks) <= 3 and any(common(w) or len(w) < 5 for w in toks) and all(len(w) >= 2 for w in toks):
                phrases.add(t)
    out = "core/src/main/resources/medical_terms.txt"
    with open(out, "w") as f:
        f.write(f"# Medical words and phrases from MeSH {year} (U.S. National Library of Medicine), everyday English removed.\n")
        for w in sorted(words | phrases):
            f.write(w + "\n")
    print("descriptors", kept, "words", len(words), "phrases", len(phrases))

main()
