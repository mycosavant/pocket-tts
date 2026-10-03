"""Same chunks through the reference PyTorch pocket-tts (PyPI 3.3.0), as a pacing/accuracy baseline.
usage: ref_synth.py OUTDIR SEED"""
import json, pathlib, sys
import numpy as np, soundfile as sf, torch
from pocket_tts import TTSModel
from chunker import chunk
from corpus import TALKBACK, READALOUD

out = pathlib.Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
seed = int(sys.argv[2])
torch.set_num_threads(2)
model = TTSModel.load_model(language="english", temp=0.3, sampler_decode_steps=1)
state = model.get_state_for_audio_prompt("alba")  # catalog embedding; cloning weights are gated
SR = model.sample_rate
meta = []
jobs = [(n, t, "text") for n, t in TALKBACK] + [(n, t, "speech") for n, t in READALOUD]
for name, text, field in jobs:
    for ci, (ctext, cspeech, pause) in enumerate(chunk(text)):
        src = ctext if field == "text" else cspeech
        torch.manual_seed(seed)
        a = model.generate_audio(state, src).numpy().astype(np.float32)
        sf.write(out / f"{name}.c{ci:02d}.wav", a, SR)
        meta.append({"item": name, "chunk": ci, "text": src, "pause": pause, "dur": round(len(a) / SR, 3)})
(out / "meta.json").write_text(json.dumps({"mode": "reference-pytorch", "seed": seed, "chunks": meta}, indent=1))
