# Pocket TTS for Android

A native Android app that reads text aloud in Pocket TTS voices, entirely on the
device. No server, no network at speaking time.

It exists to remove a specific papercut: wanting to hear a paragraph in a good
voice and having to paste it into a notes app or a pastebin to get there.

## What it gives you

**A "Read aloud" item in the tap-and-hold text selection menu.** Select text
anywhere - a browser, a chat app, a PDF reader - and Pocket TTS appears in the
selection toolbar next to Copy and Share. Tapping it starts reading immediately
in a small floating window with pause and stop. This is an
`ACTION_PROCESS_TEXT` activity; apps that draw their own selection toolbar and
suppress the system one will not show it, and for those there is a share-sheet
entry as well.

**A system-wide text-to-speech engine.** Once Pocket TTS is selected under
*Settings → Accessibility → Text-to-speech output*, every app that already knows
how to read text uses these voices: Select to Speak, Chrome's read-aloud, ebook
readers, accessibility tools. None of them need to know this app exists. Audio
is streamed back chunk by chunk as it is generated, so playback starts after the
first sentence rather than the last one.

**A Markdown scratchpad.** Type or paste, hit speak. Markdown is rendered for
reading and stripped for speaking, so `## Heading` is spoken as "Heading" rather
than "hash hash Heading", `**bold**` loses its asterisks, links are read as
their text instead of spelling out a URL, and code fences are skipped by
default. Selecting a passage in the editor and hitting speak reads just that
passage.

**Any Pocket TTS voice, including your own.** Pocket TTS has no fixed speaker
table - a voice is a few seconds of reference audio the model conditions on - so
the stock voices and a wav you record yourself are the same kind of thing. The
voice picker lists the catalogue from
[`kyutai/tts-voices`](https://huggingface.co/kyutai/tts-voices) and can import a
local wav.

## Building

Requires JDK 17+ and the Android SDK (compileSdk 35, build-tools 35.0.0).

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug        # or: gradle :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The debug APK lands in `app/build/outputs/apk/debug/`. It is about 60 MB, almost
all of it the ONNX Runtime and sherpa-onnx native libraries for `arm64-v8a` and
`armeabi-v7a`. Dropping `armeabi-v7a` from `abiFilters` in `app/build.gradle.kts`
roughly halves that if you only care about 64-bit devices.

## First run

The model is not in the APK. On first launch the app downloads
`sherpa-onnx-pocket-tts-int8-2026-01-26` - a 98 MB download that unpacks to
about 200 MB - into app storage. Voice prompts are a few hundred kilobytes each
and are fetched the first time each voice is used.

Inference runs on the CPU. Expect roughly real-time synthesis on a recent phone,
which the streaming design hides well for continuous reading but does mean a
short delay before the first words. It is not instant the way a small
concatenative engine is.

## How it works

```
selected text ──▶ ReadAloudActivity ─┐
scratchpad ─────▶ ScratchpadActivity ─┼─▶ Reader ─▶ StreamingPlayer (AudioTrack)
                                      │      │
other apps ─────▶ PocketTtsService ───┘      ▼
                  (system TTS engine)   MarkdownSpeech ─▶ TextChunker ─▶ PocketTts
                                                                            │
                                                                       sherpa-onnx
```

- `speech/MarkdownSpeech` turns Markdown into speakable prose.
- `speech/TextChunker` cuts it at sentence boundaries so the first audio arrives
  quickly and stopping is responsive.
- `engine/PocketTts` owns the single loaded model and serialises synthesis.
- `player/StreamingPlayer` writes float PCM to an `AudioTrack` with blocking
  writes, which back-pressures generation to the speed of playback.
- `player/PlaybackService` is a foreground service so reading survives the
  floating window being dismissed.

`MarkdownSpeech`, `TextChunker` and `WavReader` are plain JVM code and are
covered by unit tests in `app/src/test`.

`ActivityLaunchTest` drives every activity through its real lifecycle under
Robolectric. A theme, a manifest entry and a layout can crash an activity just
as easily as a function can, and none of them are type-checked — an early build
shipped a theme that supplied a decor action bar to screens that also called
`setSupportActionBar`, which compiled, packaged, and then threw the moment
either screen was opened. These tests fail on that.

## Edge-to-edge

`targetSdk 35` means Android 15 lays every window out edge to edge, so the
system bars are drawn over the content rather than beside it. `ui/Insets.kt`
pays those insets back as padding: each screen names the view that absorbs the
top inset (its app bar) and the one that absorbs the bottom. Without it the
toolbar wears the status bar and the first control is sliced in half.

## Licensing

Worth reading before shipping anything built from this.

- The ONNX export bundled by sherpa-onnx carries a
  [CC-BY-4.0 `LICENSE` file](https://huggingface.co/KevinAHM/pocket-tts-onnx),
  but its README states the export is for non-commercial use. Those two
  statements do not agree; treat the narrower one as binding until the exporter
  clarifies.
- Voices have individual licences, listed per voice at
  [`kyutai/tts-voices`](https://huggingface.co/kyutai/tts-voices).
- Pocket TTS's own prohibited-use terms apply, in particular the ban on cloning
  someone's voice without their explicit and lawful consent. The import-a-wav
  feature makes that easy to do accidentally.

## Prior art

If you would rather not build anything:

- [NekoSpeak](https://github.com/siva-sub/NekoSpeak) (MIT) is an offline Android
  TTS engine that already supports Pocket TTS alongside Kokoro, KittenTTS and
  Piper, with voice cloning. It registers as a system engine but does not add a
  text-selection menu item or a scratchpad.
- [TTS Util](https://github.com/Danesprite/tts-util-app) (Apache 2.0) adds
  share-sheet entries and can read the clipboard through whichever system engine
  you have selected.
- [SherpaOnnxTtsEngineAndroid](https://github.com/jing332/SherpaOnnxTtsEngineAndroid)
  wraps sherpa-onnx as a system engine with in-app model downloads.

Pairing NekoSpeak (or this app's engine) with TTS Util gets you most of the way
without writing code; this app exists mainly to put all three pieces behind one
tap.
