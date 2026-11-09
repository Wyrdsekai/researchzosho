"""MetricX-24 (Hybrid) in quality-estimation mode, for ResearchZosho's translation checks.

Reads JSON lines on stdin, one a unit: {"source": "...", "hypothesis": "..."}; writes one JSON line a unit on stdout:
{"score": 7.3}, where the score is 0 to 25 and lower is better, or {"error": "..."} for a unit it could not score.
The model is google/metricx-24-hybrid-large-v2p6 (Apache 2.0), an mT5 trained for regression: the prediction is the
logit of one fixed token after a single decoder step, as the authors' own predict script reads it. In QE mode the
input is "source: <source> candidate: <candidate>", with no reference, and the end-of-sequence token is removed, as
the model was trained without it. Run with python -I; it imports only torch and transformers.
"""
import argparse
import json
import sys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="google/metricx-24-hybrid-large-v2p6")
    ap.add_argument("--tokenizer", default="google/mt5-xl")
    ap.add_argument("--max-input-length", type=int, default=1536)
    ap.add_argument("--cache", default=None)
    ap.add_argument("--check", action="store_true", help="load the model and answer ready, scoring nothing")
    ap.add_argument("--device", default=None, help="cuda or cpu; by default cuda when it is there and has room, else cpu")
    args = ap.parse_args()
    import torch
    from transformers import AutoTokenizer, MT5ForConditionalGeneration

    class MT5ForRegression(MT5ForConditionalGeneration):
        def forward(self, input_ids=None, attention_mask=None, **kw):
            decoder_input_ids = torch.zeros((input_ids.size(0), 1), dtype=torch.long, device=input_ids.device)
            out = super().forward(input_ids=input_ids, attention_mask=attention_mask, decoder_input_ids=decoder_input_ids)
            # the authors read the logit of token 250089 at the one decoder position, clipped to the score's range
            return out.logits[:, 0, 250089].clamp(0, 25)

    device = args.device or ("cuda" if torch.cuda.is_available() else "cpu")
    tok = AutoTokenizer.from_pretrained(args.tokenizer, cache_dir=args.cache, legacy=False)
    model = MT5ForRegression.from_pretrained(args.model, cache_dir=args.cache, dtype=torch.float32 if device == "cpu" else torch.bfloat16)
    try:
        model.to(device)
    except (torch.cuda.OutOfMemoryError, RuntimeError) as e:  # a shared card with no room: the CPU scores, slower (seen live, 2026-11-07)
        if device == "cpu":
            raise
        print(json.dumps({"note": "the GPU had no room (" + str(e)[:120] + "); scoring on the CPU"}), file=sys.stderr, flush=True)
        device = "cpu"
        model = model.to("cpu").float()   # off the card first: converting on the card would ask it for the room it has not got
        torch.cuda.empty_cache()
    model.eval()
    if args.check:
        print(json.dumps({"ready": True, "device": device}))
        return
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            unit = json.loads(line)
            text = "source: " + unit.get("source", "") + " candidate: " + unit.get("hypothesis", "")
            enc = tok(text, max_length=args.max_input_length, truncation=True, padding=False, return_tensors="pt")
            ids = enc["input_ids"][:, :-1]   # the end-of-sequence token off, as the model was trained
            mask = enc["attention_mask"][:, :-1]
            with torch.no_grad():
                score = model(input_ids=ids.to(device), attention_mask=mask.to(device))
            print(json.dumps({"score": round(float(score[0].item()), 4)}), flush=True)
        except Exception as e:  # one unit's failure is that unit's, not the run's
            print(json.dumps({"error": str(e)[:200]}), flush=True)


if __name__ == "__main__":
    main()
