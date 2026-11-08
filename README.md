# Scriber

A private, fully offline clinical scribe for Android (built for a Galaxy S23 Ultra).
It records or imports a consultation, transcribes it on the phone, separates the speakers and drafts
a note in the clinician's own style. No audio, transcript or note ever leaves the phone.

Status: **Phase 0** (foundation): encrypted vault, identity lock, one-time model download with
checksums and a self-locking downloader, and a Lab screen that measures every model on the phone.

- How it is built and why: [ARCHITECTURE.md](ARCHITECTURE.md)
- What comes next: [PLAN.md](PLAN.md)
- Decisions and their reasons: [DECISIONS.md](DECISIONS.md)

Builds: every push to `main` runs unit tests, privacy guards, screenshot tests and on-device tests
on an Android emulator, then publishes a signed APK under Releases. Screenshots of every screen are
on the `screenshots` branch.
