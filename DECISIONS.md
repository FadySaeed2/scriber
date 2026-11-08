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
