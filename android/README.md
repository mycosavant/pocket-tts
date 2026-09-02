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
local wav. Each row plays a short sample, because twenty one names tell you
nothing about which one you want.

The sample is the voice's own reference audio rather than a synthesised
sentence. That is the most direct answer to "what does this sound like": the
reference audio *is* the voice. It also costs nothing but the prompt download -
no model bundle, no inference, no wait - so voices can be auditioned on a fresh
install before the 98 MB model has ever been fetched, and the download it does
is the same one selecting that voice would trigger later. The trade-off worth
knowing: it is the raw prompt, so it carries the recording's own room and
pacing. The synthesised voice tracks its timbre closely, which is the thing
being chosen, but not its background.

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

## Two callers, one engine

There is a single model in the process and two things drive it: the in-app
reader, and the system text-to-speech service other apps call. `synthesize`
serialised them per *chunk*, so with both active they took turns sentence by
sentence and a passage came out read alternately in two different voices.
Neither side knew the other existed.

Serialising whole utterances instead would replace the alternation with a wait:
an app asking through Select to Speak would hang until a whole document finished
reading, which looks broken from the other side. So `engine/EngineTurn` makes
the most recent request win. A caller takes a turn before it starts and checks
it as it goes - per chunk and per audio callback, so a request arriving
mid-sentence does not have to wait out the rest of it. Finding the turn has
moved on means somebody asked more recently, and standing down is the only
thing that produces intelligible audio.

## The reader has one state machine, and it is testable

`Idle` used to mean two things - "nothing has been asked for" and "the read is
over" - so `stopLocked` published it between two utterances, and three screens
each kept a boolean to tell those apart. `StateFlow` conflates, so whether
anyone saw that transitional value was a race: the scratchpad overlay sometimes
failed to appear on a second Speak, and the foreground service sometimes
stopped and immediately restarted.

`Reader.State` now ends in `Finished`, `Stopped` or `Failed`, each carrying the
utterance it belongs to and who asked for it. The three flags are gone. Identity
matters as much as finality: the reader is process-wide, so a screen has to tell
its own read's ending from an earlier one it never asked for - the floating
window would otherwise close itself on stale news before its own read had begun.

`player/SpeechEngine` and `player/AudioSink` exist so this can be tested at all.
Before them, driving the reader meant a 98 MB model and an `AudioTrack`, which
is why the part that had actually been wrong had no tests - and why
`ActivityLaunchTest` reached `ensureModel` and quietly downloaded the model
bundle on every run of the unit suite.

Skipping is built on the same seam. An utterance keeps its chunks, its engine
and its loaded voice, so back a sentence costs that sentence and nothing else:
no re-selection, no reload. Back at the first sentence replays it; forward past
the last one ends the read, which is what someone who keeps tapping forward
means by it.

`GlassPanelViewTest` asserts the two things that were missing when the sliders
went dead: where the panel thinks it is relative to its backdrop, and what
colour it actually put on the canvas. Its predecessor asserted only that drawing
did not throw, and Robolectric's software canvas made the panel bail before it
ever reached the geometry - so the broken function was never called by any test.

`ActivityLaunchTest` drives every activity through its real lifecycle under
Robolectric, and forces a measure and layout pass at a realistic size. A theme,
a manifest entry and a layout can crash an activity just as easily as a function
can, and none of them are type-checked — one build shipped a theme that supplied
a decor action bar to screens that also called `setSupportActionBar`, and
another handed a Material `Slider` a value off its step grid. Both compiled,
packaged, and threw the instant the screen opened.

The layout pass is the part that earns its keep. Views validate themselves while
sizing, so reaching RESUMED proves very little: the slider bug passed a version
of these tests that stopped at `setup()`, because nothing had been laid out yet.

## When it crashes

Sideloading onto a phone means no logcat within reach, and an app that dies
silently reports exactly one bit: "it crashed". `debug/CrashLog` writes the last
uncaught exception to app storage on the way down, and the next launch offers to
share it. It chains to the previous handler rather than swallowing the crash, so
the process still dies and the system dialog still appears - only the evidence
survives.

`CrashLog` alone cannot catch native crashes, ANRs or low-memory kills - none of
them unwind through the JVM - and those look identical from the outside to a
Kotlin exception. `debug/ExitReasons` closes that gap by reading the platform's
own record via `ApplicationExitInfo`, which names the kind of death and, for a
native crash on Android 12+, carries the tombstone. That reason code is the fact
that decides what to fix; the two sources are shown together on next launch.

## The JNI callback is not a lambda, on purpose

`PocketTts.audioCallback` wraps the streaming callback in an object expression
rather than passing a lambda. sherpa-onnx resolves that callback from native
code by name and exact signature - `invoke([F)Ljava/lang/Integer;`.

Kotlin 2.0 emits lambdas via `invokedynamic`, and D8 desugars them into
`$$ExternalSyntheticLambda` classes carrying only the erased
`invoke(Object)Object`. The specialised method is absent, `GetMethodID` returns
nothing, the JNI call proceeds with a pending exception, and the runtime aborts
the process. It surfaces as `REASON_CRASH_NATIVE` with no Java stack trace, from
code that is entirely ordinary Kotlin and compiles without a warning.

Nothing in Kotlin references that signature, so nothing in Kotlin breaks when it
disappears. `PocketTtsCallbackTest` asserts it by reflection and fails if the
callback becomes a lambda again.

It then disappeared a second time, by a different route. R8 inlined the
specialised method into its own bridge, leaving one method whose descriptor is
`(Ljava/lang/Object;)Ljava/lang/Object;` - the reflective test still passed,
because it reflects over the unshrunk class. Release builds have no CheckJNI, so
instead of aborting they synthesised entire utterances into nowhere and reported
a failure after a long silence. Nobody noticed for a simple reason: CI built
only `assembleDebug`, so every APK ever sideloaded was a debug build.

`proguard-rules.pro` now keeps the method, and `tools/check-jni-callback.sh`
reads the built APK with `dexdump` and fails if no method declares that
descriptor. Both APKs are built and checked in CI, and both are uploaded, so
the shrunk build is something that gets installed rather than something that is
assumed to work. The source is not the artefact, and only the artefact knows.

## Glass, and where it cannot exist

Blurring depends entirely on what is behind the panel, and the two cases have
different answers.

Behind **another app**, only `Window.setBackgroundBlurRadius` can blur, because
an app may not read another app's pixels. That API is gated on a vendor opt-in
(`ro.surface_flinger.supports_background_blur`) and Samsung does not set it -
their [developer forum](https://forum.developer.samsung.com/t/why-does-oneui-not-support-crosswindowblur/34386)
confirms cross-window blur is unsupported across One UI, S24 Ultra included. On
those devices the effect is unavailable and no radius or alpha will produce it,
so the panel is drawn near-opaque instead of leaving unreadable text floating
over someone else's app.

Behind **our own content**, `ui/GlassPanelView` gives a true
`backdrop-filter: blur()`: the blur is confined to the panel's own rounded
bounds and everything outside stays sharp. Each frame it records the backdrop
view into a `RenderNode`, offset so the slice behind the panel lands at the
panel's origin, hangs a blur `RenderEffect` on that node, and draws it clipped
to the panel - then the dim, then the tint, then the hairline.

That offset is the whole trick, and getting it wrong is silent. The panel has to
sit *outside* the backdrop - capturing an ancestor would draw the panel into its
own backdrop and recurse - so the backdrop is always a sibling. An earlier
version located the panel by walking *up* its parent chain looking for the
backdrop, which a sibling is never on: the walk ran off the top of the
hierarchy, the capture declined, and every panel fell back to a flat tint with
the configured opacity thrown away. The appearance sliders appeared to do
nothing, because for three of the four that was exactly true. Both views are now
located against the root they share and the difference is taken, which is
correct for siblings, cousins and ancestors alike.

Because that failure has no visual signature - a panel that quietly gave up
looks like settings that were never wired - every frame records *why* it drew
what it drew in `lastDraw`, and the appearance screen prints it. A log line is
no use on a phone with no logcat within reach.

An earlier version applied `RenderEffect` to the *backdrop view itself*, which
is a different effect: CSS `filter: blur()` on the sibling. It softens the whole
screen and leaves the panel with no material of its own, because there is
nothing left for it to blur. The capture is padded by the blur radius on every
side, or the kernel samples past what was recorded and the panel's border smears
into a halo.

This is the technique [Haze](https://chrisbanes.github.io/haze/) uses on
Android. Haze is Compose Multiplatform and this app is Views, so the technique
is borrowed and the dependency is not.

`ui/AppearanceActivity` exposes opacity, blur, dim and corner radius as sliders,
because how this reads depends on the wallpaper-derived palette and the display
density - neither of which can be judged from source. Every label reports the
value in the form the source wants (`0.67f · alpha 171 · 67%`), and a button
copies the whole set as pasteable Kotlin. It also states plainly which blur
strategy is live on the device, so nobody spends an evening tuning a value that
cannot work.

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
