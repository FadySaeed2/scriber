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
