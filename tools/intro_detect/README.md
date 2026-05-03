# intro_detect

Offline tool that finds the intro and outro of every episode in a season and
annotates the corresponding `media/shows/season_*.json` with millisecond
timestamps. Inspired by the [intro-skipper](https://github.com/intro-skipper/intro-skipper)
Jellyfin plugin.

## Install

```bash
brew install ffmpeg chromaprint   # ffprobe ships with ffmpeg
```

The script is pure Python 3 (stdlib only). No `pip install` step.

## Usage

```bash
# Detect intros for season 1, write to media/shows/season_1.json
python tools/intro_detect/detect.py --season media/shows/season_1.json

# Detect outros (end credits)
python tools/intro_detect/detect.py --season media/shows/season_1.json --outro

# Inspect without writing
python tools/intro_detect/detect.py --season media/shows/season_1.json --dry-run
```

A `.bak` of the season JSON is created on first write. Fingerprints are cached
under `tools/intro_detect/.cache/` so re-running is fast.

The annotation adds four optional fields per episode (any subset can be
present):

```json
{
  "id": "Show_S01E01",
  "introStartMs": 21000,
  "introEndMs": 51000,
  "outroStartMs": 1320000,
  "outroEndMs": 1380000
}
```

The Go backend (`backend/models/models.go::EpisodeInfo`) round-trips these
fields through `/api/show/info`.

## How it works

1. `ffmpeg` slices the first 600 s (intro) or last 300 s (outro) of each
   episode's audio at 11025 Hz mono s16le and pipes it into
   `fpcalc -raw`, which emits one 32-bit Chromaprint hash per ~123.8 ms.
2. For every pair of episodes in the season, the script builds a histogram of
   `(j - i)` offsets from exact-match seed frames. The dominant bucket — if it
   has at least `MIN_OFFSET_VOTES` seeds — is taken as the alignment.
3. At that alignment it scans for the longest contiguous run where the Hamming
   distance between aligned 32-bit hashes stays below `HAMMING_MAX`.
4. Each episode collects candidate windows from every pair it appears in. The
   median start/end across pairs (requiring at least `MIN_PAIR_AGREEMENT`
   pairs) is the final annotation.

## Tunables

Constants at the top of `detect.py`:

| Constant | Default | Effect |
|---|---|---|
| `INTRO_HEAD_SECONDS` | 600 | Window from the start of the file searched for the intro |
| `OUTRO_TAIL_SECONDS` | 300 | Window from the end searched for the outro |
| `HAMMING_MAX` | 8 | Max bit differences per 32-bit hash to count as a match |
| `MIN_RUN_FRAMES` | 80 | Min contiguous matching frames (~10 s) to accept |
| `MIN_OFFSET_VOTES` | 30 | Seed-frame agreement before locking an alignment |
| `MIN_PAIR_AGREEMENT` | 2 | Pairs that must agree before an episode is annotated |

If a show has many cold opens (like *The Office*), where the actual theme
starts at variable times, the intro window will reflect the theme itself, not
the cold open — that's the desired behaviour.

## Limitations

- Episodes whose intro/outro genuinely don't match the rest of the season
  (extended cuts, unique cold opens that bleed into the theme, missing
  credits) won't be annotated. The backend's `omitempty` ensures the API
  simply omits the field for those.
- Recap segments ("Previously on…") are not detected — they aren't shared
  across episodes.
