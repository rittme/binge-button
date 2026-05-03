#!/usr/bin/env python3
"""Detect intro/outro segments in a season's episodes via Chromaprint fingerprints.

Approach mirrors the intro-skipper Jellyfin plugin:
  1. ffmpeg slices the head (or tail) of each episode's audio.
  2. fpcalc -raw produces a 32-bit-per-frame Chromaprint fingerprint.
  3. For every pair of episodes, find the dominant alignment offset by
     exact-matching fingerprint frames, then extend into the longest contiguous
     run where Hamming distance between aligned frames stays below a threshold.
  4. Per-episode result = median of agreed windows across pairs.
  5. Write back introStartMs/introEndMs (or outroStartMs/outroEndMs) into the
     season JSON in place.

Usage:
  python detect.py --season ../../media/shows/season_1.json
  python detect.py --season ../../media/shows/season_1.json --outro
  python detect.py --season ../../media/shows/season_1.json --dry-run

Requires `ffmpeg`, `ffprobe`, and `fpcalc` (Chromaprint) on PATH.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from collections import Counter, defaultdict
from pathlib import Path
from statistics import median
from typing import Optional

# Chromaprint emits one 32-bit hash every 4096 samples at 11025 Hz mono.
FRAME_PERIOD_MS = 4096 * 1000 / 11025  # ~371.5 ms... actually ~123.8

# fpcalc default algorithm produces ~8.06 frames/sec — measured: 4096/11025 * 3
# ≈ 0.1238 s/frame. We compute from DURATION when possible.
DEFAULT_FRAME_MS = 4096.0 / 11025.0 * 1000.0  # ≈ 371.5 ms (single-channel base)

INTRO_HEAD_SECONDS = 600  # search first 10 minutes
OUTRO_TAIL_SECONDS = 300  # search last 5 minutes

# Tunables
HAMMING_MAX = 8           # bits/32 allowed per matched frame
MIN_RUN_FRAMES = 80       # min run span to accept (~10s)
MAX_GAP_FRAMES = 16       # tolerate up to ~2s of mismatched frames inside a run
MIN_OFFSET_VOTES = 10     # exact-match seeds required to consider an alignment
TOP_K_OFFSETS = 20        # candidate offsets to evaluate per episode pair
MIN_PAIR_AGREEMENT = 2    # pairs that must agree before writing a result

EP_ID_RE = re.compile(r"S(\d{1,2})E(\d{1,2})", re.IGNORECASE)


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def require_tool(name: str) -> None:
    if shutil.which(name) is None:
        log(f"error: required tool '{name}' not found on PATH")
        sys.exit(2)


def ffprobe_duration(path: Path) -> float:
    out = subprocess.check_output(
        [
            "ffprobe", "-v", "error", "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1", str(path),
        ],
        text=True,
    ).strip()
    return float(out)


def episode_video_path(seasons_dir: Path, episode_id: str) -> Optional[Path]:
    m = EP_ID_RE.search(episode_id)
    if not m:
        return None
    season_n = int(m.group(1))
    episode_n = int(m.group(2))
    season_dir = seasons_dir / f"season-{season_n:02d}"
    if not season_dir.is_dir():
        return None
    for ext in ("mp4", "mkv", "avi", "mov", "wmv"):
        candidate = season_dir / f"episode-{episode_n:02d}.{ext}"
        if candidate.is_file():
            return candidate
    return None


def fingerprint(video: Path, *, mode: str, cache_dir: Path,
                duration: float) -> tuple[list[int], float]:
    """Return (frames, frame_ms). For mode=='outro' frames cover the tail window
    starting at max(0, duration - OUTRO_TAIL_SECONDS)."""
    cache_dir.mkdir(parents=True, exist_ok=True)
    # Include the parent dir (season-NN) so season-01/episode-01 doesn't
    # collide with season-08/episode-01.
    cache_key = f"{video.parent.name}__{video.stem}.{mode}.fp"
    cache_path = cache_dir / cache_key

    if cache_path.exists():
        data = json.loads(cache_path.read_text())
        return data["frames"], data["frame_ms"]

    if mode == "intro":
        ss = 0.0
        t = min(INTRO_HEAD_SECONDS, duration)
    else:
        t = min(OUTRO_TAIL_SECONDS, duration)
        ss = max(0.0, duration - t)

    # ffmpeg slice -> temp wav, then fpcalc on the file (reliable across builds).
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        tmp_path = Path(tmp.name)
    try:
        ff = subprocess.run(
            [
                "ffmpeg", "-v", "error", "-nostdin", "-y",
                "-ss", f"{ss:.3f}", "-t", f"{t:.3f}",
                "-i", str(video),
                "-vn", "-ac", "1", "-ar", "11025", str(tmp_path),
            ],
            capture_output=True, text=True,
        )
        if ff.returncode != 0:
            log(f"ffmpeg failed for {video} ({mode}): {ff.stderr}")
            return [], DEFAULT_FRAME_MS

        fp = subprocess.run(
            ["fpcalc", "-raw", "-length", f"{int(t)+5}", str(tmp_path)],
            capture_output=True, text=True,
        )
        if fp.returncode != 0:
            log(f"fpcalc failed for {video} ({mode}): {fp.stderr}")
            return [], DEFAULT_FRAME_MS
    finally:
        tmp_path.unlink(missing_ok=True)

    fp_duration = 0.0
    raw = ""
    for line in fp.stdout.splitlines():
        if line.startswith("DURATION="):
            fp_duration = float(line.split("=", 1)[1])
        elif line.startswith("FINGERPRINT="):
            raw = line.split("=", 1)[1]
    if not raw:
        return [], DEFAULT_FRAME_MS

    # fpcalc -raw emits signed 32-bit decimals; cast to unsigned for popcount.
    frames = [int(x) & 0xFFFFFFFF for x in raw.split(",") if x]
    # Prefer fpcalc's reported DURATION; fall back to the slice length we asked
    # ffmpeg for (some fpcalc builds report 0 here).
    if fp_duration > 0:
        frame_ms = fp_duration * 1000.0 / len(frames)
    else:
        frame_ms = t * 1000.0 / len(frames)

    cache_path.write_text(json.dumps({"frames": frames, "frame_ms": frame_ms}))
    return frames, frame_ms


def popcount(x: int) -> int:
    return bin(x).count("1")


def candidate_offsets(a: list[int], b: list[int]) -> list[int]:
    """Return up to TOP_K_OFFSETS alignment offsets ranked by exact-match seed
    votes, filtered to those with at least MIN_OFFSET_VOTES seeds."""
    positions: dict[int, list[int]] = defaultdict(list)
    for j, h in enumerate(b):
        positions[h].append(j)

    votes: Counter[int] = Counter()
    for i, h in enumerate(a):
        for j in positions.get(h, ()):
            votes[j - i] += 1
    return [off for off, count in votes.most_common(TOP_K_OFFSETS)
            if count >= MIN_OFFSET_VOTES]


def longest_run(a: list[int], b: list[int], offset: int) -> Optional[tuple[int, int]]:
    """Walk a/b at the given offset; return (start_in_a, span) of the longest
    matching segment, where 'span' = first-match to last-match inclusive,
    tolerating up to MAX_GAP_FRAMES consecutive non-matches inside the segment.
    Brief mid-theme variations (e.g. cast voice-overs) shouldn't truncate the run."""
    i_lo = max(0, -offset)
    i_hi = min(len(a), len(b) - offset)
    best_start = -1
    best_span = 0
    cur_start = -1
    cur_end = -1
    gap = 0
    for i in range(i_lo, i_hi):
        match = popcount(a[i] ^ b[i + offset]) <= HAMMING_MAX
        if match:
            if cur_start == -1:
                cur_start = i
            cur_end = i
            gap = 0
            span = cur_end - cur_start + 1
            if span > best_span:
                best_span = span
                best_start = cur_start
        elif cur_start != -1:
            gap += 1
            if gap > MAX_GAP_FRAMES:
                cur_start = -1
                cur_end = -1
                gap = 0
    if best_span < MIN_RUN_FRAMES:
        return None
    return best_start, best_span


def best_run(a: list[int], b: list[int]) -> Optional[tuple[int, int, int]]:
    """Return (offset, start_in_a, length) — the longest contiguous run found
    across all candidate alignment offsets. None if nothing meets MIN_RUN_FRAMES.

    Picking by run length (not seed count) lets the theme song beat a short
    studio-logo block that happens to attract more exact-match seeds at offset
    ≈ 0."""
    best: Optional[tuple[int, int, int]] = None
    for offset in candidate_offsets(a, b):
        run = longest_run(a, b, offset)
        if run is None:
            continue
        start, length = run
        if best is None or length > best[2]:
            best = (offset, start, length)
    return best


def detect_segments(season_path: Path, mode: str, cache_dir: Path,
                    seasons_dir: Path) -> dict[str, tuple[int, int]]:
    """Return {episode_id: (start_ms, end_ms)} relative to the full episode."""
    episodes = json.loads(season_path.read_text())
    fps: dict[str, tuple[list[int], float, float, float]] = {}  # id -> (frames, frame_ms, slice_start_ms, duration_ms)

    for ep in episodes:
        ep_id = ep["id"]
        video = episode_video_path(seasons_dir, ep_id)
        if video is None:
            log(f"  skip {ep_id}: video not found")
            continue
        duration = ffprobe_duration(video)
        frames, frame_ms = fingerprint(video, mode=mode, cache_dir=cache_dir,
                                       duration=duration)
        if not frames:
            log(f"  skip {ep_id}: empty fingerprint")
            continue
        slice_start_ms = 0.0 if mode == "intro" else max(
            0.0, (duration - OUTRO_TAIL_SECONDS) * 1000.0)
        fps[ep_id] = (frames, frame_ms, slice_start_ms, duration * 1000.0)
        log(f"  fp {ep_id}: {len(frames)} frames @ {frame_ms:.1f} ms")

    # Pairwise: collect candidate (start_ms, end_ms) windows per episode.
    candidates: dict[str, list[tuple[float, float]]] = defaultdict(list)
    ids = list(fps.keys())
    for i in range(len(ids)):
        a_id = ids[i]
        a_frames, a_fms, a_off, _ = fps[a_id]
        for j in range(i + 1, len(ids)):
            b_id = ids[j]
            b_frames, b_fms, b_off, _ = fps[b_id]
            result = best_run(a_frames, b_frames)
            if result is None:
                continue
            offset, start_a, length = result
            start_b = start_a + offset
            a_start_ms = a_off + start_a * a_fms
            a_end_ms = a_off + (start_a + length) * a_fms
            b_start_ms = b_off + start_b * b_fms
            b_end_ms = b_off + (start_b + length) * b_fms
            candidates[a_id].append((a_start_ms, a_end_ms))
            candidates[b_id].append((b_start_ms, b_end_ms))

    results: dict[str, tuple[int, int]] = {}
    for ep_id, wins in candidates.items():
        if len(wins) < MIN_PAIR_AGREEMENT:
            log(f"  {ep_id}: only {len(wins)} pair(s) agreed, skipping")
            continue
        starts = [w[0] for w in wins]
        ends = [w[1] for w in wins]
        results[ep_id] = (int(median(starts)), int(median(ends)))
    return results


def write_back(season_path: Path, mode: str,
               results: dict[str, tuple[int, int]]) -> None:
    episodes = json.loads(season_path.read_text())
    start_key = "introStartMs" if mode == "intro" else "outroStartMs"
    end_key = "introEndMs" if mode == "intro" else "outroEndMs"

    backup = season_path.with_suffix(season_path.suffix + ".bak")
    if not backup.exists():
        backup.write_text(season_path.read_text())

    for ep in episodes:
        if ep["id"] in results:
            s, e = results[ep["id"]]
            ep[start_key] = s
            ep[end_key] = e

    season_path.write_text(json.dumps(episodes, indent=2, ensure_ascii=False) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--season", type=Path, required=True,
                        help="Path to season_*.json")
    parser.add_argument("--outro", action="store_true",
                        help="Detect outro/credits instead of intro")
    parser.add_argument("--dry-run", action="store_true",
                        help="Print results, don't write JSON")
    parser.add_argument("--cache-dir", type=Path,
                        default=Path(__file__).parent / ".cache")
    args = parser.parse_args()

    for tool in ("ffmpeg", "ffprobe", "fpcalc"):
        require_tool(tool)

    season_path: Path = args.season.resolve()
    if not season_path.is_file():
        log(f"error: {season_path} not found")
        return 2
    seasons_dir = season_path.parent

    mode = "outro" if args.outro else "intro"
    log(f"detecting {mode} for {season_path.name}")

    results = detect_segments(season_path, mode, args.cache_dir, seasons_dir)

    for ep_id, (s, e) in sorted(results.items()):
        log(f"  {ep_id}: {mode} {s/1000:.1f}s -> {e/1000:.1f}s "
            f"({(e-s)/1000:.1f}s)")

    if args.dry_run:
        log("dry-run: not writing")
        return 0

    if not results:
        log("no detections; nothing to write")
        return 0

    write_back(season_path, mode, results)
    log(f"wrote {len(results)} {mode} window(s) to {season_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
