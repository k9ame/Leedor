# Third-Party Notices — Leedor (侍读郎)

Leedor (侍读郎) is a fork of **NekoSpeak** and bundles or links several third-party
components. This file lists them and their licenses.

**Verified** below means the license text was fetched from the upstream project
and read directly.

---

## ⚠️ 1. Copyleft components — these determine the license of the shipped APK

These are the components that make a distributed Leedor APK a **GPL-3.0 combined
work**, not a pure MIT work. Read [`COPYING`](COPYING) (GPL-3.0 full text).

| Component | License | Where it lives | Verified |
|---|---|---|---|
| **eSpeak-NG** | **GPL-3.0-or-later** | `app/src/main/jniLibs/<abi>/libespeak-ng.so` — **built by this project from upstream source**<br>`app/src/main/assets/espeak-ng-data/` (19 MB) | ✅ [espeak-ng/COPYING](https://raw.githubusercontent.com/espeak-ng/espeak-ng/master/COPYING) |
| **Book-Story theme code** | **GPL-3.0** | `app/src/main/java/com/leedor/tts/ui/theme/` — see the header comment in `Theme.kt` | ⚠️ per the in-source attribution comment only |

> **History:** the project previously shipped a third-party prebuilt
> `libttsespeak.so` (a `com.reecedunn.espeak` build) with no accompanying source
> revision or licence file. It was replaced with a self-built `libespeak-ng.so`
> for 16 KB page alignment — see [docs/16kb-alignment.md](docs/16kb-alignment.md).
> That change also **resolved the provenance gap**: we now know exactly which
> upstream revision we ship.

### eSpeak-NG build provenance (needed for GPL-3.0 compliance)

| | |
|---|---|
| Upstream | https://github.com/espeak-ng/espeak-ng |
| Version | master, `CMakeLists.txt` `VERSION 1.53.0` |
| Source tarball sha256 | `b4c30c70d990587833b4e47735db5ac962a783373f7c05c207ce247008a26296` |
| Build script | [`docs/build-espeak-ng-android.sh`](docs/build-espeak-ng-android.sh) |
| Data version | `0x014801` (matches the bundled `espeak-ng-data`) |

To satisfy GPL-3.0 when distributing an APK, publish this script together with
the corresponding upstream source revision.

### Why this matters

`app/src/main/cpp/CMakeLists.txt` does an explicit link:

```cmake
add_library(espeak-ng SHARED IMPORTED)
set_target_properties(espeak-ng PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libespeak-ng.so)

target_link_libraries(leedor espeak-ng ${log-lib})
```

eSpeak-NG therefore runs **in the same process** as the app. This is a combined
work, not mere aggregation, so **GPL-3.0 §5(c)** applies: the whole combined work
must be licensed under GPL-3.0 to anyone who receives it.

### What this means in practice

- ✅ You may rename, rebrand, modify, and even sell the app.
- ✅ Private/internal use triggers no distribution obligations at all.
- ⚠️ If you **distribute** the APK, you must make the **complete corresponding
  source** available to recipients — including your own Kotlin modifications —
  under GPL-3.0.
- ❌ You cannot distribute it as closed-source or add terms stricter than GPL-3.0.

---

## 2. Permissive components

| Component | License | Notes | Verified |
|---|---|---|---|
| **NekoSpeak** (upstream app code) | MIT | See [`LICENSE`](LICENSE); original copyright notice is retained as required | ✅ |
| **ONNX Runtime** (`com.microsoft.onnxruntime:onnxruntime-android:1.22.0`) | MIT | `libonnxruntime.so`, `libonnxruntime4j_jni.so` | — |
| **Misaki** — G2P port + lexicons | Apache-2.0 | Ported logic in `engine/misaki/`; `assets/us_gold.json`, `us_silver.json`, `gb_gold.json`, `gb_silver.json` | ✅ [hexgrad/misaki/LICENSE](https://raw.githubusercontent.com/hexgrad/misaki/main/LICENSE) |
| **JTransforms** (`com.github.wendykierp:JTransforms:3.2`) | BSD-2-Clause | Used for GTCRN denoising STFT/iSTFT. *Note: often mislabelled as MPL — it is not.* | ✅ [LICENSE](https://raw.githubusercontent.com/wendykierp/JTransforms/master/LICENSE) |
| **AndroidX / Jetpack Compose / Material 3** | Apache-2.0 | | — |
| **Android Material Components** (`com.google.android.material:material`) | Apache-2.0 | | — |
| **Kotlin / kotlinx.coroutines** | Apache-2.0 | | — |
| **Gson** (`com.google.code.gson:gson`) | Apache-2.0 | | — |

---

## 3. Models and voice data

Some models ship inside the APK; others are downloaded on first use and are
therefore **not** covered by the app's source license.

| Component | License | Bundled in APK? | Verified |
|---|---|---|---|
| **Piper** (engine) | MIT | n/a (Kotlin reimplementation in `engine/piper/`) | — |
| **Piper voice `en_US-amy-low`** | ⚠️ per-voice | ✅ Yes — `assets/piper/en_US-amy-low.onnx` (63 MB) | ❌ rhasspy/piper-voices publishes no repo-level license file; check the MODEL_CARD for this voice |
| **Piper community voices** | ⚠️ per-voice | ❌ Downloaded via `assets/piper/voices.json` (60+ languages, incl. `zh_CN-huayan-medium`) | ❌ verify per voice |
| **Kokoro v1.0** | Apache-2.0 | ❌ Downloaded (~115 MB) | — |
| **Kitten TTS Nano** | ⚠️ unverified | ❌ Downloaded (~23 MB) | ❌ verify with KittenML |
| **Pocket-TTS** (Kyutai) | CC-BY-4.0 | ❌ Downloaded (~176 MB) | — |
| **Celebrity voice dataset** (`sdialog/voices-celebrities`) | ⚠️ unverified | ❌ Downloaded on demand | ❌ **See the legal warning below** |

### 🚨 Legal warning: celebrity voices

`app/src/main/java/com/leedor/tts/data/VoiceDefinitions.kt` (`CELEBRITY_VOICES`)
ships voice-cloning profiles for real public figures. This is a **personality /
voice-rights** problem, entirely separate from copyright:

- PRC Civil Code Art. 1023 protects a natural person's voice; Art. 1019-1020 cover
  likeness and name. Cloning a real person's voice without consent is actionable.
- Chinese app stores are very likely to reject an app shipping these.
- Other jurisdictions have equivalent publicity-rights regimes.

**Recommendation: remove the `CELEBRITY_VOICES` list before any public release.**

---

## 4. Attribution retained from upstream

Per the MIT terms in [`LICENSE`](LICENSE), credit to the original NekoSpeak authors
is retained. Upstream acknowledges the following projects, which remain relevant:

- [Pocket-TTS](https://github.com/kyutai-labs/pocket-tts) — Kyutai Labs
- [pocket-tts-onnx-export](https://github.com/KevinAHM/pocket-tts-onnx-export) — KevinAHM
- [Kokoro-ONNX](https://github.com/thewh1teagle/kokoro-onnx) — thewh1teagle
- [KittenTTS](https://github.com/KittenML/KittenTTS) — KittenML
- [Piper](https://github.com/rhasspy/piper) — Rhasspy
- [Misaki](https://github.com/hexgrad/misaki) — hexgrad
- [eSpeak-NG](https://github.com/espeak-ng/espeak-ng)

---

## 5. Action checklist before public distribution

- [ ] Ship [`COPYING`](COPYING) (GPL-3.0) alongside the APK or in an in-app
      "Open source licenses" screen.
- [ ] Publish the **complete corresponding source** for the exact APK revision.
- [ ] Add the full Apache-2.0 / BSD-2-Clause texts to an in-app notices screen.
- [ ] Verify the license of the bundled `en_US-amy-low` Piper voice.
- [ ] Verify the Kitten TTS and celebrity-dataset licenses.
- [ ] **Remove `CELEBRITY_VOICES`** or obtain consent for every voice profile.
- [x] ~~Resolve the third-party license status of `libttsespeak.so`~~ — done:
      replaced by a self-built `libespeak-ng.so` (see docs/16kb-alignment.md).

---

*This file is a good-faith engineering summary, not legal advice. If you plan to
distribute or commercialise this app, have a lawyer review it.*
