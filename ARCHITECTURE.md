# Architecture

## Principles

1. **Nothing about a patient leaves the phone.** No analytics, no crash upload, no cloud backup.
2. **The original is never touched.** Each layer is written once and never edited in place; later
   layers sit on top of it. Any mistake higher up can always be traced back to the source.
3. **Every model claim is checkable.** Model files are pinned to exact revisions and SHA-256 hashes.
   Note text (from Phase 3) will be traceable to the words and the moment in the audio it came from.
4. **Fail closed.** A file that does not match its checksum is deleted, a host that is not on the
   list is refused, and the app will not open on a phone without a screen lock.

## Data layers (built from Phase 1 on)

```
Recording (original audio, recorded here or imported, kept byte for byte with its SHA-256)
  └─ Transcription run (engine, model version, settings, date) → raw result, sealed when finished
       └─ Speaker layer (who spoke when), its own record
            └─ Edits (corrections as a separate layer over the raw result, never in it)
                 └─ Note versions (each knows which run and which edits it came from)
```

Raw transcripts can be exported as plain text with timestamps, SRT/VTT, or JSON with word timings
and confidence, for processing with any outside model.

## Modules

| Module | Job | Network |
|---|---|---|
| `core` | Plain Kotlin: model catalogue, allowed hosts, resume maths, hashing, word error rate | none |
| `vault` | Encrypted storage (Tink: streaming AES-256-GCM for files, AES-256-GCM for records; keys wrapped by an Android Keystore master key) | none |
| `fetch` | Downloads catalogue files, resumes, verifies, then locks itself | **only module with network code** |
| `app` | Screens, speech engines (sherpa-onnx), language model (LiteRT-LM), Lab | none |

`PrivacyGuardTest` fails the build if network code appears outside `fetch`, if any manifest other
than `fetch`'s asks for the internet, or if the network security config trusts anything beyond the
four download domains.

## Network

The internet permission exists only so models can be downloaded once. Three independent controls:

1. **Android network security config** (`app/src/main/res/xml/network_security_config.xml`): by
   default no certificate is trusted, so any connection to any other host fails at the TLS
   handshake. Only `huggingface.co`, `hf.co`, `github.com`, `githubusercontent.com` (and their
   subdomains, where files are actually served) use the system trust store. Cleartext http is
   refused everywhere. Verified on an emulator by `NetworkPolicyOnDeviceTest`.
2. **The downloader** follows redirects by hand and refuses any hop outside the same list.
3. **FetchLock**: once all needed packs are verified, downloads lock. Unlocking needs the phone
   owner's fingerprint or screen lock.

## Models (Phase 0 catalogue, `core/src/main/resources/model_catalog.json`)

| Pack | Model | Size | Runtime |
|---|---|---|---|
| Speech to text (main) | NVIDIA Parakeet TDT 0.6B v2, int8 | 661 MB | sherpa-onnx 2.0.1 |
| Speech to text (medical) | Google MedASR, int8 | 154 MB | sherpa-onnx 2.0.1 |
| Voice activity | Silero VAD | 0.6 MB | sherpa-onnx |
| Speaker turns | pyannote segmentation 3.0 + CAM++ voiceprint | 31 MB | sherpa-onnx |
| Note writer | Gemma 4 E4B (12 GB phones) or E2B (8 GB phones) | 3.66 / 2.59 GB | LiteRT-LM 0.19.1, GPU first |
| Test audio | Six medical dictations with reference text | 5 MB | Lab only |

The `Check model files` workflow streams every file weekly and fails on any size, checksum or
redirect-host change.

## Storage on the phone

- Models: `noBackupFilesDir/models/<pack>/`, public files, not encrypted (engines memory-map them).
- Everything else: `noBackupFilesDir/vault/`, encrypted, each file bound to its own name.
- `allowBackup=false` plus data-extraction rules exclude every domain from cloud backup and
  device transfer.

## Screen protection

`FLAG_SECURE` (no screenshots, no recent-apps preview), identity prompt on open and after two
minutes in the background.
