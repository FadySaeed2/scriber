# Decisions

**2026-11-08. One app that downloads its own models, then locks.** Fady chose this over a separate
downloader app. Android cannot remove a permission at runtime, so the guarantee comes from three
layers instead: the network security config (enforced by Android), the downloader's own host
check, and FetchLock. See ARCHITECTURE.md, Network.

**2026-11-08. Audio kept forever by default, original transcripts sealed.** Fady re-transcribes
recordings in different ways and processes raw transcripts with outside models.

**2026-11-08. Signing key encrypted in the repo, password in a repository secret.** The repo is
public. A key with a password committed to the code would let anyone sign an update that installs
over Scriber and reads its data. Same pattern as PDFx.

**2026-11-08. MedASR included from Phase 0.** sherpa-onnx 2.0.1 supports Google's MedASR directly
(int8, 154 MB), so no conversion is needed. The Lab compares it with Parakeet on six medical
dictations with reference text; the better engine becomes the default per use (dictation vs
conversation) once measured on the phone.

**2026-11-08. Gemma 4 file choice.** `gemma-4-E4B-it.litertlm` (the file Google benchmarks on
Samsung phones for both GPU and CPU), pinned to commit 71064b0. E2B for phones under 10 GB.

**2026-11-08. Toolchain.** AGP 8.13.2, Gradle 8.14.3, Kotlin 2.4.21 (LiteRT-LM 0.19.1 carries
Kotlin 2.4 metadata), compileSdk 36, minSdk 31.

**2026-11-08. Two modes: dictation and conversation.** Fady dictates notes as well as recording
reviews. A session carries its mode; each mode has its own default engine (MedASR for dictation,
Parakeet for conversation, changeable in Settings until his own recordings decide). Dictation shows
a readable view where spoken commands ("period", "new paragraph", section tags) become punctuation
and headings; the raw sealed text is always one tap away and is what JSON export carries.

**2026-11-08. WAV instead of FLAC.** Android has no built-in FLAC encoder that works from a stream
on every phone, and the phone has 727 GB free. 16 kHz mono WAV is about 115 MB per hour; studio
quality (48 kHz) is three times that. Kept lossless and simple; FLAC can be added later without
changing the layers.

**2026-11-08. Design language from PDFx/CalPal.** True black, ink palette (bone, sand, dust, ash),
amber for the one main action, Bricolage Grotesque, no cards or shadows, hairlines and type only,
line icons drawn in code. Dark only, like PDFx.

**2026-11-08. Parakeet first, MedASR as a second opinion, in both modes.** Fady's own trial: Parakeet
clearly better on ordinary speech, MedASR better on medicine names. Both run automatically on every
session; Parakeet's transcript is the one he reads. Disagreements are marked rather than merged
automatically, and nothing changes without his tap. Words only Parakeet heard are not flagged;
words only MedASR heard are flagged only when they name a medicine, to keep the review short.

**2026-11-08. Medicine dictionary bundled, everyday words protected.** About 1,000 generic and AU/NZ
brand names written for Scriber. A misspelling is matched by edit distance only for words of six
letters or more that are not among 32,000 common English words (wordfreq, CC BY-SA 4.0), so
"concert" is never turned into Concerta. Fady's corrections teach it what the engine heard.

**2026-11-08. Android's regex engine is stricter than desktop Java.** An unescaped closing brace
compiles in unit tests and crashes on the phone. All text functions now also run in an on-device test.

**2026-11-08. Speakers: two voices by default, cleaned lightly.** The phone's first check found both
voices but 8 changes where 1 was right. Scriber now asks the model for two voices in a conversation
(changeable per session and in Settings), joins a voice's neighbouring turns, and gives blips under
0.4 s to the voice around them. Short real answers ("Yes.") are longer than that and keep their own
voice. Inside one line, one or two words between the same voice follow it. Names and moves are
Fady's, kept beside the transcripts. Sessions over 80 minutes are not split yet (memory).

**2026-11-08. Medicine hints for Parakeet: a trial, not a default.** Measured on CI with the five
scored public dictations (219 words): greedy 13 errors, beam search 10, beam with the medicine list
as hotwords 12 (strength 0.5), 13 (1.0), 14 (1.5), and 35 at 3.0. Those dictations have almost no
medicine names, so they cannot show the benefit, only the cost. The Lab therefore runs both Parakeet
variants on Fady's own corrected sessions, where medicine names are scored, before anything changes.

**2026-11-08. Mirrors for the two models from GitHub releases.** k2-fsa's release downloads on GitHub
returned 504 for hours, blocking the device tests. Each catalogue file can now list mirrors with the
very same bytes; the downloader tries them in order and the SHA-256 check decides, so a mirror can
never change what Scriber runs. The voice-print model now comes first from the sherpa-onnx author's
Hugging Face repository; the voice activity model keeps GitHub first with a byte-identical copy on
Hugging Face as the fallback. The weekly model check covers every mirror too.
