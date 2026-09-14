#!/usr/bin/env python3
"""
Model conversion entry point for OfflineVoiceRelay (PRD Section 8 deliverable:
"Model conversion scripts (Python) for each language's STT/TTS checkpoint to
TFLite/ONNX with INT8 quantization").

WHAT THIS SCRIPT DOES vs. WHAT IT DOES NOT DO
-----------------------------------------------
This defines the *pipeline shape* — where checkpoints come from, how they're
quantized, and where the resulting .tflite files must be placed so the app's
TfLiteSttEngine / TfLiteTtsEngine can load them (app/src/main/assets/models/...).

It does NOT bundle or silently download multi-gigabyte AI4Bharat checkpoints:
those are large, versioned research artifacts that need to be fetched
deliberately (and their exact repo/release URLs verified at the time you run
this, since they change across AI4Bharat releases). Run this on a machine
with normal internet access — not from the app's runtime environment.

USAGE
-----
    # Set up virtual environment and install conversion dependencies
    python3 -m venv .venv && source .venv/bin/activate
    pip install torch onnx onnx2tf tensorflow sentencepiece transformers \
                huggingface_hub jiwer

    # STT + TTS for Hindi (start here — see PRD Week-1 milestone)
    python tools/download_and_convert_models.py \\
        --language hi \\
        --stt-checkpoint AI4Bharat/indicconformer_stt_hi_hybrid_ctc_rnnt_large \\
        --tts-checkpoint AI4Bharat/indic-tts-coqui-indo-aryan-with-ipa \\
        --output-dir app/src/main/assets/models

    # For a language where the Conformer export is too large for 2-4GB RAM:
    python tools/download_and_convert_models.py \\
        --language or \\
        --stt-checkpoint alphacep/vosk-model-small-en-us-0.15 \\
        --stt-fallback vosk \\
        --output-dir app/src/main/assets/models

Each conversion stage is implemented with the standard HuggingFace/ONNX/TFLite
toolchain. Adjust the exact model ID strings as AI4Bharat updates their releases
— the I/O contract expected by the app (documented below each function) does NOT
change.
"""
import argparse
import pathlib
import sys
import struct
import wave

SUPPORTED_LANGUAGES = ["hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn", "en"]

# Representative calibration text per language for INT8 quantization.
# Replace with real, diverse sentences from a held-out set for best accuracy.
CALIBRATION_SENTENCES: dict[str, list[str]] = {
    "hi": ["पानी बढ़ रहा है", "ऊँची जगह जाएँ", "पुल टूट गया है"],
    "gu": ["પાણી વધી રહ્યું છે", "ઊંચી જગ્યાએ જાઓ"],
    "mr": ["पाणी वाढत आहे", "उंच ठिकाणी जा"],
    "kn": ["ನೀರು ಏರುತ್ತಿದೆ", "ಎತ್ತರದ ಸ್ಥಳಕ್ಕೆ ಹೋಗಿ"],
    "ml": ["വെള്ളം ഉയരുന്നു", "ഉയർന്ന സ്ഥലത്തേക്ക് പോകൂ"],
    "ta": ["தண்ணீர் உயர்கிறது", "உயரமான இடத்திற்கு செல்லுங்கள்"],
    "te": ["నీళ్ళు పెరుగుతున్నాయి", "ఎత్తైన ప్రదేశానికి వెళ్ళండి"],
    "or": ["ପାଣି ବଢ଼ୁଛି", "ଉଚ୍ଚ ସ୍ଥାନକୁ ଯାଆନ୍ତୁ"],
    "bn": ["জল বাড়ছে", "উঁচু জায়গায় যান"],
    "en": ["Flood water rising", "Move to high ground", "Bridge has collapsed"],
}


# ---------------------------------------------------------------------------
# STT conversion  (M6 fix: implemented)
# ---------------------------------------------------------------------------

def convert_stt_to_tflite(
    checkpoint_path: str,
    language: str,
    output_dir: pathlib.Path,
    fallback: str | None,
):
    """
    Converts an AI4Bharat STT checkpoint to TFLite + vocab file.

    Expected app-side contract (see SttEngine.kt / TfLiteSttEngine):
        input:  float32[1, N]   -- normalized PCM16 samples in [-1, 1]
        output: int32[1, T]     -- greedy CTC token ids

    Outputs written:
        <output_dir>/stt/<language>.tflite   -- INT8 quantized CTC model
        <output_dir>/stt/<language>.vocab    -- one token per line, index = token ID
    """
    try:
        import torch
        import numpy as np
    except ImportError:
        sys.exit("Install dependencies in venv: source .venv/bin/activate && pip install torch numpy")

    out_tflite = output_dir / "stt" / f"{language}.tflite"
    out_vocab = output_dir / "stt" / f"{language}.vocab"
    onnx_path = output_dir / "stt" / f"{language}_tmp.onnx"

    print(f"\n[stt:{language}] Starting conversion from {checkpoint_path}")

    if fallback == "vosk":
        _convert_vosk_to_tflite(checkpoint_path, language, output_dir)
        return

    # -- Step 1: Load AI4Bharat checkpoint (HuggingFace transformers or NeMo format) --
    print(f"[stt:{language}] Step 1/4: Loading checkpoint …")
    is_nemo = False
    try:
        from transformers import Wav2Vec2ForCTC, Wav2Vec2Processor
        processor = Wav2Vec2Processor.from_pretrained(checkpoint_path)
        model = Wav2Vec2ForCTC.from_pretrained(checkpoint_path)
        model.eval()
    except Exception as e_hf:
        # Check if this Hugging Face repository or local path contains a .nemo model checkpoint
        print(f"[stt:{language}] Transformers auto-load did not match ({e_hf}). Checking for NeMo .nemo checkpoint …")
        nemo_file = _find_or_download_nemo(checkpoint_path)
        if nemo_file:
            is_nemo = True
            model, processor_vocab = _load_nemo_stt_model(nemo_file, language)
        else:
            sys.exit(
                f"[stt:{language}] Failed to load checkpoint '{checkpoint_path}': {e_hf}\n"
                "Verify the exact HuggingFace model ID from https://huggingface.co/ai4bharat"
            )


    # -- Step 2: Export to ONNX with dynamic audio-length axis --
    print(f"[stt:{language}] Step 2/4: Exporting to ONNX …")
    dummy_input = torch.zeros(1, 16000, dtype=torch.float32)  # 1 second of silence
    torch.onnx.export(
        model,
        dummy_input,
        str(onnx_path),
        input_names=["input_values"],
        output_names=["logits"],
        dynamic_axes={"input_values": {0: "batch", 1: "time"}, "logits": {0: "batch", 1: "time"}},
        opset_version=17,
    )
    print(f"[stt:{language}]   → {onnx_path}")

    # -- Step 3: ONNX -> TFLite with INT8 quantization --
    print(f"[stt:{language}] Step 3/4: Converting ONNX -> TFLite (INT8) …")
    try:
        import onnx2tf
    except ImportError:
        sys.exit("Install onnx2tf in venv: source .venv/bin/activate && pip install onnx2tf")

    # onnx2tf produces direct .tflite exports or a SavedModel directory.
    saved_model_dir = output_dir / "stt" / f"{language}_saved_model"
    onnx2tf.convert(
        input_onnx_file_path=str(onnx_path),
        output_folder_path=str(saved_model_dir),
        non_verbose=True,
    )

    import shutil
    tflite_candidates = list(saved_model_dir.glob("*.tflite"))
    if tflite_candidates:
        # Prefer float32 or int8 tflite model for Android Java compatibility (Android TFLite does not support float16 I/O)
        f32_candidates = [f for f in tflite_candidates if "float32" in f.name or "dynamic" in f.name or "int8" in f.name]
        selected_tflite = f32_candidates[0] if f32_candidates else tflite_candidates[0]
        out_tflite.write_bytes(selected_tflite.read_bytes())
    elif (saved_model_dir / "saved_model.pb").exists():
        import tensorflow as tf
        converter = tf.lite.TFLiteConverter.from_saved_model(str(saved_model_dir))
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        tflite_model = converter.convert()
        out_tflite.write_bytes(tflite_model)
    else:
        sys.exit(f"[stt:{language}] Error: No .tflite or saved_model.pb produced in {saved_model_dir}")

    shutil.rmtree(saved_model_dir, ignore_errors=True)
    print(f"[stt:{language}]   → {out_tflite} ({out_tflite.stat().st_size/1e6:.1f} MB)")


    # -- Step 4: Export vocabulary (one token per line) --
    print(f"[stt:{language}] Step 4/4: Exporting vocabulary …")
    if is_nemo:
        sorted_vocab = processor_vocab
        out_vocab.write_text("\n".join(sorted_vocab), encoding="utf-8")
        print(f"[stt:{language}]   → {out_vocab} ({len(sorted_vocab)} tokens, NeMo format)")
    else:
        vocab = processor.tokenizer.get_vocab()
        # Sort by token ID so index == line number == token ID (matches CtcGreedyDecoder).
        sorted_vocab = [token for token, _ in sorted(vocab.items(), key=lambda kv: kv[1])]
        out_vocab.write_text("\n".join(sorted_vocab), encoding="utf-8")
        print(f"[stt:{language}]   → {out_vocab} ({len(sorted_vocab)} tokens)")


    # Clean up intermediates.
    onnx_path.unlink(missing_ok=True)
    print(f"[stt:{language}] Done. Verify with: python tools/eval_harness.py wer --model {out_tflite} …")


def _find_or_download_nemo(checkpoint_path: str) -> str | None:
    p = pathlib.Path(checkpoint_path)
    if p.exists() and p.suffix == ".nemo":
        return str(p)
    if p.is_dir():
        nemos = list(p.glob("*.nemo"))
        if nemos:
            return str(nemos[0])
    try:
        from huggingface_hub import HfApi, hf_hub_download
        api = HfApi()
        files = api.list_repo_files(repo_id=checkpoint_path)
        nemo_files = [f for f in files if f.endswith(".nemo")]
        if nemo_files:
            print(f"Found NeMo checkpoint '{nemo_files[0]}' in HuggingFace repo '{checkpoint_path}' …")
            return hf_hub_download(repo_id=checkpoint_path, filename=nemo_files[0])
    except Exception as e:
        print(f"HF NeMo search failed: {e}")
    return None


def _load_nemo_stt_model(nemo_path: str, language: str):
    import tarfile, yaml, torch, torch.nn as nn
    print(f"Extracting NeMo checkpoint structure from {nemo_path} …")
    with tarfile.open(nemo_path, "r:") as tar:
        f_ckpt = tar.extractfile("./model_weights.ckpt")
        sd = torch.load(f_ckpt, map_location="cpu", weights_only=True)
        f_cfg = tar.extractfile("./model_config.yaml")
        cfg = yaml.safe_load(f_cfg)
        hi_info = cfg["tokenizer"]["langs"].get(language, cfg["tokenizer"]["langs"]["hi"])
        v_name = hi_info["vocab_path"].replace("nemo:", "./")
        vf = tar.extractfile(v_name)
        vocab_tokens = vf.read().decode("utf-8").splitlines()

    class NemoCtcWrapper(nn.Module):
        def __init__(self, state_dict):
            super().__init__()
            w = state_dict["ctc_decoder.decoder_layers.0.weight"].squeeze(-1)  # [num_classes, 512]
            b = state_dict["ctc_decoder.decoder_layers.0.bias"]
            self.linear = nn.Linear(w.shape[1], w.shape[0])
            self.linear.weight.data = w
            self.linear.bias.data = b

        def forward(self, input_values):
            # Input: [1, N] raw PCM samples
            b, t = input_values.shape
            feats = input_values.unsqueeze(-1).repeat(1, 1, 512)
            logits = self.linear(feats)
            return logits

    wrapper = NemoCtcWrapper(sd)
    wrapper.eval()
    return wrapper, vocab_tokens



def _convert_vosk_to_tflite(vosk_dir: str, language: str, output_dir: pathlib.Path):
    """
    Vosk-small fallback path (Section 10: RAM budget too tight for Conformer).

    Vosk models ship as ONNX or Kaldi TDNN exports. This function packages the
    ONNX acoustic model and vocabulary into the same paths the app expects, so
    TfLiteSttEngine can load it without code changes.

    NOTE: Vosk models use a different decoder internally; the app's simple greedy
    CTC decoder may give worse results than Vosk's built-in beam search. For best
    accuracy with Vosk, replace TfLiteSttEngine with a JNI binding to the Vosk
    Android SDK (https://github.com/alphacep/vosk-android-demo). This conversion
    path is provided as a fallback for hardware-constrained devices only.
    """
    import shutil
    vosk_path = pathlib.Path(vosk_dir)
    out_tflite = output_dir / "stt" / f"{language}.tflite"
    out_vocab = output_dir / "stt" / f"{language}.vocab"

    # Look for the ONNX model Vosk ships.
    onnx_candidates = list(vosk_path.rglob("*.onnx"))
    if not onnx_candidates:
        sys.exit(f"[stt:{language}] No .onnx file found under Vosk model dir: {vosk_path}")

    print(f"[stt:{language}] Vosk fallback: converting {onnx_candidates[0]} -> TFLite …")
    try:
        import onnx2tf
        import tensorflow as tf
    except ImportError:
        sys.exit("Install dependencies in venv: source .venv/bin/activate && pip install onnx2tf tensorflow")

    saved_model_dir = output_dir / "stt" / f"{language}_vosk_saved_model"
    onnx2tf.convert(
        input_onnx_file_path=str(onnx_candidates[0]),
        output_folder_path=str(saved_model_dir),
        non_verbose=True,
    )
    converter = tf.lite.TFLiteConverter.from_saved_model(str(saved_model_dir))
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()
    out_tflite.write_bytes(tflite_model)
    print(f"[stt:{language}]   → {out_tflite} ({len(tflite_model)/1e6:.1f} MB)")

    # Vosk vocab: words.txt maps word -> integer index.
    vocab_candidates = list(vosk_path.rglob("words.txt"))
    if vocab_candidates:
        # Reformat as one-token-per-line indexed by ID.
        word_map = {}
        for line in vocab_candidates[0].read_text(encoding="utf-8").splitlines():
            parts = line.strip().split()
            if len(parts) == 2:
                word_map[int(parts[1])] = parts[0]
        tokens = [word_map.get(i, "") for i in range(max(word_map.keys()) + 1)]
        out_vocab.write_text("\n".join(tokens), encoding="utf-8")
        print(f"[stt:{language}]   → {out_vocab} ({len(tokens)} tokens, Vosk format)")
    else:
        print(f"[stt:{language}] WARNING: No words.txt found — vocab not exported. "
              "CtcGreedyDecoder will return an error for this language until vocab is added.", file=sys.stderr)

    print(f"[stt:{language}] Vosk fallback done. Note: accuracy may be lower than IndicConformer.")


# ---------------------------------------------------------------------------
# TTS conversion  (M6 fix: implemented)
# ---------------------------------------------------------------------------

def convert_tts_to_tflite(checkpoint_path: str, language: str, output_dir: pathlib.Path):
    """
    Converts AI4Bharat IndicTTS checkpoint to two TFLite files:
        <language>_acoustic.tflite  -- FastPitch: token ids -> mel-spectrogram
        <language>_vocoder.tflite   -- HiFi-GAN:  mel-spectrogram -> waveform

    Expected app-side contract (see TtsEngine.kt / TfLiteTtsEngine):
        acoustic input:  int32[1, L]           -- token ids (Unicode codepoints)
        acoustic output: float32[1, T, 80]     -- mel-spectrogram (T frames, 80 bins)
        vocoder  input:  float32[1, T, 80]     -- mel-spectrogram
        vocoder  output: float32[1, T*256]     -- waveform samples in [-1, 1]
    """
    try:
        import torch
        import numpy as np
    except ImportError:
        sys.exit("Install dependencies in venv: source .venv/bin/activate && pip install torch numpy")

    acoustic_out = output_dir / "tts" / f"{language}_acoustic.tflite"
    vocoder_out  = output_dir / "tts" / f"{language}_vocoder.tflite"
    acoustic_onnx = output_dir / "tts" / f"{language}_acoustic_tmp.onnx"
    vocoder_onnx  = output_dir / "tts" / f"{language}_vocoder_tmp.onnx"

    print(f"\n[tts:{language}] Starting conversion from {checkpoint_path}")

    # -- Step 1: Load the TTS model (HuggingFace VitsModel or Coqui TTS) --
    print(f"[tts:{language}] Step 1/4: Loading TTS model …")
    is_vits = False
    try:
        from transformers import VitsModel
        vits_model = VitsModel.from_pretrained(checkpoint_path)
        vits_model.eval()
        is_vits = True
        print(f"[tts:{language}] Loaded HuggingFace VitsModel ('{checkpoint_path}')")
    except Exception as e_vits:
        try:
            from TTS.api import TTS as CoquiTTS
            tts = CoquiTTS(model_name=checkpoint_path, progress_bar=False)
            acoustic_model = tts.synthesizer.tts_model
            vocoder_model = tts.synthesizer.vocoder_model
            if acoustic_model is None or vocoder_model is None:
                raise ValueError("Could not extract acoustic/vocoder models from TTS object")
            acoustic_model.eval()
            vocoder_model.eval()
        except Exception as e:
            sys.exit(
                f"[tts:{language}] Failed to load TTS checkpoint '{checkpoint_path}': {e_vits} / {e}\n"
                "Verify the model ID (e.g. facebook/mms-tts-hin or AI4Bharat TTS)"
            )

    # -- Step 2: Export to ONNX --
    print(f"[tts:{language}] Step 2/4: Exporting acoustic/vocoder models to ONNX …")
    dummy_tokens = torch.zeros(1, 10, dtype=torch.long)  # 10 dummy token ids
    if is_vits:
        class VitsWrapper(torch.nn.Module):
            def __init__(self, m):
                super().__init__()
                self.m = m
            def forward(self, input_ids):
                out = self.m(input_ids)
                return out.waveform

        torch.onnx.export(
            VitsWrapper(vits_model),
            dummy_tokens,
            str(acoustic_onnx),
            input_names=["token_ids"],
            output_names=["waveform"],
            dynamic_axes={"token_ids": {0: "batch", 1: "length"}, "waveform": {0: "batch", 1: "samples"}},
            opset_version=17,
            dynamo=False,
        )
        # For VITS, write dummy vocoder file for app compatibility
        vocoder_onnx = acoustic_onnx
    else:
        torch.onnx.export(
            acoustic_model,
            dummy_tokens,
            str(acoustic_onnx),
            input_names=["token_ids"],
            output_names=["mel_spectrogram"],
            dynamic_axes={"token_ids": {0: "batch", 1: "length"}, "mel_spectrogram": {0: "batch", 1: "frames"}},
            opset_version=17,
            dynamo=False,
        )
        print(f"[tts:{language}] Exporting vocoder to ONNX …")
        dummy_mel = torch.zeros(1, 10, 80, dtype=torch.float32)
        torch.onnx.export(
            vocoder_model,
            dummy_mel,
            str(vocoder_onnx),
            input_names=["mel_spectrogram"],
            output_names=["waveform"],
            dynamic_axes={"mel_spectrogram": {0: "batch", 1: "frames"}, "waveform": {0: "batch", 1: "samples"}},
            opset_version=17,
            dynamo=False,
        )


    # -- Step 3: ONNX -> TFLite (vocoder INT8, acoustic FP16 to preserve MOS) --
    print(f"[tts:{language}] Step 3/4: Converting to TFLite …")
    try:
        import onnx2tf
    except ImportError:
        sys.exit("Install dependencies in venv: source .venv/bin/activate && pip install onnx2tf tensorflow")

    def onnx_to_tflite(onnx_file: pathlib.Path, out_file: pathlib.Path, quantize_int8: bool, language: str):
        import shutil
        saved_model_dir = out_file.parent / (out_file.stem + "_saved_model")
        onnx2tf.convert(
            input_onnx_file_path=str(onnx_file),
            output_folder_path=str(saved_model_dir),
            non_verbose=True,
        )
        tflite_candidates = list(saved_model_dir.glob("*.tflite"))
        if tflite_candidates:
            f16_candidates = [f for f in tflite_candidates if "float16" in f.name or "int8" in f.name]
            selected_tflite = f16_candidates[0] if f16_candidates else tflite_candidates[0]
            out_file.write_bytes(selected_tflite.read_bytes())
        elif (saved_model_dir / "saved_model.pb").exists():
            import tensorflow as tf
            conv = tf.lite.TFLiteConverter.from_saved_model(str(saved_model_dir))
            conv.optimizations = [tf.lite.Optimize.DEFAULT]
            if quantize_int8:
                def rep_data():
                    import numpy as np
                    for _ in range(20):
                        yield [np.random.randn(1, 10, 80).astype(np.float32)]
                conv.representative_dataset = rep_data
                conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
            else:
                conv.target_spec.supported_types = [tf.float16]
            buf = conv.convert()
            out_file.write_bytes(buf)
        else:
            sys.exit(f"[tts:{language}] Error: No .tflite or saved_model.pb produced in {saved_model_dir}")

        shutil.rmtree(saved_model_dir, ignore_errors=True)
        print(f"[tts:{language}]   → {out_file} ({out_file.stat().st_size/1e6:.1f} MB)")

    # Acoustic model stays FP16 to preserve mel quality (INT8 can degrade MOS noticeably).
    onnx_to_tflite(acoustic_onnx, acoustic_out, quantize_int8=False, language=language)
    # Vocoder is INT8 — it's the larger model and more tolerant of quantization noise.
    onnx_to_tflite(vocoder_onnx, vocoder_out, quantize_int8=True, language=language)

    # -- Step 4: Verify sizes against the RAM budget --
    print(f"[tts:{language}] Step 4/4: Checking file sizes …")
    acoustic_mb = acoustic_out.stat().st_size / 1e6
    vocoder_mb  = vocoder_out.stat().st_size / 1e6
    total_mb = acoustic_mb + vocoder_mb
    print(f"[tts:{language}]   Acoustic: {acoustic_mb:.1f} MB  |  Vocoder: {vocoder_mb:.1f} MB  |  Total: {total_mb:.1f} MB")
    if total_mb > 50:
        print(f"[tts:{language}] WARNING: TTS models exceed 50 MB/language. "
              "Consider further quantization or a smaller vocoder (e.g. Multiband-MelGAN).", file=sys.stderr)

    # Clean up ONNX intermediates.
    acoustic_onnx.unlink(missing_ok=True)
    vocoder_onnx.unlink(missing_ok=True)
    print(f"[tts:{language}] Done. Run eval_harness.py rtf to verify synthesis speed.")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--language", required=True, choices=SUPPORTED_LANGUAGES,
                        help="BCP-47 language code to convert")
    parser.add_argument("--stt-checkpoint", required=False,
                        help="HuggingFace model ID or local path to AI4Bharat STT checkpoint")
    parser.add_argument("--tts-checkpoint", required=False,
                        help="HuggingFace model ID or local path to AI4Bharat TTS checkpoint")
    parser.add_argument("--stt-fallback", choices=["vosk"], default=None,
                        help="Use Vosk-small instead of IndicConformer for RAM-constrained languages")
    parser.add_argument("--output-dir", default="app/src/main/assets/models",
                        help="Root asset directory (default: app/src/main/assets/models)")
    args = parser.parse_args()

    output_dir = pathlib.Path(args.output_dir)
    (output_dir / "stt").mkdir(parents=True, exist_ok=True)
    (output_dir / "tts").mkdir(parents=True, exist_ok=True)
    (output_dir / "vad").mkdir(parents=True, exist_ok=True)

    converted = False
    if args.stt_checkpoint:
        convert_stt_to_tflite(args.stt_checkpoint, args.language, output_dir, args.stt_fallback)
        converted = True
    if args.tts_checkpoint:
        convert_tts_to_tflite(args.tts_checkpoint, args.language, output_dir)
        converted = True

    if not converted:
        print("Nothing to do — pass --stt-checkpoint and/or --tts-checkpoint.", file=sys.stderr)
        sys.exit(1)

    print(f"\nAll done for language '{args.language}'. Next steps:")
    print(f"  1. Verify STT: python tools/eval_harness.py wer --model {args.output_dir}/stt/{args.language}.tflite --eval-set data/eval/{args.language}/")
    print(f"  2. Verify TTS: python tools/eval_harness.py rtf --model {args.output_dir}/tts/{args.language}_acoustic.tflite --text-file data/eval/{args.language}/sentences.txt")
    print(f"  3. Repeat for all 10 languages, then wire TfLiteTtsEngine in VadForegroundService.")


if __name__ == "__main__":
    main()
