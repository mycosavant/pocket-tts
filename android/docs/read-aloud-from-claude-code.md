# Reading Claude Code's replies aloud

One command on the phone, and the reply that just finished is read by Pocket
TTS - in the app's own voice, with the app's own transport controls in the
notification and on the lock screen.

It replaces the manual loop: `/copy`, switch apps, paste into the scratchpad,
tap read.

## How it fits together

```
Claude Code (proot Ubuntu, under Termux)
  └─ speak-last.sh          finds the transcript, pulls the last reply
       └─ am broadcast      →  SpeakReceiver
                                 └─ Reader.speak(…, Source.Agent)
                                 └─ PlaybackService   notification, lock screen,
                                                      media session, audio focus
```

Nothing about the reading is new. `SpeakReceiver` takes the same route through
`Reader` and `PlaybackService` that the floating "Read aloud" window takes, so
Markdown is stripped by `MarkdownSpeech` exactly as it is for the scratchpad,
and the four transport controls are the same four.

The receiver exists for one reason: **it works with the screen off.** Starting
an activity needs a foreground caller, so a read triggered that way is only ever
as headless as whoever asked for it. A broadcast has no such requirement.

## Install, on the phone

```bash
# 1. the script
mkdir -p ~/.claude/hooks
cp android/tools/speak-last.sh ~/.claude/hooks/
chmod +x ~/.claude/hooks/speak-last.sh

# 2. the slash command
mkdir -p ~/.claude/commands
cp android/tools/claude/speak.md ~/.claude/commands/

# 3. the zero-turn version - see below
mkdir -p ~/.local/bin
ln -sf ~/.claude/hooks/speak-last.sh ~/.local/bin/speak-last
```

`jq` is the only dependency: `apt install jq` inside proot, or `pkg install jq`
in Termux.

## Using it

| what you type | costs | notes |
|---------------|-------|-------|
| `!speak-last` | nothing | runs in-session, no model turn |
| `/speak`      | one round trip | the slash command |

**Prefer `!speak-last`.** A leading `!` at the Claude Code prompt runs a shell
command in the session without consuming a turn. `/speak` has to go to the
model and come back, and at the rate this gets used that is the difference
between a free action and a paid one. The symlink in step 3 is what makes the
bare name work - a shell alias would not, since the command does not run in an
interactive shell.

`~/.claude/keybindings.json` rebinds keys within Claude Code; it is not a way to
run a shell command, so there is no single-keystroke version of this today.

## The last reply is the one on screen

Typed at the prompt, the last `assistant` record in the transcript is the reply
that just finished - the one you are looking at. That is the intent, not an
off-by-one.

## Modes

Set `CLAUDE_TTS`:

| mode | what it does |
|------|--------------|
| `broadcast` | **default.** `SpeakReceiver`. No window, works with the screen off. |
| `pocket` | `ReadAloudActivity`, which opens the floating sheet. Needs the caller in the foreground. |
| `engine` | `termux-tts-speak`. For when the app is not installed. The only mode that strips Markdown in the script, because it is the only one whose far end will not. |
| `print` | Prints what it would read and stops. Start here when nothing comes out. |

## Long replies

A reply over ~32,000 characters is split on paragraph boundaries and sent as a
queue: the first piece replaces whatever is playing, the rest arrive with
`append=true` and read in order. Without that each piece would silence the one
before it, and a long reply would play as the last few words of its last
paragraph.

The receiver also takes a `path` extra, and that is the better answer on
paper - except that a file written by Termux is not a file Pocket TTS can open.
They are different uids with private storage, and neither `/data/local/tmp` nor
`/sdcard/Android/data` bridges that on a current Android without root. `path` is
there for the app's own files and for `adb`-driven testing; the queue is what
the phone actually uses.

## Also a Stop hook

The script reads hook JSON from stdin when it is given any, so the same file can
be registered as a `Stop` hook with no changes:

```json
{ "hooks": { "Stop": [ { "hooks": [ { "type": "command",
  "command": "~/.claude/hooks/speak-last.sh" } ] } ] } }
```

## Controls, and the battery setting they need

A read started this way begins with Pocket TTS in the background, and Android
refuses a foreground service started from there. The read still plays - the
reader does not depend on the service - but without it there is no notification,
no lock-screen or quick-settings controls, no audio-focus claim, and nothing
stopping the system killing the read mid-sentence.

Allowing Pocket TTS to run unrestricted in the battery settings is what grants
it. On a Samsung device: *Settings → Apps → Pocket TTS → Battery →
Unrestricted*. With that set, a broadcast read appears in the shade with skip
back, pause and stop, forward once the player is expanded, and on the lock
screen, and answers the buttons on a headset - "next" included.

A skip moves by chunk: a paragraph, or up to a few hundred characters of one.
A short reply is one chunk, so forward on it ends the read, and starts the next
queued piece if there is one.

Timings says which of the two happened - `playback service: started`, or
`refused (...)` with the reason - so "it read but there were no controls" has an
answer on the device rather than in a log nobody can reach.

## When nothing comes out

The whole point of this path is that there is no window, which also means there
is nothing on screen to tell you what went wrong.

```bash
# 1. Is it the script or the app?
CLAUDE_TTS=print ~/.claude/hooks/speak-last.sh

# 2. Does the app read a fixed string?
am broadcast -n org.pockettts.android/.SpeakReceiver --es text "Receiver check."
```

`am` cannot confirm delivery. It prints `Broadcast sent without waiting for
result` and exits 0 whether the receiver handled it, does not exist, or the
package is not installed at all - so a successful-looking run proves only that
the intent left Termux. `utterances read` in Timings is the confirmation.

It *does* exit 1 when the platform refuses the broadcast outright. On Android 16
that happens if the user id is left implicit:

```
SecurityException: Permission Denial: getIntentSender asks to run as user -2
but is calling from uid u0aNNN; this requires INTERACT_ACROSS_USERS_FULL
or android.permission.INTERACT_ACROSS_USERS
```

`-2` is `USER_CURRENT`, which termux-am passes by default and which the platform
will no longer resolve from an app uid. The script sends an explicit `--user 0`;
override with `CLAUDE_TTS_USER` on a work profile or secondary user. `--user
current` is not an alternative - it maps to the same -2.

Then open Pocket TTS and tap **Timings**. That is the instrument, not `logcat`:
Android stopped letting an app read another app's logs at 4.1, and Termux is an
ordinary app, so `logcat` from there shows you Termux and nothing else. Reading
the app's own log needs adb - over wireless debugging, to the phone from itself,
if you want it.

Timings answers most of it directly:

| Line | What it settles |
|------|-----------------|
| `utterances read` | Whether the broadcast reached `Reader` at all. Force stop the app first so the count starts from zero and there is no doubt which read you are looking at. |
| `time to first audio` | A cold start pays for the model bundle and the voice prompt inside the read - tens of seconds, and not a fault. The second read tells you the real number. |
| `generation speed` | Below 1.0x real time means the model cannot keep up with its own playback, and gaps are arithmetic rather than bad luck. Measured on the first chunk of a read only. |
| `[chunk n] … first=NNNms` | Wall clock from asking for that chunk to its first sample. That is the model's *whole* pass over the chunk - the engine composes every frame before the decoder produces a sample - so it scales with the chunk's length, and a chunk that is one stripped table row still pays a full pass. The reader composes each chunk while the previous one plays, so this only becomes a silence when it is longer than the previous chunk's audio. Expect the first chunk of a cold read to be far larger than the rest. |
| `playback service` | Whether the read got its notification and controls; see above. |
| `[agent] asked for X, got Y` | Which source asked, and whether the voice that answered is the one that was asked for. `FELL BACK` or a prompt size that is not the expected one is the whole diagnosis. |

A voice trace line reading `prompt file missing` on the *first* read after an
install is the prompt being fetched during that read; it is recorded after the
fetch, so seeing it on a warm read means the file really is gone.
