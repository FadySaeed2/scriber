# Plan

Each phase ships as a signed APK, is tried on the phone, and only then does the next start.

- **Phase 0, foundation (shipped 0.0.5):** project, CI, signing, vault, identity lock, model download
  with checksums and self-lock, device check, Lab benchmarks.
- **Phase 1, recording, import and raw transcription (shipped 0.0.11):** two modes, dictation (spoken
  punctuation and section commands become a readable note view) and conversation; foreground
  recording that survives screen lock, crash-safe encrypted 5-second pieces, lossless WAV
  (16 kHz default, 48 kHz studio option with a 16 kHz working copy), import of
  m4a/mp3/wav/ogg/opus/flac and the audio of video files (picker and share sheet), recording library,
  retention (keep forever by default, or delete after note approval, or after N days), transcription
  in batches during recording, sealed raw runs, export as text/SRT/VTT/JSON plus the audio.
- **Phase 2a, second opinion and corrections (this build):** Parakeet is the main transcript in both
  modes and MedASR runs after it on every session; the two are lined up word by word and the places
  they disagree are marked; an AU/NZ medicine dictionary (generic and brand names, misspellings,
  Fady's own words) suggests medicines; a review mode steps through each place with the audio;
  corrections are a layer beside the sealed run, with undo; exports use the corrected text; a
  corrected session can be marked right so the Lab scores both engines on Fady's own voice.
- **Phase 2b, speakers and biasing:** speaker layer with two speakers by default (Speaker 1 and 2,
  named later), short turns merged, words assigned to speakers by time, lines moved between
  speakers; Lab trial of Parakeet with the medicine list as hotwords (modified beam search),
  kept only if it measurably helps on Fady's recordings.
- **Phase 3, notes:** facts extracted first and tied to transcript positions; anything without a
  source rejected; note written section by section in the inpatient review style.
- **Phase 4, checking:** every drug name, dose and number in a note checked against the
  transcript, tap a sentence to hear that moment.
- **Phase 5, polish:** live captions, storage management, encrypted vault backup/restore with a
  passphrase, more note templates.
