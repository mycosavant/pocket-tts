#!/usr/bin/env bash
#
# Reads Claude Code's last reply aloud in Pocket TTS.
#
# Two ways in, deliberately:
#
#   - As a Stop hook, with the hook JSON on stdin. The transcript path comes
#     out of that.
#   - As a command the user types, with no stdin at all. The transcript is
#     found from CLAUDE_CODE_SESSION_ID instead.
#
# The second is what this is actually for. The first is kept working because a
# script that only runs when a person asks can be registered as a hook later
# without being rewritten, and one that assumes stdin cannot.
#
# Typed at the prompt, the "last assistant record" is the reply on screen - the
# one that just finished. That is the intent, not an off-by-one.
#
# Where the transcript comes from, first match wins:
#
#   CLAUDE_TTS_TRANSCRIPT=<file>   that file
#   CLAUDE_TTS_REMOTE=<ssh host>   the newest transcript on that machine, copied
#                                  over ssh: for an agent running on a desk the
#                                  phone reaches by ssh, including one in a Warp
#                                  panel. It uses the ssh access the phone
#                                  already has and asks for nothing new.
#   the hook JSON on stdin, then CLAUDE_CODE_SESSION_ID, then the newest local
#   transcript.
#
# Modes, from $CLAUDE_TTS:
#
#   broadcast  (default) SpeakReceiver, no window, works with the screen off
#   pocket     ReadAloudActivity, which opens the floating sheet - needs
#              whatever is calling this to be in the foreground
#   engine     termux-tts-speak, for when the app is not installed. The only
#              mode that strips Markdown here, because it is the only one
#              whose far end will not.
#   print      says what it would read and stops. For working out why nothing
#              came out.
#
set -uo pipefail

PACKAGE=org.pockettts.android
RECEIVER="$PACKAGE/.SpeakReceiver"
ACTIVITY="$PACKAGE/.ui.ReadAloudActivity"

# termux-am defaults to USER_CURRENT (-2), and Android 16 refuses to resolve
# that from an app uid:
#
#   SecurityException: Permission Denial: getIntentSender asks to run as user
#   -2 but is calling from uid u0aNNN; this requires INTERACT_ACROSS_USERS_FULL
#   or android.permission.INTERACT_ACROSS_USERS
#
# An explicit id avoids it. `--user current` maps to the same -2 and fails the
# same way, so it is not an alternative. Override for a work profile or a
# secondary user.
USER_ID=${CLAUDE_TTS_USER:-0}

# Characters per broadcast. SpeakReceiver refuses more than 64 KB of UTF-8, and
# a String extra is parcelled as UTF-16, so this leaves room under both without
# anyone having to think about which one binds. Anything longer is split and
# sent as a queue - which is what the receiver's `append` extra is for, and it
# avoids the file route entirely: a file written by Termux is not a file
# Pocket TTS can open, since the two are different uids with private storage.
LIMIT=${CLAUDE_TTS_LIMIT:-32000}

mode=${CLAUDE_TTS:-broadcast}

die() { printf '%s\n' "$*" >&2; exit 1; }

case "${1:-}" in
  -h|--help)
    sed -n '2,/^set -uo/p' "$0" | sed 's/^# \{0,1\}//; $d'
    exit 0
    ;;
esac

command -v jq >/dev/null 2>&1 || die "speak-last: jq is not installed (pkg install jq / apt install jq)"

# --- find the transcript -----------------------------------------------------

hook_json=""
# A pipe means a hook fed us JSON. A terminal, /dev/null or a closed stdin -
# which is every way a person runs this - means it did not, and reading would
# either block or return nothing useful.
if [ ! -t 0 ] && [ -p /dev/stdin ]; then
  hook_json=$(cat 2>/dev/null)
fi

transcript=${CLAUDE_TTS_TRANSCRIPT:-}
remote_copy=""
if [ -z "$transcript" ] && [ -n "${CLAUDE_TTS_REMOTE:-}" ]; then
  command -v ssh >/dev/null 2>&1 || die "speak-last: ssh is not installed (pkg install openssh)"
  # A temp file, not a pipe into jq: the parse below reads a path, and the same
  # proot /dev/fd trap that broke `< <(...)` would break any substitution here.
  remote_copy=$(mktemp) || die "speak-last: could not create a temp file"
  trap 'rm -f "$remote_copy"' EXIT
  # The newest transcript on the far side, found there. Only the tail is sent:
  # the last reply is at the end, and a long session file is megabytes over a
  # phone connection. 2000 lines holds the last turn of any session measured.
  err=$(ssh -o BatchMode=yes "$CLAUDE_TTS_REMOTE" \
          'f=$(find "$HOME/.claude/projects" -type f -name "*.jsonl" -printf "%T@ %p\n" 2>/dev/null | sort -rn | head -1 | cut -d" " -f2-); [ -n "$f" ] || { echo "no transcript under ~/.claude/projects" >&2; exit 3; }; tail -n 2000 "$f"' \
          2>&1 >"$remote_copy") \
    || die "speak-last: could not fetch a transcript from $CLAUDE_TTS_REMOTE${err:+ - }${err}"
  transcript=$remote_copy
fi
if [ -z "$transcript" ] && [ -n "$hook_json" ]; then
  transcript=$(printf '%s' "$hook_json" | jq -r '.transcript_path // empty' 2>/dev/null)
fi
if [ -z "$transcript" ] && [ -n "${CLAUDE_CODE_SESSION_ID:-}" ]; then
  transcript=$(find "$HOME/.claude/projects" -type f \
                    -name "$CLAUDE_CODE_SESSION_ID.jsonl" 2>/dev/null | head -1)
fi
if [ -z "$transcript" ]; then
  # Last resort, and worth having: the session id is not exported in every
  # context this can be run from, and the newest transcript is nearly always
  # the right one when there is only one session open.
  transcript=$(find "$HOME/.claude/projects" -type f -name '*.jsonl' \
                    -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-)
fi

[ -n "$transcript" ] && [ -f "$transcript" ] || die "speak-last: no transcript found"

# --- pull the last assistant message ----------------------------------------

# Emitted as a JSON string so the whole message survives on one line, then
# decoded. Joining first and taking the last line would cut every reply at its
# first paragraph break.
#
# Sidechain records are subagent turns. They are not what is on screen.
encoded=$(jq -c '
    select(.type == "assistant" and (.isSidechain // false) == false)
    | [ .message.content[]? | select(.type == "text") | .text ]
    | join("\n\n")
    | select(length > 0)
  ' "$transcript" 2>/dev/null | awk 'END{print}')

[ -n "$encoded" ] || die "speak-last: no assistant message in $(basename "$transcript")"

text=$(printf '%s' "$encoded" | jq -r . 2>/dev/null)
[ -n "${text//[[:space:]]/}" ] || die "speak-last: the last reply had no text to read"

# --- say it ------------------------------------------------------------------

# Splits on line boundaries, so a break lands between paragraphs rather than
# inside a sentence wherever it can. A single line longer than the limit is cut
# to length, because the alternative is not sending it.
split() {
  awk -v limit="$LIMIT" '
    function flush() { if (length(buf)) { printf "%s%c", buf, 0; buf = "" } }
    {
      line = $0
      while (length(line) > limit) {
        flush()
        printf "%s%c", substr(line, 1, limit), 0
        line = substr(line, limit + 1)
      }
      if (length(buf) + length(line) + 1 > limit) flush()
      buf = (buf == "" ? line : buf "\n" line)
    }
    END { flush() }
  '
}

# The crude stripper, kept for one caller. Pocket TTS strips far better through
# MarkdownSpeech - headings become sentences, links are read as their text, code
# fences are skipped - so the app modes hand it the Markdown untouched and let
# it do the job properly.
strip_markdown() {
  sed -e 's/```[^`]*```//g' \
      -e 's/`\([^`]*\)`/\1/g' \
      -e 's/^#\{1,6\}[[:space:]]*//' \
      -e 's/\*\*\([^*]*\)\*\*/\1/g' \
      -e 's/\*\([^*]*\)\*/\1/g' \
      -e 's/!\{0,1\}\[\([^]]*\)\]([^)]*)/\1/g' \
      -e 's/^[[:space:]]*[-*+][[:space:]]\{1,\}//' \
      -e 's/^[[:space:]]*>[[:space:]]\{0,\}//'
}

chars=${#text}

case "$mode" in
  print)
    printf '%s\n' "$text"
    printf -- '--- %s characters, %s mode would send %s broadcast(s)\n' \
      "$chars" "$mode" "$(( (chars + LIMIT - 1) / LIMIT ))" >&2
    ;;

  broadcast)
    sent=0
    # A temp file rather than `< <(...)`: process substitution needs /dev/fd,
    # and under proot /dev/fd is bound to the *reader's* /proc/self/fd, so the
    # shell's fd 63 is not there to open. The loop body then never runs at all,
    # and the only sign of it is a "(0 broadcast(s))" in the success message.
    queue=$(mktemp) || die "speak-last: could not create a temp file"
    trap 'rm -f "$queue" ${remote_copy:+"$remote_copy"}' EXIT
    printf '%s' "$text" | split > "$queue"

    # `am` exits 1 for a refused broadcast, but 0 for one addressed to a
    # receiver that does not exist - the output is identical either way, so a
    # missing app cannot be detected from here. Timings -> `utterances read` is
    # what confirms delivery.
    while IFS= read -r -d '' piece; do
      if [ "$sent" -eq 0 ]; then
        err=$(am broadcast --user "$USER_ID" -n "$RECEIVER" \
                --es text "$piece" --ez append false 2>&1 >/dev/null) \
          || die "speak-last: am broadcast failed${err:+ - }${err}"
      else
        # Queued rather than sent as a second read: without this each piece
        # would silence the one before it, and a long reply would play as the
        # last few words of its last paragraph.
        am broadcast --user "$USER_ID" -n "$RECEIVER" \
          --es text "$piece" --ez append true >/dev/null 2>&1
      fi
      sent=$((sent + 1))
    done < "$queue"

    [ "$sent" -gt 0 ] || die "speak-last: nothing was sent - the queue was empty"
    printf 'Reading %s characters in Pocket TTS (%s broadcast(s)).\n' "$chars" "$sent"
    ;;

  pocket)
    [ "$chars" -le "$LIMIT" ] || die "speak-last: $chars characters is too much for one activity start; use CLAUDE_TTS=broadcast"
    am start --user "$USER_ID" -n "$ACTIVITY" \
      -a android.intent.action.PROCESS_TEXT -t text/plain \
      --es android.intent.extra.PROCESS_TEXT "$text" \
      --ez android.intent.extra.PROCESS_TEXT_READONLY true >/dev/null 2>&1 \
      || die "speak-last: am start failed - is Pocket TTS installed?"
    printf 'Reading %s characters in Pocket TTS.\n' "$chars"
    ;;

  engine)
    command -v termux-tts-speak >/dev/null 2>&1 || die "speak-last: termux-tts-speak is not installed"
    printf '%s' "$text" | strip_markdown | termux-tts-speak
    printf 'Read %s characters through the system engine.\n' "$chars"
    ;;

  *)
    die "speak-last: unknown CLAUDE_TTS mode '$mode' (broadcast, pocket, engine, print)"
    ;;
esac
