"""Finding text and balloons on a page: the RT-DETR-v2 detector of the manifest (int8), ONNX Runtime on the CPU.
Input as its card says: the page resized to 640x640 (aspect not kept), RGB, 0..1. The graph is asked for boxes on a
1x1 page and they are scaled here, so the order of its size input cannot put boxes in the wrong space."""
import numpy as np
import onnxruntime as ort
from PIL import Image
from .geometry import drop_doubles

LABELS = {0: "bubble", 1: "text_bubble", 2: "text_free"}

class Detector:
    def __init__(self, path, threads=4, score=0.3):
        so = ort.SessionOptions(); so.intra_op_num_threads = int(threads); so.inter_op_num_threads = 1
        self.sess = ort.InferenceSession(path, sess_options=so, providers=["CPUExecutionProvider"])
        self.score = score

    def __call__(self, image):
        """(text boxes with class and score, doubles dropped; balloon boxes), at or above the score threshold."""
        im = image.convert("RGB"); w, h = im.size
        x = np.asarray(im.resize((640, 640), Image.BILINEAR), dtype=np.float32) / 255.0
        labels, boxes, scores = self.sess.run(None, {"images": x.transpose(2, 0, 1)[None], "orig_target_sizes": np.array([[1, 1]], dtype=np.int64)})
        dets = []
        for l, b, s in zip(labels[0], boxes[0], scores[0]):
            if s < self.score: continue
            dets.append({"cls": LABELS[int(l)], "score": round(float(s), 4),
                         "box": [round(max(0.0, float(b[0]) * w), 1), round(max(0.0, float(b[1]) * h), 1),
                                 round(min(float(w), float(b[2]) * w), 1), round(min(float(h), float(b[3]) * h), 1)]})
        text = drop_doubles([d for d in dets if d["cls"] != "bubble"])
        return text, [d["box"] for d in dets if d["cls"] == "bubble"]
