# Scriber

A private, fully offline transcription app for Android (built for a Galaxy S23 Ultra).
It records or imports a dictation, a consultation or a meeting, transcribes it on the phone,
checks medicine names with a second, medical speech engine and separates the speakers. Nothing
leaves the phone unless the clinician copies or exports it: the Copy button gives a clean
transcript, with a prompt above it, for a cloud model to write the note from.

Status: **Phase 0** (foundation): encrypted vault, identity lock, one-time model download with
checksums and a self-locking downloader, and a Lab screen that measures every model on the phone.

- How it is built and why: [ARCHITECTURE.md](ARCHITECTURE.md)
- What comes next: [PLAN.md](PLAN.md)
- Decisions and their reasons: [DECISIONS.md](DECISIONS.md)

Builds: every push to `main` runs unit tests, privacy guards, screenshot tests and on-device tests
on an Android emulator, then publishes a signed APK under Releases. Screenshots of every screen are
on the `screenshots` branch.
