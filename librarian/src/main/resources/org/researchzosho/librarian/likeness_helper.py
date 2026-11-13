# The library's likeness helper: two embeddings, on the CPU, in a Python environment of its own (ResearchZosho 0.7.0).
#
#   picture  open_clip ViT-B-32 with the LAION-2B weights (laion2b_s34b_b79k): code MIT (mlfoundations/open_clip), weights MIT by the
#            model card at huggingface.co/laion/CLIP-ViT-B-32-laion2B-s34B-b79k, 605,219,813 bytes, fetched once into --cache.
#   face     OpenCV's YuNet detector (MIT, 232,589 bytes) and SFace recogniser (Apache-2.0, 38,696,353 bytes) from opencv/opencv_zoo,
#            fetched once into --models. facenet-pytorch was not taken: its code is MIT but its weights were trained on VGGFace2, a
#            dataset licensed for non-commercial research, so they are not clearly free for every library's use.
#
# Protocol, one JSON object a line on stdin, one a line on stdout:
#   {"id": "…", "ref": "<base64 image>", "cand": "<base64 image>"}
#   → {"id": "…", "picture": 0.83, "face": 0.61, "faces": [true, true], "face_px": [184, 41], "face_conf": [0.91, 0.89]}
#     face is null unless both pictures hold a detectable face; face_px is each face box's longer side in the picture's own pixels and
#     face_conf the detector's confidence, so the library can leave a small or unclear face unmeasured
#   {"id": "…", "image": "<base64>"} → {"id": "…", "face": true|false}      whether the picture holds a face
# --check loads everything (fetching what is missing) and prints {"ready": true, …}; a failure prints {"ready": false, "error": "…"}.
# --serve PORT answers over HTTP on 127.0.0.1 (--bind another) instead, one request at a time: GET /health gives {"ready": true,
# "model": …, "device": "cpu"}; POST /measure with {"requests": [<a request as above>, …]} gives {"replies": [<its reply>, …]}.
import sys, json, base64, hashlib, io, os, time, argparse, urllib.request

MODELS = {
    "yunet": ("face_detection_yunet_2023mar.onnx", "https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx", 232589),
    "sface": ("face_recognition_sface_2021dec.onnx", "https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx", 38696353),
}
UA = "researchzosho.org (library software; likeness helper setup)"


def fetch_models(folder):
    os.makedirs(folder, exist_ok=True)
    for key, (name, url, size) in MODELS.items():
        path = os.path.join(folder, name)
        if os.path.isfile(path) and os.path.getsize(path) == size:
            continue
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        data = urllib.request.urlopen(req, timeout=300).read()
        if len(data) != size:
            raise RuntimeError("%s came as %d bytes, not %d" % (name, len(data), size))
        with open(path, "wb") as f:
            f.write(data)


class Helper:
    def __init__(self, cache, models):
        import numpy as np
        import cv2
        import torch
        import open_clip
        from PIL import Image
        self.np, self.cv2, self.torch, self.Image = np, cv2, torch, Image
        torch.set_num_threads(max(1, min(4, os.cpu_count() or 1)))
        self.model, _, self.pre = open_clip.create_model_and_transforms("ViT-B-32", pretrained="laion2b_s34b_b79k", cache_dir=cache)
        self.model.eval()
        fetch_models(models)
        self.det = cv2.FaceDetectorYN.create(os.path.join(models, MODELS["yunet"][0]), "", (320, 320), 0.6, 0.3, 5000)
        self.rec = cv2.FaceRecognizerSF.create(os.path.join(models, MODELS["sface"][0]), "")
        self.cache = {}

    def decode(self, b64):
        raw = base64.b64decode(b64)
        img = self.Image.open(io.BytesIO(raw)).convert("RGB")
        return hashlib.sha256(raw).hexdigest(), img

    def embed(self, b64):
        sha, img = self.decode(b64)
        hit = self.cache.get(sha)
        if hit is not None:
            return hit
        with self.torch.no_grad():
            x = self.pre(img).unsqueeze(0)
            v = self.model.encode_image(x)
            v = v / v.norm(dim=-1, keepdim=True)
            picture = v[0].cpu().numpy()
        bgr = self.cv2.cvtColor(self.np.array(img), self.cv2.COLOR_RGB2BGR)
        h, w = bgr.shape[:2]
        scale = 640.0 / max(h, w)
        used = 1.0
        if scale < 1.0:
            bgr = self.cv2.resize(bgr, (int(w * scale), int(h * scale)))
            h, w = bgr.shape[:2]
            used = scale
        self.det.setInputSize((w, h))
        _, faces = self.det.detect(bgr)
        face, px, conf = None, 0, 0.0
        if faces is not None and len(faces) > 0:
            best = max(faces, key=lambda f: f[2] * f[3])   # the largest face
            aligned = self.rec.alignCrop(bgr, best)
            face = self.rec.feature(aligned).reshape(-1)
            face = face / (self.np.linalg.norm(face) + 1e-9)
            px = int(round(float(max(best[2], best[3])) / used))
            conf = float(best[14])
        out = (picture, face, px, conf)
        if len(self.cache) > 200:
            self.cache.clear()
        self.cache[sha] = out
        return out

    def score(self, ref_b64, cand_b64):
        pr, fr, pxr, cr = self.embed(ref_b64)
        pc, fc, pxc, cc = self.embed(cand_b64)
        picture = float(self.np.dot(pr, pc))
        face = float(self.np.dot(fr, fc)) if fr is not None and fc is not None else None
        return picture, face, [fr is not None, fc is not None], [pxr, pxc], [round(cr, 3), round(cc, 3)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", required=True)
    ap.add_argument("--models", required=True)
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--serve", type=int, default=0)
    ap.add_argument("--bind", default="127.0.0.1")
    a = ap.parse_args()
    try:
        t = time.time()
        h = Helper(a.cache, a.models)
        if a.check:
            print(json.dumps({"ready": True, "picture": "open_clip ViT-B-32 laion2b_s34b_b79k", "face": "OpenCV YuNet + SFace", "load_s": round(time.time() - t, 1)}), flush=True)
            return
    except Exception as e:
        print(json.dumps({"ready": False, "error": str(e)}), flush=True)
        sys.exit(1)
    def answer(req):
        try:
            if "image" in req:
                _, f, px, conf = h.embed(req["image"])
                return {"id": req.get("id", ""), "face": f is not None, "face_px": px, "face_conf": round(conf, 3)}
            picture, face, faces, px, conf = h.score(req["ref"], req["cand"])
            return {"id": req.get("id", ""), "picture": round(picture, 4), "face": None if face is None else round(face, 4), "faces": faces, "face_px": px, "face_conf": conf}
        except Exception as e:
            return {"id": req.get("id", "") if isinstance(req, dict) else "", "error": str(e)}

    if a.serve:
        serve(a.bind, a.serve, {"ready": True, "model": "open_clip ViT-B-32 laion2b_s34b_b79k; OpenCV YuNet + SFace", "device": "cpu"}, answer)
        return
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except ValueError as e:
            print(json.dumps({"id": "", "error": str(e)}), flush=True)
            continue
        print(json.dumps(answer(req)), flush=True)


def serve(bind, port, health, answer):
    """One request at a time; the library's own front asks for the secret before it."""
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
            self.reply(200, health) if self.path.rstrip("/") == "/health" else self.reply(404, {"error": "not here"})

        def do_POST(self):
            if self.path.rstrip("/") != "/measure":
                return self.reply(404, {"error": "not here"})
            try:
                req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
            except ValueError:
                return self.reply(400, {"error": "not JSON"})
            self.reply(200, {"replies": [answer(r) for r in req.get("requests", [])]})

        def log_message(self, *a):
            pass

    print(json.dumps({"serving": "http://%s:%d" % (bind, port), **health}), flush=True)
    HTTPServer((bind, port), Handler).serve_forever()


if __name__ == "__main__":
    main()
