"""Corpora. TalkBack strings reconstructed from warp .fork/runs/android-te-2026-10-03
(talk.sh plus SpokenScreen.announcement; each length matches the logged char count).
The 51-char command line and the 13-char prompt were not recoverable and are left out."""

TALKBACK = [
    ("tb01_three", "the first test: three short lines\napples\npears"),  # 46
    ("tb02_seq", "15 new lines, ending:\n18\n19\n20"),  # 30
    ("tb03_slow1", "slow line one"),  # 13
    ("tb04_slow23", "slow line two\nslow line three"),  # 29
    ("tb05_slow4", "slow line four"),  # 14
    ("tb06_slow5", "slow line five"),  # 14
    ("tb07_fast_a", "10 new lines, ending:\nfast line 8\nfast line 9\nfast line 10"),  # 58
    ("tb08_fast_b", "10 new lines, ending:\nfast line 18\nfast line 19\nfast line 20"),  # 60
    ("tb09_end", "that is the end of the test"),  # 27
]

# Ordinary technical prose, written for this run (not the app's scratchpad).
# As MarkdownSpeech would hand it to TextChunker: one line per paragraph.
READALOUD = [
    (
        "ra01",
        "\n\n".join(
            [
                "The engine loads the model once and keeps it in memory for the life of the process. "
                "Loading takes a few seconds, so the reader, the scratchpad and the system service all share the same copy. "
                "Synthesis is serialised behind a lock, because the decoder keeps state that two generations would corrupt.",
                "Text is split into chunks before it reaches the model. A chunk ends at a sentence boundary where one is available, "
                "at a comma or a semicolon when a sentence runs long, and in the middle of a phrase only as a last resort. "
                "Each chunk is generated while the previous one plays, so the listener hears the first sentence quickly and the gaps "
                "between chunks stay short. The size of a chunk is a trade between latency and continuity, and neither extreme works well.",
                "When the voice is steady, every generation draws its speaker from the same seed. "
                "That keeps the timbre from drifting between sentences. It does not keep the pacing the same, "
                "and a sentence that follows a long one can sound hurried.",
                "If a chunk fails, the reader stops at the end of the audio it already has. Nothing is retried. "
                "The error is shown in the notification, and the next tap starts again from the chunk that failed.",
            ]
        ),
    )
]
