# Plan

Each phase ships as a signed APK, is tried on the phone, and only then does the next start.

- **Phase 0, foundation (this build):** project, CI, signing, vault, identity lock, model download
  with checksums and self-lock, device check, Lab benchmarks.
- **Phase 1, recording, import and raw transcription:** foreground recording that survives screen
  lock, crash-safe chunks, lossless FLAC finalisation (16 kHz default, 48 kHz option), import of
  m4a/mp3/wav/ogg/opus/flac and the audio of video files (picker and share sheet), recording library,
  retention (keep forever by default, or delete after note approval, or after N days), transcription
  in batches during recording, sealed raw runs, export as text/SRT/VTT/JSON plus the audio.
- **Phase 2, speakers, more runs, editing:** speaker layer, naming speakers, re-transcription with
  another engine or settings, side-by-side comparison, non-destructive editor, corrections feeding a
  medication dictionary.
- **Phase 3, notes:** facts extracted first and tied to transcript positions; anything without a
  source rejected; note written section by section in the inpatient review style.
- **Phase 4, checking:** AU/NZ medication dictionary (psychotropics first), every drug name and
  number checked against the transcript, tap a sentence to hear that moment.
- **Phase 5, polish:** live captions, storage management, encrypted vault backup/restore with a
  passphrase, more note templates.
