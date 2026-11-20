"""Cuts the animated pieces out of mockup.png at 1920x1080 scale. Run after build_assets.py."""
import cv2, numpy as np, os, json
D = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(D, "..", "app", "src", "main", "assets")
DBG = os.path.join(D, "dbg")
src = cv2.imread(os.path.join(D, "mockup.png"))
H0, W0 = src.shape[:2]
W, H = 1920, 1080
S = W / W0
img = cv2.resize(src, (W, H), interpolation=cv2.INTER_LANCZOS4)
blur = cv2.GaussianBlur(img, (0, 0), 1.2)
img = cv2.addWeighted(img, 1.25, blur, -0.25, 0)
def sc(v): return int(round(v * S))
layout = {"screen": [W, H]}

def save_rgba(path, bgr, alpha):
    rgba = np.dstack([bgr, np.clip(alpha, 0, 255).astype(np.uint8)])
    cv2.imwrite(path, rgba)

def bbox(alpha, pad=2):
    ys, xs = np.where(alpha > 4)
    return max(xs.min() - pad, 0), max(ys.min() - pad, 0), min(xs.max() + pad + 1, W), min(ys.max() + pad + 1, H)

# ------------------------------------------------------------------ kitten + tail
k0 = cv2.imread(os.path.join(D, "dbg_kmask.png"), 0)
ka = cv2.resize(k0, (W, H), interpolation=cv2.INTER_LINEAR).astype(np.float32)
ka = cv2.erode(ka, np.ones((5, 5), np.uint8))           # trim background fringe by ~2px
ka = cv2.GaussianBlur(ka, (0, 0), 1.3)
# Decontaminate edge colours: pull edge pixels toward the inner fur colour
inner = cv2.erode((ka > 200).astype(np.uint8), np.ones((7, 7), np.uint8))
fur = cv2.inpaint(img, (1 - inner).astype(np.uint8) * 255 & (ka > 10).astype(np.uint8) * 255, 3, cv2.INPAINT_TELEA)
edge_w = np.clip((200 - ka) / 200.0, 0, 1)[..., None]
kimg = (img * (1 - edge_w * 0.7) + fur * edge_w * 0.7).astype(np.uint8)

# ---- Eyes: the iris (with its ORIGINAL shine and detail) becomes a piece that can glance ----
# Inner eye sockets (inside the dark outline), mockup px: centre, half-axes
EYES = [("eye_l", (681.5, 405.5), (17.0, 24.0)), ("eye_r", (785.0, 420.0), (26.5, 24.5))]
eye_layout = {}
hsv_full = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
SS = 4   # supersampling for smooth, anti-aliased masks
for name, (ecx, ecy), (eax, eay) in EYES:
    cx, cy, ax, ay = sc(ecx), sc(ecy), sc(eax), sc(eay)
    sock = np.zeros((H, W), np.uint8)
    cv2.ellipse(sock, (cx, cy), (ax, ay), 0, 0, 360, 255, -1)
    v = hsv_full[..., 2].astype(np.float32) / 255
    # Find the iris: biggest dark blob in the socket (shine holes filled), then fit a smooth ellipse
    dark = ((v < 0.58) & (sock > 0)).astype(np.uint8)
    dark = cv2.morphologyEx(dark, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (11, 11)))
    n_, lab, st, _ = cv2.connectedComponentsWithStats(dark)
    blob = np.where(lab == 1 + np.argmax(st[1:, cv2.CC_STAT_AREA]), 255, 0).astype(np.uint8)
    cnts, _ = cv2.findContours(blob, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    (ex, ey), (ew, eh), ang = cv2.fitEllipse(max(cnts, key=cv2.contourArea))
    # anti-aliased ellipse alpha, slightly larger than the fit so the iris rim is included
    big = np.zeros((H * 1, W * 1), np.float32)
    hi = np.zeros(((2 * ay + 40) * SS, (2 * ax + 40) * SS), np.uint8)
    ox, oy = cx - ax - 20, cy - ay - 20
    cv2.ellipse(hi, (int((ex - ox) * SS), int((ey - oy) * SS)),
                (int((ew / 2 + 2.5) * SS), int((eh / 2 + 2.5) * SS)), ang, 0, 360, 255, -1, cv2.LINE_AA)
    small = cv2.resize(hi, (hi.shape[1] // SS, hi.shape[0] // SS), interpolation=cv2.INTER_AREA).astype(np.float32)
    big[oy:oy + small.shape[0], ox:ox + small.shape[1]] = small
    ia = cv2.GaussianBlur(big, (0, 0), 0.7)
    ia = np.minimum(ia, cv2.GaussianBlur(sock.astype(np.float32), (0, 0), 0.7))   # never past the socket
    x0_, y0_, x1_, y1_ = bbox(ia, 3)
    # iris keeps the original pixels, shine and all
    save_rgba(os.path.join(OUT, "cat", name + "_iris.png"), img[y0_:y1_, x0_:x1_], ia[y0_:y1_, x0_:x1_])
    # Behind the iris: the eye's own white, grown in from the surrounding sclera pixels (keeps its
    # natural shading), so a glance only reveals real-looking eye white.
    hole = (cv2.dilate((ia > 8).astype(np.uint8), np.ones((5, 5), np.uint8)) * 255) & sock
    behind = cv2.inpaint(img, hole, 9, cv2.INPAINT_TELEA)
    behind = cv2.GaussianBlur(behind, (0, 0), 2.0)
    hm = cv2.GaussianBlur(hole.astype(np.float32) / 255, (0, 0), 1.5)[..., None]
    kimg = (kimg.astype(np.float32) * (1 - hm) + behind.astype(np.float32) * hm).astype(np.uint8)
    eye_layout[name] = {"iris": [int(x0_), int(y0_)], "socket": [cx, cy, ax, ay]}
layout["eyes"] = eye_layout

# ---- Rig: cut the kitten into parts that can move -------------------------------------
yy, xx = np.mgrid[0:H, 0:W]
def poly_mask(pts, blur=1.5):
    m = np.zeros((H, W), np.float32)
    cv2.fillPoly(m, [np.array([[sc(x), sc(y)] for x, y in pts], np.int32)], 1.0)
    return cv2.GaussianBlur(m, (0, 0), blur) if blur else m

parts = {}
def add_part(name, alpha, pivot):
    x0, y0, x1, y1 = bbox(alpha)
    save_rgba(os.path.join(OUT, "cat", name + ".png"), kimg[y0:y1, x0:x1], alpha[y0:y1, x0:x1])
    parts[name] = {"x": int(x0), "y": int(y0), "px": int(sc(pivot[0])), "py": int(sc(pivot[1]))}

# Tail: cut exactly where the tail leaves the rump, along the diagonal (1085,262)-(1165,290)
# (mockup px). Only tail fur moves; the back stays with the body.
def cut_y(x):            # y of the cut line at screen x (1920 space)
    return sc(262) + (x - sc(1085)) * (sc(290) - sc(262)) / (sc(1165) - sc(1085))
line = cut_y(xx.astype(np.float32))
tail_zone = (xx > sc(1072)) | ((yy < sc(248)) & (xx > sc(1030)))   # tip curls left above the back
tm = ((yy < line) & tail_zone).astype(np.float32)
tm = cv2.GaussianBlur(tm, (0, 0), 1.5)
tail_a = ka * tm
# body keeps an 8px strip above the line so the joint never opens while the tail sways
keep = ((yy < line - sc(8)) & tail_zone).astype(np.float32)
remove = cv2.GaussianBlur(keep, (0, 0), 2.0)

# Ears (cut along their base line; body keeps the base so a twitch never opens a gap)
earL = poly_mask([(640, 305), (652, 215), (705, 228), (752, 262), (735, 290), (700, 300)], 2)
earL *= (yy < sc(298) - (xx - sc(650)) * 0.35).astype(np.float32)
earR = poly_mask([(812, 292), (822, 245), (870, 220), (915, 232), (922, 335), (880, 330)], 2)
earR *= (yy < sc(300) + (xx - sc(820)) * 0.32).astype(np.float32)
earL_a = ka * earL
earR_a = ka * earR
remove = np.maximum(remove, np.clip(cv2.GaussianBlur((earL > 0.5).astype(np.float32), (0, 0), 1) * 1.0, 0, 1) * (yy < sc(274)))
remove = np.maximum(remove, np.clip(cv2.GaussianBlur((earR > 0.5).astype(np.float32), (0, 0), 1) * 1.0, 0, 1) * (yy < sc(266) + (xx - sc(820)) * 0.32))

# Near front leg (planted paw under the chest) and near hind leg
legF = poly_mask([(726, 492), (814, 492), (826, 594), (718, 594)], 2)
legH = poly_mask([(1066, 462), (1170, 462), (1186, 594), (1058, 594)], 2)
legF_a = ka * legF
legH_a = ka * legH
# body loses the legs only below their overlap zone, so at rest the joint is invisible
remove = np.maximum(remove, legF * np.clip((yy - sc(538)) / 6.0, 0, 1))
remove = np.maximum(remove, legH * np.clip((yy - sc(518)) / 6.0, 0, 1))

body_a = ka * (1 - np.clip(remove, 0, 1))
x0, y0, x1, y1 = bbox(body_a)
save_rgba(os.path.join(OUT, "cat", "kitten.png"), kimg[y0:y1, x0:x1], body_a[y0:y1, x0:x1])
layout["kitten"] = [int(x0), int(y0), int(x1 - x0), int(y1 - y0)]
add_part("tail", tail_a, (1125, 276))
add_part("ear_l", earL_a, (700, 292))
add_part("ear_r", earR_a, (868, 312))
add_part("leg_f", legF_a, (770, 500))
add_part("leg_h", legH_a, (1116, 470))
layout["parts"] = parts
layout["kittenFeetY"] = sc(585)
layout["kittenFlipX"] = int((x0 + x1) / 2)
layout["pawTip"] = sc(588) - int((x0 + x1) / 2)        # raised paw tip, relative to flip axis
layout["headX"] = sc(790)
tx0, ty0 = parts["tail"]["x"], parts["tail"]["y"]

# ------------------------------------------------------------------ blink overlay
blink = np.zeros((H, W, 4), np.uint8)
for (cx, cy, rx, ry) in [(684, 404, 27, 31), (783, 420, 31, 34)]:
    cx, cy, rx, ry = sc(cx), sc(cy), sc(rx), sc(ry)
    em = np.zeros((H, W), np.uint8)
    cv2.ellipse(em, (cx, cy), (rx + 3, ry + 3), 0, 0, 360, 255, -1)
    region = img.copy()
    filled = cv2.inpaint(region, em, 9, cv2.INPAINT_TELEA)
    filled = cv2.GaussianBlur(filled, (0, 0), 2.2)
    # fur grain back on top
    n = np.random.default_rng(cx).normal(0, 5, (H, W, 1)).astype(np.float32)
    n = cv2.GaussianBlur(n, (0, 0), 0.8)[..., None]
    filled = np.clip(filled.astype(np.float32) + n, 0, 255).astype(np.uint8)
    # closed-eye line: a gentle downward arc with tiny lashes
    pts = []
    for t in np.linspace(-1, 1, 40):
        pts.append((cx + t * rx * 0.95, cy + ry * 0.15 + (1 - t * t) * ry * 0.38))
    pts = np.array(pts, np.int32).reshape(-1, 1, 2)
    big = cv2.resize(filled, None, fx=1, fy=1)
    cv2.polylines(big, [pts], False, (35, 45, 70), max(3, sc(4)), cv2.LINE_AA)
    a = cv2.GaussianBlur(em.astype(np.float32), (0, 0), 3)
    blink[..., :3] = np.where(a[..., None] > 0, big, blink[..., :3])
    blink[..., 3] = np.maximum(blink[..., 3], a.astype(np.uint8))
bx0, by0, bx1, by1 = layout["kitten"][0], layout["kitten"][1], layout["kitten"][0] + layout["kitten"][2], layout["kitten"][1] + layout["kitten"][3]
cv2.imwrite(os.path.join(OUT, "cat", "blink.png"), blink[by0:by1, bx0:bx1])

# ------------------------------------------------------------------ sleeping kitten patch
sx0, sy0, sx1, sy1 = sc(1200), sc(355), sc(1478), sc(548)
sa = np.zeros((sy1 - sy0, sx1 - sx0), np.float32)
cv2.ellipse(sa, ((sx1 - sx0) // 2, (sy1 - sy0) // 2 + 8), ((sx1 - sx0) // 2 - 18, (sy1 - sy0) // 2 - 14), 0, 0, 360, 255, -1)
sa = cv2.GaussianBlur(sa, (0, 0), 10)
save_rgba(os.path.join(OUT, "cat", "sleeper.png"), img[sy0:sy1, sx0:sx1], sa)
layout["sleeper"] = [sx0, sy0, sx1 - sx0, sy1 - sy0]

# ------------------------------------------------------------------ ball (texture rotates, shading fixed)
bcx, bcy, br = sc(533), sc(554), sc(61)
pad = 6
R = br + pad
patch = img[bcy - R:bcy + R, bcx - R:bcx + R].astype(np.float32)
lum = cv2.cvtColor(patch.astype(np.uint8), cv2.COLOR_BGR2GRAY).astype(np.float32)
low = cv2.GaussianBlur(lum, (0, 0), br * 0.35)
mean = float(low[R - br // 2:R + br // 2, R - br // 2:R + br // 2].mean())
flat = np.clip(patch * (mean / np.maximum(low, 1))[..., None], 0, 255).astype(np.uint8)
yy2, xx2 = np.mgrid[0:2 * R, 0:2 * R]
dist = np.sqrt((xx2 - R + 0.5) ** 2 + (yy2 - R + 0.5) ** 2)
balpha = np.clip((br + 3 - dist) / 4.0, 0, 1) * 255
save_rgba(os.path.join(OUT, "ball", "ball.png"), flat, balpha)
# shading: darker where low < mean, lighter where above
shade = np.zeros((2 * R, 2 * R, 4), np.float32)
dark = np.clip((mean - low) / mean * 2.2, 0, 0.75)
light = np.clip((low - mean) / mean * 2.0, 0, 0.5)
shade[..., :3] = np.where((light > dark)[..., None], 255, 30)
shade[..., 3] = np.maximum(dark, light) * balpha
cv2.imwrite(os.path.join(OUT, "ball", "shade.png"), shade.astype(np.uint8))
layout["ball"] = {"radius": br, "pad": pad, "restY": bcy}

# ------------------------------------------------------------------ app tiles
TW, TH = 200, 186
# (Brand-logo tiles from the mockup are not exported: each app's own banner is stitched onto
# the blank knitted tile by the launcher at runtime.)
# Blank knitted tile, the same rounded size every app tile uses
TW, TH = 200, 186
def tile_alpha(w, h, r):
    a = np.zeros((h * 4, w * 4), np.uint8)
    cv2.rectangle(a, (r * 4, 0), ((w - r) * 4, h * 4), 255, -1)
    cv2.rectangle(a, (0, r * 4), (w * 4, (h - r) * 4), 255, -1)
    for cx, cy in [(r, r), (w - r, r), (r, h - r), (w - r, h - r)]:
        cv2.circle(a, (cx * 4, cy * 4), r * 4, 255, -1)
    return cv2.resize(a, (w, h), interpolation=cv2.INTER_AREA).astype(np.float32)
TA = tile_alpha(TW, TH, 26)
# Cut from the first mockup tile with its logo rows replaced by clean felt rows
yt = cv2.resize(src[618:781, 48:216], (TW, TH), interpolation=cv2.INTER_LANCZOS4).astype(np.float32)
blank = yt.copy()
top = yt[8:42]             # clean felt rows above the logo
bot = yt[140:174]          # clean felt rows below the logo
fill_rows = np.vstack([top, bot[::-1], top[::-1], bot])
y_a, y_b = 38, 146
seg = fill_rows[:y_b - y_a]
wgt = np.ones((y_b - y_a, 1, 1), np.float32)
r = 8
wgt[:r, 0, 0] = np.linspace(0, 1, r); wgt[-r:, 0, 0] = np.linspace(1, 0, r)
blank[y_a:y_b] = blank[y_a:y_b] * (1 - wgt) + seg * wgt
# keep the original side borders (columns 0..22 and last 22) untouched
blank[:, :22] = yt[:, :22]; blank[:, -22:] = yt[:, -22:]
blank = blank.astype(np.uint8)
save_rgba(os.path.join(OUT, "tiles", "blank.png"), blank, TA)
# Felt multiply texture (white = no change) to knit app icons
g = cv2.cvtColor(blank, cv2.COLOR_BGR2GRAY).astype(np.float32)
g = g / np.percentile(g, 85) * 255
mul = np.clip(g, 0, 255).astype(np.uint8)
cv2.imwrite(os.path.join(OUT, "tiles", "felt.png"), mul)

# Focus glow (warm halo + bright rim), drawn behind the focused tile
GM = 44
gw, gh = TW + 2 * GM, TH + 2 * GM
glow = np.zeros((gh, gw), np.float32)
ring = np.zeros((gh, gw), np.float32)
big = tile_alpha(TW + 12, TH + 12, 30)
glow[GM - 6:GM - 6 + TH + 12, GM - 6:GM - 6 + TW + 12] = big
glow = cv2.GaussianBlur(glow, (0, 0), 14) * 1.6
ring_in = tile_alpha(TW + 8, TH + 8, 29)
ring[GM - 4:GM - 4 + TH + 8, GM - 4:GM - 4 + TW + 8] = ring_in
ring = cv2.GaussianBlur(ring, (0, 0), 2.5)
a = np.clip(glow * 0.85 + ring * 0.0, 0, 255)
g_rgba = np.zeros((gh, gw, 4), np.uint8)
g_rgba[..., 0] = 110; g_rgba[..., 1] = 205; g_rgba[..., 2] = 255   # BGR warm gold
g_rgba[..., 3] = np.clip(a, 0, 255).astype(np.uint8)
cv2.imwrite(os.path.join(OUT, "tiles", "glow.png"), g_rgba)
layout["tile"] = {"w": TW, "h": TH, "glowMargin": GM, "rowTop": sc(619), "rowStart": sc(48), "gap": 38}

json.dump(layout, open(os.path.join(OUT, "layout.json"), "w"), indent=1)
print(json.dumps(layout))

# Debug composite: room + sprites in their places
room = cv2.imread(os.path.join(DBG, "room.png"))
def paste(dst, path, x, y):
    s = cv2.imread(path, cv2.IMREAD_UNCHANGED)
    h, w = s.shape[:2]
    w = min(w, dst.shape[1] - x); h = min(h, dst.shape[0] - y); s = s[:h, :w]
    a = s[..., 3:4].astype(np.float32) / 255
    dst[y:y + h, x:x + w] = (dst[y:y + h, x:x + w] * (1 - a) + s[..., :3] * a).astype(np.uint8)
comp = room.copy()
paste(comp, os.path.join(OUT, "cat", "tail.png"), tx0, ty0)
for _n in ("ear_l", "ear_r", "leg_f", "leg_h"):
    paste(comp, os.path.join(OUT, "cat", _n + ".png"), parts[_n]["x"], parts[_n]["y"])
paste(comp, os.path.join(OUT, "cat", "kitten.png"), x0 if False else layout["kitten"][0], layout["kitten"][1])
x = sc(48)
for i, n in enumerate(["blank"] * 8):
    paste(comp, os.path.join(OUT, "tiles", n + ".png"), x + i * (TW + 38), sc(619))
bimg = cv2.imread(os.path.join(OUT, "ball", "ball.png"), cv2.IMREAD_UNCHANGED)
paste(comp, os.path.join(OUT, "ball", "ball.png"), sc(48) + TW // 2 - R + (TW + 38), bcy - R)
paste(comp, os.path.join(OUT, "ball", "shade.png"), sc(48) + TW // 2 - R + (TW + 38), bcy - R)
cv2.imwrite(os.path.join(DBG, "comp.png"), comp)
flip = room.copy()
kk = cv2.imread(os.path.join(OUT, "cat", "kitten.png"), cv2.IMREAD_UNCHANGED)[:, ::-1]
cv2.imwrite(os.path.join(DBG, "_k.png"), kk)
paste(flip, os.path.join(DBG, "_k.png"), layout["kitten"][0], layout["kitten"][1])
cv2.imwrite(os.path.join(DBG, "flip.png"), flip)

