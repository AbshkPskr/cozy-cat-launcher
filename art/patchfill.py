"""Texture-copying hole fill (exemplar / shift-map), so filled areas keep real detail and grain
instead of turning into a smooth smear."""
import cv2, numpy as np

def shiftmap_fill(img_bgr, mask, pad=60):
    """Fill mask>0 in img_bgr by copying patches from the rest of the image (cv2.xphoto shift-map).
    Works per connected region on a cropped window for speed."""
    out = img_bgr.copy()
    n, lab, st, _ = cv2.connectedComponentsWithStats((mask > 0).astype(np.uint8))
    H, W = mask.shape
    for i in range(1, n):
        x, y, w, h = st[i, :4]
        x0, y0 = max(0, x - pad), max(0, y - pad)
        x1, y1 = min(W, x + w + pad), min(H, y + h + pad)
        win = out[y0:y1, x0:x1].copy()
        m = (lab[y0:y1, x0:x1] == i).astype(np.uint8)
        keep = (1 - m) * 255                       # xphoto: non-zero = known pixels
        lab_win = cv2.cvtColor(win, cv2.COLOR_BGR2Lab)
        res = np.zeros_like(lab_win)
        cv2.xphoto.inpaint(lab_win, keep, res, cv2.xphoto.INPAINT_SHIFTMAP)
        res = cv2.cvtColor(res, cv2.COLOR_Lab2BGR)
        a = cv2.GaussianBlur(m.astype(np.float32), (0, 0), 1.2)[..., None]
        out[y0:y1, x0:x1] = (win * (1 - a) + res * a).astype(np.uint8)
    return out
