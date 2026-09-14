#!/usr/bin/env python3
"""
Eval harness for exported STT/TTS models, mapping directly to the PRD's
Section 5 evaluation metrics and Section 7 CI regression gate:

  - STT: Word Error Rate (WER) via `jiwer`, target <15% clean / <25% noisy.
  - TTS: Real-Time Factor (RTF) — synthesis time / audio duration, target <0.3.
  - TTS: Mean Opinion Score (MOS) — logged from a manual listening-test CSV
    (>=5 raters, target >=3.5); this script aggregates scores, it does not
    generate them, since MOS requires real human listeners.

CI usage (Section 7 "Regression / CI" row):
    python tools/eval_harness.py wer --model app/src/main/assets/models/stt/hi.tflite \\
        --eval-set data/eval/hi/  --baseline-wer results/hi_wer_baseline.json
  Exits non-zero if WER regresses by more than 2 points vs. the baseline,
  so a GitHub Actions job can `fail build if WER regresses >2pts`.

This script assumes TFLite models and a held-out eval set already exist; it
does not fabricate audio or transcripts. Wire your own eval-set loader in
`load_eval_set()` once you have real held-out clips per language.
"""
import argparse
import json
import pathlib
import sys
import time
import wave


# ---------------------------------------------------------------------------
# Shared utilities
# ---------------------------------------------------------------------------

def load_eval_set(eval_dir: pathlib.Path):
    """
    Expected layout:
        eval_dir/
          clip_0001.wav
          clip_0001.txt   (reference transcript)
          clip_0002.wav
          clip_0002.txt
          ...
    Returns a list of (wav_path, reference_text) tuples.
    """
    pairs = []
    for wav_path in sorted(eval_dir.glob("*.wav")):
        txt_path = wav_path.with_suffix(".txt")
        if not txt_path.exists():
            print(f"WARNING: no reference transcript for {wav_path}, skipping", file=sys.stderr)
            continue
        pairs.append((wav_path, txt_path.read_text(encoding="utf-8").strip()))
    return pairs


def load_tflite_interpreter(model_path: str):
    """Load a TFLite model; exits with a clear error if tflite_runtime is missing."""
    try:
        import tflite_runtime.interpreter as tflite
    except ImportError:
        try:
            # Fall back to the full TensorFlow package if tflite_runtime is absent.
            import tensorflow as tf
            tflite = tf.lite
        except ImportError:
            sys.exit(
                "Install tensorflow inside virtual environment:\n"
                "  source .venv/bin/activate && pip install tensorflow"
            )
    interp = tflite.Interpreter(model_path=model_path)
    interp.allocate_tensors()
    return interp


def wav_to_pcm_float(wav_path: pathlib.Path):
    """Read a 16-bit mono WAV file and return normalized float32 samples."""
    import struct
    with wave.open(str(wav_path), "rb") as wf:
        n_channels = wf.getnchannels()
        sample_width = wf.getsampwidth()
        frame_rate = wf.getframerate()
        n_frames = wf.getnframes()
        raw = wf.readframes(n_frames)

    if sample_width != 2:
        raise ValueError(f"Expected 16-bit PCM, got {sample_width*8}-bit in {wav_path}")

    samples = struct.unpack(f"<{n_frames * n_channels}h", raw)
    if n_channels > 1:
        # Simple downmix to mono: average channels
        samples = [sum(samples[i:i+n_channels]) / n_channels for i in range(0, len(samples), n_channels)]

    floats = [s / 32768.0 for s in samples]
    return floats, frame_rate


# ---------------------------------------------------------------------------
# WER evaluation  (M4 fix: fully implemented)
# ---------------------------------------------------------------------------

def run_wer(args):
    try:
        import jiwer
    except ImportError:
        sys.exit("Install jiwer inside virtual environment: source .venv/bin/activate && pip install jiwer")
    import numpy as np

    eval_dir = pathlib.Path(args.eval_set)
    pairs = load_eval_set(eval_dir)
    if not pairs:
        sys.exit(f"No eval clips found under {eval_dir} (expected .wav + matching .txt files)")

    print(f"Loading model: {args.model}")
    interp = load_tflite_interpreter(args.model)
    input_details = interp.get_input_details()
    output_details = interp.get_output_details()

    references = []
    hypotheses = []
    errors = 0

    for wav_path, ref_text in pairs:
        try:
            pcm, sr = wav_to_pcm_float(wav_path)
            # Resize the input tensor to match this clip's length (dynamic shape).
            input_data = np.array([pcm], dtype=np.float32)
            interp.resize_input_tensor(input_details[0]["index"], input_data.shape)
            interp.allocate_tensors()
            interp.set_tensor(input_details[0]["index"], input_data)
            interp.invoke()
            token_ids = interp.get_tensor(output_details[0]["index"])[0]

            # CTC greedy decode: collapse repeats, skip blank (id=0 by convention).
            tokens = []
            prev = -1
            for tid in token_ids:
                if tid != prev:
                    prev = tid
                    if tid != 0:
                        tokens.append(tid)

            # Attempt to load vocab file alongside model for ID -> character mapping.
            model_path = pathlib.Path(args.model)
            vocab_path = model_path.with_suffix(".vocab")
            if vocab_path.exists():
                vocab = vocab_path.read_text(encoding="utf-8").splitlines()
                hyp = "".join(vocab[t] for t in tokens if t < len(vocab)).strip()
            else:
                # Fallback: treat ids as Unicode codepoints (matches the app's stub).
                hyp = "".join(chr(t) for t in tokens if 0 < t < 0x110000).strip()

            references.append(ref_text)
            hypotheses.append(hyp)
        except Exception as exc:
            print(f"ERROR on {wav_path.name}: {exc}", file=sys.stderr)
            errors += 1

    if not references:
        sys.exit("All clips failed inference — check model shape and eval set.")

    wer = jiwer.wer(references, hypotheses) * 100  # percentage
    print(f"\n=== WER Results ===")
    print(f"Clips evaluated : {len(references)}  (errors: {errors})")
    print(f"WER             : {wer:.2f}%")
    print(f"Target (clean)  : <15%    {'✓ PASS' if wer < 15 else '✗ FAIL'}")
    print(f"Target (noisy)  : <25%    {'✓ PASS' if wer < 25 else '✗ FAIL'}")

    result = {"wer": round(wer, 4), "n_clips": len(references), "errors": errors}

    if args.baseline_wer:
        baseline_path = pathlib.Path(args.baseline_wer)
        if baseline_path.exists():
            baseline = json.loads(baseline_path.read_text())["wer"]
            regression = wer - baseline
            print(f"\nBaseline WER    : {baseline:.2f}%")
            print(f"Regression      : {regression:+.2f}pp  (threshold: +2pp)")
            if regression > 2.0:
                print("✗ CI GATE FAILED: WER regressed by more than 2 points", file=sys.stderr)
                sys.exit(1)
            else:
                print("✓ CI GATE PASSED")
        else:
            # Write this run as the new baseline.
            baseline_path.parent.mkdir(parents=True, exist_ok=True)
            baseline_path.write_text(json.dumps(result, indent=2))
            print(f"No baseline found — wrote current results to {baseline_path}")

    return result


# ---------------------------------------------------------------------------
# RTF evaluation  (M5 fix: fully implemented)
# ---------------------------------------------------------------------------

def run_rtf(args):
    """
    RTF = synthesis wall-clock time / output audio duration.
    Target <0.3 (Section 5).

    Reads sentences from --text-file (one sentence per line) and synthesizes
    each with the TFLite TTS acoustic+vocoder pair. The vocoder model is
    expected to reside alongside the acoustic model:
      <model>              -> acoustic (text tokens -> mel spectrogram)
      <model>_vocoder.tflite  -> vocoder (mel -> waveform)
    """
    import numpy as np

    model_path = pathlib.Path(args.model)
    text_file = pathlib.Path(args.text_file)
    if not text_file.exists():
        sys.exit(f"Text file not found: {text_file}")

    sentences = [l.strip() for l in text_file.read_text(encoding="utf-8").splitlines() if l.strip()]
    if not sentences:
        sys.exit("No sentences in text file.")

    # Derive vocoder path: replace _acoustic suffix if present.
    vocoder_path = str(model_path).replace("_acoustic.tflite", "_vocoder.tflite")
    if not pathlib.Path(vocoder_path).exists():
        sys.exit(f"Vocoder model not found at {vocoder_path}\n"
                 "Expected: <acoustic_model_path> with _acoustic.tflite replaced by _vocoder.tflite")

    acoustic_interp = load_tflite_interpreter(str(model_path))
    vocoder_interp = load_tflite_interpreter(vocoder_path)

    ac_in = acoustic_interp.get_input_details()
    ac_out = acoustic_interp.get_output_details()
    vo_in = vocoder_interp.get_input_details()
    vo_out = vocoder_interp.get_output_details()

    HOP_SIZE = 256
    SAMPLE_RATE = 22_050

    rtf_values = []
    print(f"Synthesizing {len(sentences)} sentences …\n")
    for sentence in sentences:
        # Character-level tokenizer (matches the app's stub in TfLiteTtsEngine.kt).
        token_ids = np.array([[ord(c) for c in sentence]], dtype=np.int32)

        t0 = time.perf_counter()

        # Stage 1: acoustic (text -> mel)
        acoustic_interp.resize_input_tensor(ac_in[0]["index"], token_ids.shape)
        acoustic_interp.allocate_tensors()
        acoustic_interp.set_tensor(ac_in[0]["index"], token_ids)
        acoustic_interp.invoke()
        mel = acoustic_interp.get_tensor(ac_out[0]["index"])

        # Stage 2: vocoder (mel -> waveform)
        vocoder_interp.resize_input_tensor(vo_in[0]["index"], mel.shape)
        vocoder_interp.allocate_tensors()
        vocoder_interp.set_tensor(vo_in[0]["index"], mel)
        vocoder_interp.invoke()
        waveform = vocoder_interp.get_tensor(vo_out[0]["index"]).flatten()

        synth_time = time.perf_counter() - t0
        audio_duration = len(waveform) / SAMPLE_RATE
        rtf = synth_time / max(audio_duration, 1e-9)
        rtf_values.append(rtf)
        print(f"  [{sentence[:40]:<40}]  synth={synth_time*1000:.0f}ms  audio={audio_duration:.2f}s  RTF={rtf:.3f}")

    mean_rtf = sum(rtf_values) / len(rtf_values)
    p95_rtf = sorted(rtf_values)[int(len(rtf_values) * 0.95)]
    print(f"\n=== RTF Results ===")
    print(f"Sentences  : {len(rtf_values)}")
    print(f"Mean RTF   : {mean_rtf:.3f}  (target <0.3)")
    print(f"p95  RTF   : {p95_rtf:.3f}")
    print(f"Result     : {'✓ PASS' if mean_rtf < 0.3 else '✗ FAIL'}")

    if mean_rtf >= 0.3:
        sys.exit(1)


# ---------------------------------------------------------------------------
# MOS aggregation (unchanged — requires human rater CSVs)
# ---------------------------------------------------------------------------

def run_mos_aggregate(args):
    """Aggregates a manual listening-test CSV: columns = rater_id, clip_id, score(1-5)."""
    import csv
    scores = []
    with open(args.csv, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            scores.append(float(row["score"]))
    if not scores:
        sys.exit("No scores found in CSV")
    mos = sum(scores) / len(scores)
    print(f"MOS = {mos:.2f} across {len(scores)} ratings (target >= 3.5 per Section 5)")
    if mos < 3.5:
        print("BELOW TARGET", file=sys.stderr)
        sys.exit(1)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)

    wer_p = sub.add_parser("wer", help="Compute STT Word Error Rate against a held-out eval set")
    wer_p.add_argument("--model", required=True, help="Path to .tflite STT model")
    wer_p.add_argument("--eval-set", required=True, help="Directory of .wav + .txt pairs")
    wer_p.add_argument("--baseline-wer", required=False,
                       help="JSON file with {\"wer\": <float>}; CI fails if new WER > baseline + 2pp")
    wer_p.set_defaults(func=run_wer)

    rtf_p = sub.add_parser("rtf", help="Compute TTS Real-Time Factor")
    rtf_p.add_argument("--model", required=True, help="Path to _acoustic.tflite model")
    rtf_p.add_argument("--text-file", required=True, help="File with one sentence per line")
    rtf_p.set_defaults(func=run_rtf)

    mos_p = sub.add_parser("mos", help="Aggregate a manual MOS listening-test CSV")
    mos_p.add_argument("--csv", required=True, help="CSV with columns: rater_id, clip_id, score")
    mos_p.set_defaults(func=run_mos_aggregate)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
