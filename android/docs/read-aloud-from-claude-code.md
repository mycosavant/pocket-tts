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

## When nothing comes out

The whole point of this path is that there is no window, which also means there
is nothing on screen to tell you what went wrong. Work down the list:

```bash
# 1. Is it the script or the app?
CLAUDE_TTS=print ~/.claude/hooks/speak-last.sh

# 2. Does the app read a fixed string?
am broadcast -n org.pockettts.android/.SpeakReceiver --es text "Receiver check."

# 3. What did the app make of it?
logcat -d -s SpeakReceiver:* Reader:* PocketTts:* PlaybackService:* AudioFocus:*
```

Step 3 is the one that answers it. `SpeakReceiver` logs every broadcast it
accepts and every one it ignores, with the reason; `PocketTts` logs the model
load, which on a cold start is seconds of silence that is not a fault;
`AudioFocus` logs focus being taken away by something else.

If the sheet opens with the text in it and nothing is spoken, the read is
reaching `Reader` and the problem is downstream of it - model still loading,
media volume, or audio focus. The status line on the sheet says which.
