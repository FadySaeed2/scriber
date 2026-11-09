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
- **Phase 2a, second opinion and corrections (shipped 0.0.17):** Parakeet is the main transcript in both
  modes and MedASR runs after it on every session; the two are lined up word by word and the places
  they disagree are marked; an AU/NZ medicine dictionary (generic and brand names, misspellings,
  Fady's own words) suggests medicines; a review mode steps through each place with the audio;
  corrections are a layer beside the sealed run, with undo; exports use the corrected text; a
  corrected session can be marked right so the Lab scores both engines on Fady's own voice.
- **Phase 2b, speakers and biasing (this build):** speaker layer with two speakers by default (Speaker 1 and 2,
  named later), short turns merged, words assigned to speakers by time, lines moved between
  speakers; Lab trial of Parakeet with the medicine list as hotwords (modified beam search),
  kept only if it measurably helps on Fady's recordings.
- **Phase 3a, notes on the phone (0.0.35 to 0.0.38, then removed):** Gemma 4 E4B wrote notes
  from facts checked against the transcript. On a real 27-minute session it missed most of the
  history and mixed the clinician's explanations with the patient's medicines; dropped.
- **Phase 3, transcription only (this build):** no language model on the phone. Copy gives a clean
  transcript (medicine names from MedASR, no fillers or stutters, one paragraph per speaker,
  optional times) on its own or under a prompt for a cloud model; twelve built-in prompts
  (inpatient review, follow-up, telehealth, ADHD assessment, initial assessment, letter to GP,
  medication review, discharge summary, family meeting, Mental Health Act report, meeting minutes,
  dictation tidy), all editable, plus Fady's own. A meeting mode finds how many people speak, up
  to two hours.
- **Phase 4, finishing (this build):** MedASR runs straight after Parakeet, before speakers, and the
  medicine check shows once, when it is complete. Long transcripts sit in their own scrolling
  window (only the lines on screen are drawn, so two-hour meetings stay smooth), with find, next and
  previous, and a whole-screen view. Settings show how much room the audio takes, the sessions
  taking the most, and can delete audio older than 30 days while keeping transcripts. Backup and
  restore of everything in one file locked with a passphrase; a restore only adds what the phone
  does not have.
- **Still open, needing Fady's recordings:** Parakeet with the medicine list as hotwords (Lab trial
  on his corrected sessions); speakers in a real meeting over 40 minutes.
