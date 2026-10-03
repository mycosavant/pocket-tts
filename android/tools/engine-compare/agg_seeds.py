"""Aggregate seeds/*/judge.json by mode and steps, across seeds."""
import json, glob, re, collections
import numpy as np

groups = collections.defaultdict(list)
for f in sorted(glob.glob("seeds/*/judge.json")):
    cell = f.split("/")[1]
    key = re.sub(r"_s\d+$", "", cell)
    for r in json.load(open(f)):
        r["seed"] = cell.rsplit("_s", 1)[1]
        groups[key].append(r)

print("| mode, steps | corpus | WER (5 seeds) | worst seed WER | items with WER>=0.3 | wps | boundary quiet median s | boundaries <100 ms |")
print("|---|---|---|---|---|---|---|---|")
order = ["whole_st1", "sherpa_st1", "whole_st5", "sherpa_st5", "ref_st1", "refsherpa_st1", "lines_st1",
         "linessherpa_st1", "perunit_st1", "perunit@0.15_st1", "refpad_st1", "reference_st1", "fp32-whole_st1", "fp32-ref_st1", "pocketspeak_st1", "phone-pocketspeak_st1"]
for key in order:
    rows = groups.get(key)
    if not rows:
        continue
    for corpus, pre in (("TalkBack", "tb"), ("ReadAloud", "ra")):
        rs = [r for r in rows if r["item"].startswith(pre)]
        n = sum(r["n_exp"] for r in rs)
        err = sum(r["sub"] + r["del"] + r["ins"] for r in rs)
        per_seed = collections.defaultdict(lambda: [0, 0])
        for r in rs:
            per_seed[r["seed"]][0] += r["sub"] + r["del"] + r["ins"]
            per_seed[r["seed"]][1] += r["n_exp"]
        worst = max(e / m for e, m in per_seed.values())
        bad = sum(r["wer"] >= 0.3 for r in rs)
        wps = n / sum(r["speech_s"] for r in rs)
        gaps = [g for r in rs for g in r["gaps"]]
        print(f"| {key} | {corpus} | {err / n:.1%} | {worst:.1%} | {bad}/{len(rs)} | {wps:.2f} | "
              f"{np.median(gaps):.2f} | {sum(g < 0.1 for g in gaps)}/{len(gaps)} |")
