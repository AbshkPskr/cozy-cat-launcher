"""Renders the rigged kitten in a few poses over the room, to check seams."""
import cv2, numpy as np, json, os
D = os.path.dirname(os.path.abspath(__file__))
A = os.path.join(D, "..", "app", "src", "main", "assets")
L = json.load(open(os.path.join(A, "layout.json")))
room = cv2.imread(os.path.join(D, "dbg", "room.png")).astype(np.float32)
def load(n): return cv2.imread(os.path.join(A, "cat", n + ".png"), cv2.IMREAD_UNCHANGED).astype(np.float32)
def over(dst, img, x, y, ang=0.0, px=0, py=0):
    h, w = img.shape[:2]
    M = cv2.getRotationMatrix2D((px - x, py - y), ang, 1.0)
    M[0, 2] += x; M[1, 2] += y
    warped = cv2.warpAffine(img, M, (dst.shape[1], dst.shape[0]), flags=cv2.INTER_LINEAR, borderValue=(0, 0, 0, 0))
    a = warped[..., 3:4] / 255
    return dst * (1 - a) + warped[..., :3] * a
P = L["parts"]
out = []
for (lf, lh, el, er, tl) in [(0, 0, 0, 0, 0), (16, 0, 6, -6, 6), (0, 16, -6, 6, -6)]:
    c = room.copy()
    c = over(c, load("tail"), P["tail"]["x"], P["tail"]["y"], tl, P["tail"]["px"], P["tail"]["py"])
    c = over(c, load("kitten"), L["kitten"][0], L["kitten"][1])
    c = over(c, load("leg_f"), P["leg_f"]["x"], P["leg_f"]["y"] - lf)
    c = over(c, load("leg_h"), P["leg_h"]["x"], P["leg_h"]["y"] - lh)
    for n, ang in (("ear_l", el), ("ear_r", er)):
        c = over(c, load(n), P[n]["x"], P[n]["y"], ang, P[n]["px"], P[n]["py"])
    out.append(c[220:700, 600:1420])
cv2.imwrite(os.path.join(D, "dbg", "poses.png"), np.vstack(out).astype(np.uint8))
