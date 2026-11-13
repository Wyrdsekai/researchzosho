"""MetricX-24 (Hybrid) in quality-estimation mode, for ResearchZosho's translation checks.

Reads JSON lines on stdin, one a unit: {"source": "...", "hypothesis": "..."}; writes one JSON line a unit on stdout:
{"score": 7.3}, where the score is 0 to 25 and lower is better, or {"error": "..."} for a unit it could not score.
The model is google/metricx-24-hybrid-large-v2p6 (Apache 2.0), an mT5 trained for regression: the prediction is the
logit of one fixed token after a single decoder step, as the authors' own predict script reads it. In QE mode the
input is "source: <source> candidate: <candidate>", with no reference, and the end-of-sequence token is removed, as
the model was trained without it. Run with python -I; it imports only torch and transformers.

With --serve PORT it loads the model once and answers over HTTP on 127.0.0.1 (--bind another address), one request at a
time: GET /health gives {"ready": true, "model": ..., "device": ...}; POST /score with {"pairs": [{"source": ..., "hypothesis":
...}, ...]} gives {"scores": [7.3, null, ...]}, null for a pair it could not score. The library's own front (researchzosho helpers
serve) stands before it and asks for the secret; this server answers whoever reaches it.
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
    ap.add_argument("--serve", type=int, default=0, help="answer over HTTP on this port instead of reading stdin")
    ap.add_argument("--bind", default="127.0.0.1", help="the address the server listens on")
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
    # the card it really runs on, as torch sees it: its name and its UUID (CUDA_VISIBLE_DEVICES names it by UUID, so device 0 is that card)
    card = {}
    if device == "cuda":
        try:
            card["card"] = torch.cuda.get_device_name(0)
            u = getattr(torch.cuda.get_device_properties(0), "uuid", None)
            if u is not None:
                card["card_uuid"] = str(u) if str(u).startswith("GPU-") else "GPU-" + str(u)
        except Exception:  # an old torch that cannot say: the device alone
            pass
    if args.check:
        print(json.dumps({"ready": True, "device": device, **card}))
        return

    def score(unit):
        text = "source: " + unit.get("source", "") + " candidate: " + unit.get("hypothesis", "")
        enc = tok(text, max_length=args.max_input_length, truncation=True, padding=False, return_tensors="pt")
        ids = enc["input_ids"][:, :-1]   # the end-of-sequence token off, as the model was trained
        mask = enc["attention_mask"][:, :-1]
        with torch.no_grad():
            s = model(input_ids=ids.to(device), attention_mask=mask.to(device))
        return round(float(s[0].item()), 4)

    if args.serve:
        serve(args.bind, args.serve, {"ready": True, "model": args.model, "device": device, **card}, score)
        return
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            print(json.dumps({"score": score(json.loads(line))}), flush=True)
        except Exception as e:  # one unit's failure is that unit's, not the run's
            print(json.dumps({"error": str(e)[:200]}), flush=True)


def serve(bind, port, health, score):
    """One request at a time: the model is not shared between threads."""
    from http.server import BaseHTTPRequestHandler, HTTPServer

    class Handler(BaseHTTPRequestHandler):
        def reply(self, code, body):
            data = json.dumps(body).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path.rstrip("/") == "/health":
                self.reply(200, health)
            else:
                self.reply(404, {"error": "not here"})

        def do_POST(self):
            if self.path.rstrip("/") != "/score":
                return self.reply(404, {"error": "not here"})
            try:
                req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
            except ValueError:
                return self.reply(400, {"error": "not JSON"})
            out = []
            for unit in req.get("pairs", []):
                try:
                    out.append(score(unit))
                except Exception:  # one pair's failure is that pair's
                    out.append(None)
            self.reply(200, {"scores": out})

        def log_message(self, *a):
            pass

    print(json.dumps({"serving": "http://%s:%d" % (bind, port), **health}), flush=True)
    HTTPServer((bind, port), Handler).serve_forever()


if __name__ == "__main__":
    main()
