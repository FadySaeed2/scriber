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

**2026-11-08. Speakers from voice prints made during transcription; no pyannote pass.** The full
pyannote pass took about a fifth of the session's length on the phone and its turns flipped inside
one speaker. Now each transcript line, cut where the words pause long enough for the other person
to start, gets a voice print (at most ten seconds from its middle; four seconds was measured as too unsteady) while Parakeet writes it; the
prints are grouped into voices; a piece too short for a print answers the question before it, or
else keeps the voice before it. Speakers appear with the transcript, and asking for another number
of voices only regroups saved prints. Finding speakers can be switched off in Settings, and hidden
per session.

**2026-11-08. Back to the speaker model first (0.0.26's way), speed from caching and order.** The
print-only way was fast but, on Fady's real conversation, badly wrong: cutting lines at every pause
made short, unsteady prints, and forcing two groups let a few odd pieces (a cough, a laugh) become
"the other voice". The synthetic two-voice test had not shown it. Now pyannote listens once, lines
are cut only where it heard a clear change, each piece gets a print of up to ten seconds, and short
pieces take the voice pyannote heard there. Speakers run straight after the main transcript (before
the second opinion), the transcript is readable meanwhile, and both pyannote's turns and the prints
are saved, so another number of voices only regroups them. The print-only way stays out until it
is measured on real conversations.

**2026-11-08. Second opinion shows medicines only.** Fady's use: Parakeet is far better at ordinary
speech; MedASR earns its place only on medicine names. Other differences stay in the data and come
back with a Settings switch.

**2026-11-08. Speed from measured core counts.** The phone mixes fast and power-saving cores, and an
engine on all of them can be slower than on a few. The Lab's speed check times each engine (Parakeet,
MedASR, the speaker model) on the same 30 s of test speech with 2 to 8 cores and keeps the fastest;
jobs use it. Same models, same audio, so the words stay the same; each sealed run records its cores.
GPU or NPU paths and smaller models were left out: less safe or less accurate.

**2026-11-09. Notes in two steps, code as the referee.** The model never writes from the transcript
directly. First it lists facts as KIND | line | fact | "exact words"; a fact stays only if its words
are really in that line and every number and medicine in it was said there. Then each section is
written from its facts alone, each sentence citing them, and code marks what it cannot trace. One
model load serves all prompts; each prompt is a fresh conversation, so nothing leaks between
sections. Templates are data (sections, instructions, form, length, fact kinds), so Fady changes
them on the phone without a new build; built-ins can always go back to how they came. Gemma 4 E4B
is the default writer for accuracy; E2B is used only if it is the one chosen in Models.

**2026-11-09. Notes, second round, after Fady's first real session.** On a 27-minute ADHD telehealth
assessment the first notes mixed the clinician's general explanation of medicines with the
patient's own, lost medicines the speech engine had misheard, missed most of the history, and
invented normal findings; code marked none of it because it only checked numbers and medicines.
Now: the note writer reads the transcript with medicine names the second opinion heard (Fady's
corrections first); the model first says which speaker is the clinician; facts come from about three
minutes of talk at a time, with the template's sections and a fixed psychiatric checklist to look
for, and with EDUCATION and ADVICE kept apart from the patient's medicines; a medicine the line does
not name is kept but marked to check. Each section is then checked twice: code (numbers, medicines,
words not in the facts) and the model (each sentence against its facts). A number nobody said, or a
contradiction, is red, and copying a note with red sentences asks first. The Lab scores notes from
two invented consultations against lists of what must and must not appear. Real sessions stay out
of the repository.

**2026-11-09. No language model on the phone.** After two rounds, Gemma 4 E4B still could not write
a usable note from a 27-minute consultation: too much of the history missing, the clinician's
general explanations taken for the patient's own medicines, and a quarter of an hour of a hot
phone. Fady will paste a clean transcript into a large cloud model instead, which is faster and far
better. So Scriber does transcription only: Parakeet for the words, MedASR for medicine names,
speakers, and a clean copy with prompts. The language model files, its runtime library, and the
notes, facts and templates kept so far are deleted on the first run of this version. Copying is
Fady's choice and leaves Scriber's encryption; the copy sheet says so once.

**2026-11-09. Meetings up to two hours.** Meetings find their own number of speakers. Past 40
minutes the speaker model hears the audio in half-hour pieces cut between lines, so the phone holds
one piece at a time; the voice prints of all pieces are grouped together, so a voice keeps its name
across the meeting. Grouping was rewritten to keep group likeness up to date as groups join, which
gives the same groups as before in a fraction of the time on a thousand or more pieces.

