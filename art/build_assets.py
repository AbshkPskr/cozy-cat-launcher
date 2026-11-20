"""Builds every launcher asset from mockup.png (1672x941) at 1920x1080 scale.

Outputs (in ../app/src/main/assets/):
  bg/room.webp                 empty room (no kitten, ball, top bar or app tiles)
  cat/kitten.png               main kitten (body, without upper tail), facing left
  cat/tail.png                 upper tail, rotates around its base
  cat/blink.png                closed-eyes overlay for the kitten
  cat/sleeper.png              sleeping kitten patch (breathes in place)
  ball/ball.png                wool ball texture (shading removed, rotates)
  ball/shade.png               fixed ball shading overlay
  tiles/<name>.png             knitted app tiles cut from the mockup
  tiles/blank.png              blank knitted tile for other apps
  tiles/glow.png               warm focus glow
plus layout.json with the positions the app needs.
"""
import cv2, numpy as np, os, json
D = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(D, "..", "app", "src", "main", "assets")
DBG = os.path.join(D, "dbg")
os.makedirs(DBG, exist_ok=True)
for sub in ("bg", "cat", "ball", "tiles"):
    os.makedirs(os.path.join(OUT, sub), exist_ok=True)

src = cv2.imread(os.path.join(D, "mockup.png"))
H0, W0 = src.shape[:2]
W, H = 1920, 1080
S = W / W0
img = cv2.resize(src, (W, H), interpolation=cv2.INTER_LANCZOS4)
# Very light unsharp mask to offset the upscale
blur = cv2.GaussianBlur(img, (0, 0), 1.2)
img = cv2.addWeighted(img, 1.25, blur, -0.25, 0)

def sc(v): return int(round(v * S))

layout = {"screen": [W, H]}

# ---------------------------------------------------------------- kitten mask
kmask0 = cv2.imread(os.path.join(D, "dbg_kmask.png"), 0)
kmask = cv2.resize(kmask0, (W, H), interpolation=cv2.INTER_LINEAR)
kmask = cv2.GaussianBlur(kmask, (0, 0), 1.0)
khard = (kmask > 127).astype(np.uint8) * 255

# ---------------------------------------------------------------- removal mask
rm = np.zeros((H, W), np.uint8)
rm |= cv2.dilate(khard, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (51, 51)))
# the tail's fine hairs reach further: wider clean-up around the tail (wall only, left of the post)
_tw = cv2.dilate(khard, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (91, 91)))
rm[:sc(330), sc(900):sc(1140)] |= _tw[:sc(330), sc(900):sc(1140)]
# stray glints left on the wall right of the kitten's back
cv2.circle(rm, (1090, 290), 14, 255, -1); cv2.circle(rm, (1330, 340), 12, 255, -1)
# whiskers on the left of the face
cv2.rectangle(rm, (sc(595), sc(360)), (sc(700), sc(480)), 255, -1)
# keep the shelf post on the right: only remove the kitten itself there
# Right of the kitten's back (shelf post, cat bed): remove only the kitten itself + a hair,
# so the post and the bed keep their real, sharp pixels.
post_x = sc(1140)
tight = cv2.dilate(khard, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (9, 9)))
rm[:sc(560), post_x:] = tight[:sc(560), post_x:]
wide = cv2.dilate(khard, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (25, 25)))
wall_strip = slice(post_x, sc(1196))
rm[:500, post_x:1372] = cv2.dilate(khard, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (51, 51)))[:500, post_x:1372]   # glow halo, up to the post
rm[500:640, 1290:1366] = 255   # bed-end area, rebuilt below
cv2.rectangle(rm, (sc(880), sc(400)), (sc(960), sc(470)), 255, -1)
# ball + its motion streaks
cv2.circle(rm, (sc(534), sc(553)), sc(78), 255, -1)
cv2.ellipse(rm, (sc(470), sc(540)), (sc(90), sc(70)), 0, 180, 300, 255, sc(18))
cv2.rectangle(rm, (sc(395), sc(470)), (sc(480), sc(600)), 255, -1)
# app tile band (cleaned separately but include in mask)
band = np.zeros((H, W), np.uint8)
band[668:sc(806), :] = 255
cv2.rectangle(rm, (sc(205), sc(568)), (sc(492), 668), 255, -1)   # Netflix glow above the band
# top bar elements
tb = np.zeros((H, W), np.uint8)
for (x0, y0, x1, y1) in [(50, 82, 132, 160), (168, 78, 366, 164), (400, 100, 482, 145), (536, 100, 642, 145),
                         (690, 95, 702, 148), (746, 96, 900, 148), (1360, 92, 1424, 150), (1460, 92, 1522, 150),
                         (1555, 86, 1626, 156)]:
    cv2.rectangle(tb, (sc(x0), sc(y0)), (sc(x1), sc(y1)), 255, -1)

# ---------------------------------------------------------------- fill helpers
def lowfreq_fill(image, mask, factor=12):
    small = cv2.resize(image, (W // factor, H // factor), interpolation=cv2.INTER_AREA)
    msmall = cv2.resize(mask, (W // factor, H // factor), interpolation=cv2.INTER_NEAREST)
    msmall = cv2.dilate(msmall, np.ones((3, 3), np.uint8))
    filled = cv2.inpaint(small, msmall, 6, cv2.INPAINT_TELEA)
    filled = cv2.GaussianBlur(filled, (0, 0), 2.0)
    return cv2.resize(filled, (W, H), interpolation=cv2.INTER_CUBIC)

def highpass(patch, sigma=6):
    p = patch.astype(np.float32)
    return p - cv2.GaussianBlur(p, (0, 0), sigma)

def tile_texture(hp, w, h, seed=0):
    """Tile a high-pass texture patch to (h, w) with random mirrored offsets to hide repeats."""
    rng = np.random.default_rng(seed)
    ph, pw = hp.shape[:2]
    out = np.zeros((h, w, 3), np.float32)
    for y in range(0, h, ph // 2):
        for x in range(0, w, pw // 2):
            p = hp
            if rng.random() < 0.5: p = p[:, ::-1]
            if rng.random() < 0.5: p = p[::-1, :]
            hh = min(ph, h - y); ww = min(pw, w - x)
            # feathered paste
            wgt = np.ones((ph, pw), np.float32)
            r = 12
            ramp = np.linspace(0, 1, r)
            wgt[:r, :] *= ramp[:, None]; wgt[-r:, :] *= ramp[::-1, None]
            wgt[:, :r] *= ramp[None, :]; wgt[:, -r:] *= ramp[None, ::-1]
            out[y:y + hh, x:x + ww] = out[y:y + hh, x:x + ww] * (1 - wgt[:hh, :ww, None]) + p[:hh, :ww] * wgt[:hh, :ww, None]
    return out

floor_y = sc(560)   # where the wall meets the carpet in the mockup
rng = np.random.default_rng(3)

def grain(h, w, amp, sx, sy, seed):
    """Procedural fuzz: filtered noise, seamless, no repeating pattern."""
    r = np.random.default_rng(seed)
    n = r.normal(0, 1, (h, w)).astype(np.float32)
    n = cv2.GaussianBlur(n, (0, 0), sigmaX=sx, sigmaY=sy)
    n = n / (n.std() + 1e-6) * amp
    return np.repeat(n[..., None], 3, axis=2)

# Grain amplitudes measured from clean patches
def hp_std(patch, sigma=3):
    p = cv2.cvtColor(patch, cv2.COLOR_BGR2GRAY).astype(np.float32)
    return float((p - cv2.GaussianBlur(p, (0, 0), sigma)).std())
wall_amp = hp_std(img[sc(150):sc(200), sc(605):sc(650)])
carpet_amp = hp_std(img[sc(566):sc(592), sc(1220):sc(1450)])
print("grain", wall_amp, carpet_amp)
WALL_TEX = grain(H, W, wall_amp, 1.0, 1.0, 11)
CARPET_TEX = grain(H, W, carpet_amp * 0.9, 1.6, 0.9, 12) + grain(H, W, carpet_amp * 0.5, 0.7, 0.7, 13)

def membrane_fill(image, mask):
    """Solve Laplace's equation inside the mask (boundary = surrounding pixels), coarse-to-fine
    so even big holes converge: smooth, no smears, meets the edges exactly."""
    img_f = image.astype(np.float32)
    u = None
    for factor, iters in ((24, 600), (12, 500), (6, 500), (3, 600)):
        h, w = H // factor, W // factor
        small = cv2.resize(img_f, (w, h), interpolation=cv2.INTER_AREA)
        ms = cv2.resize(mask, (w, h), interpolation=cv2.INTER_NEAREST) > 0
        ms = cv2.dilate(ms.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
        if u is None:
            u = cv2.inpaint(small.astype(np.uint8), ms.astype(np.uint8) * 255, 3, cv2.INPAINT_TELEA).astype(np.float32)
        else:
            u = cv2.resize(u, (w, h), interpolation=cv2.INTER_LINEAR)
        known = ~ms
        u = np.where(known[..., None], small, u)
        for _ in range(iters):
            avg = (np.roll(u, 1, 0) + np.roll(u, -1, 0) + np.roll(u, 1, 1) + np.roll(u, -1, 1)) * 0.25
            u = np.where(known[..., None], small, avg)
    return cv2.resize(u, (W, H), interpolation=cv2.INTER_CUBIC)

def clean(image, mask):
    base = membrane_fill(image, mask)
    yy = np.arange(H)[:, None, None].astype(np.float32)
    carpet_w = np.clip((yy - (floor_y - 8)) / 16.0, 0, 1)
    tex = CARPET_TEX * carpet_w + WALL_TEX * (1 - carpet_w)
    fill = np.clip(base + tex, 0, 255)
    m = cv2.GaussianBlur(mask.astype(np.float32) / 255.0, (0, 0), 2)[..., None]
    return (image.astype(np.float32) * (1 - m) + fill * m).astype(np.uint8)

room = img.copy()
import sys as _sys; _sys.path.insert(0, D)
from patchfill import shiftmap_fill
tb_left = tb.copy(); tb_left[:, 1300:] = 0
# right icons (cast, gear, avatar): mask only the icon shapes themselves, not their boxes
tb_right = np.zeros_like(tb)
hsv_i = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
for (x0_, y0_, x1_, y1_) in [(1360, 92, 1424, 150), (1460, 92, 1522, 150)]:
    X0_, Y0_, X1_, Y1_ = sc(x0_), sc(y0_), sc(x1_), sc(y1_)
    v_ = hsv_i[Y0_:Y1_, X0_:X1_, 2].astype(np.float32); s_ = hsv_i[Y0_:Y1_, X0_:X1_, 1].astype(np.float32)
    bg = cv2.medianBlur(img[Y0_:Y1_, X0_:X1_], 21)
    bv = cv2.cvtColor(bg, cv2.COLOR_BGR2HSV)[..., 2].astype(np.float32)
    glyph = ((v_ - bv) > 18) & (s_ < 90)
    tb_right[Y0_:Y1_, X0_:X1_] = glyph.astype(np.uint8) * 255
discs = np.zeros_like(tb)
cv2.circle(discs, (sc(1590.5), sc(121)), sc(37), 255, -1)          # avatar disc
cv2.circle(discs, (sc(1491), sc(121)), sc(30), 255, -1)            # gear
tb_right[discs > 0] = 0
tb_right = cv2.dilate(tb_right, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7)))
room = shiftmap_fill(room, tb_left)
def rowwise_fill(image, mask, grain_amp=1.5):
    out = image.astype(np.float32).copy()
    xs = np.arange(W)
    ys = np.where(mask.any(axis=1))[0]
    for yv in ys:
        hr = mask[yv] > 0; good = ~hr
        for c in range(3):
            out[yv, hr, c] = np.interp(xs[hr], xs[good], out[yv, good, c])
    out = cv2.GaussianBlur(out, (0, 0), 0.6) * (mask[..., None] > 0) + out * (mask[..., None] == 0)
    n = np.random.default_rng(4).normal(0, grain_amp, out.shape[:2]).astype(np.float32)[..., None]
    out += n * (mask[..., None] > 0)
    return np.clip(out, 0, 255).astype(np.uint8)
room = shiftmap_fill(room, tb_right, pad=40)
# gear + avatar sit on a smooth shelf edge and wall: rebuild them row by row (keeps the shelf line)
room = rowwise_fill(room, cv2.dilate(discs, np.ones((5, 5), np.uint8)), grain_amp=1.2)
room = clean(room, rm | band)

# Floor strip + far carpet edge (y 598-676): horizontally uniform, so rebuild it row by row
# between clean real pixels left of the ball (x<450) and right of the kitten (x>=1300).
F0, F1 = 598, 676
FX0, FX1 = 450, 1366
src_rows = cv2.GaussianBlur(img, (0, 0), 1.0).astype(np.float32)
floor = room.astype(np.float32).copy()
t = np.linspace(0, 1, FX1 - FX0)[None, :, None]
floor[F0:F1, FX0:FX1] = src_rows[F0:F1, FX0 - 1:FX0][:, :, :] * (1 - t) + src_rows[F0:F1, FX1:FX1 + 1] * t
floor[F0:F1, FX0:FX1] += CARPET_TEX[F0:F1, FX0:FX1] * np.linspace(0.25, 1.0, F1 - F0)[:, None, None]
wy = np.clip((np.arange(H) - F0) / 12.0, 0, 1) * np.clip((F1 + 6 - np.arange(H)) / 10.0, 0, 1)
wx = np.zeros(W, np.float32); wx[FX0:FX1] = 1
wx = cv2.GaussianBlur(wx.reshape(1, -1), (0, 0), 6).ravel()
wfl = (wy[:, None] * wx[None, :])[..., None]
room = np.clip(room.astype(np.float32) * (1 - wfl) + floor * wfl, 0, 255).astype(np.uint8)

# Carpet strip under the app row: rebuilt from clean carpet colours above and below,
# with grain that softens with depth (the original has depth-of-field blur toward the bottom).
y0, y1 = 668, sc(806)
def clean_row_color(ya, yb, exclude):
    rows = img[ya:yb].astype(np.float32)
    ok = (exclude[ya:yb] == 0)
    hsv = cv2.cvtColor(img[ya:yb], cv2.COLOR_BGR2HSV)
    ok &= hsv[..., 1] < 90
    col = np.full((W, 3), np.nan, np.float32)
    good = ok.sum(axis=0)
    for c in range(3):
        col[:, c] = np.where(good > 0, (rows[..., c] * ok).sum(axis=0) / np.maximum(good, 1), np.nan)
    x = np.arange(W)
    for c in range(3):
        v = col[:, c]; bad = np.isnan(v)
        v[bad] = np.interp(x[bad], x[~bad], v[~bad])
        col[:, c] = cv2.GaussianBlur(v.reshape(1, -1), (0, 0), 80).ravel()
    return col
excl = cv2.dilate(rm, np.ones((41, 41), np.uint8))
col_top = clean_row_color(668, 676, excl)
col_bot = clean_row_color(sc(806), sc(816), np.zeros_like(rm))
grad = np.zeros((y1 - y0, W, 3), np.float32)
for i in range(y1 - y0):
    t = i / (y1 - y0 - 1)
    t = t * t * (3 - 2 * t)
    grad[i] = col_top * (1 - t) + col_bot * t
amp = np.linspace(1.0, 0.25, y1 - y0)[:, None, None]
newband = np.clip(grad + CARPET_TEX[y0:y1] * amp, 0, 255)
m = np.zeros((H, W), np.float32)
m[y0 + 4:y1 - 30] = 1
# fuzzy pile edge: wobble the top boundary a few px
wob = cv2.GaussianBlur(np.random.default_rng(21).normal(0, 1, (1, W)).astype(np.float32), (0, 0), 2.5).ravel()
wob = wob / (np.abs(wob).max() + 1e-6) * 5
for xx in range(W):
    m[: int(y0 + 4 + wob[xx]), xx] = 0
m = cv2.GaussianBlur(m, (0, 0), 5.0)
ramp = np.clip((y1 - np.arange(H)) / 45.0, 0, 1)
m[y1 - 50:y1 + 10] = np.maximum(m[y1 - 50:y1 + 10], 0) * 0 + ramp[y1 - 50:y1 + 10, None]
m[:y0] = 0
m = np.maximum(m, (cv2.GaussianBlur(rm.astype(np.float32) / 255, (0, 0), 3) * ((np.arange(H) >= y0) & (np.arange(H) < y1))[:, None]))[..., None]
full = room.astype(np.float32)
full[y0:y1] = newband
room = (room.astype(np.float32) * (1 - m) + full * m).astype(np.uint8)

# ---- Wall between the kitten's back and the shelf post: one smooth surface up to the post ----
Z = np.zeros((H, W), np.uint8)
Z[150:500, 860:1374] = 255
Z[500:604, 860:1362] = 255
zfill = membrane_fill(room, Z) + WALL_TEX
zm = cv2.GaussianBlur(Z.astype(np.float32) / 255, (0, 0), 2.5)[..., None]
room = np.clip(room.astype(np.float32) * (1 - zm) + zfill * zm, 0, 255).astype(np.uint8)

# ---- Rebuild the left end of the cat bed that the kitten's hind leg hid (screen px) ----
# Mirror the real bed about the column where it first appears (a mirror is seamless at its axis),
# then give it one rounded silhouette: cushion end + rim, matching the bed's own corners.
AX = 1362                      # first clean bed column right of the kitten's fur
EXT = 28                       # bed extended this far to the left (mirror source stays clear of the paw)
BT, BB = 500, 622
mir = img[BT:BB, AX:AX + EXT][:, ::-1].astype(np.float32)
SS = 4
def S_(v): return int(v * SS)
A = np.zeros(((BB - BT) * SS, EXT * SS), np.uint8)
RIM_T = 8
mid = (RIM_T + (BB - BT)) / 2
cv2.ellipse(A, (S_(EXT), S_(mid)), (S_(EXT - 1), S_((BB - BT - RIM_T) / 2)), 0, 90, 270, 255, -1, cv2.LINE_AA)
alpha = cv2.resize(A, (EXT, BB - BT), interpolation=cv2.INTER_AREA).astype(np.float32) / 255
alpha = cv2.GaussianBlur(alpha, (0, 0), 1.0)
mir *= (0.92 + 0.08 * np.linspace(0, 1, EXT))[None, :, None]
dst = room[BT:BB, AX - EXT:AX].astype(np.float32)
room[BT:BB, AX - EXT:AX] = (dst * (1 - alpha[..., None]) + mir * alpha[..., None]).astype(np.uint8)
# real bed from AX on: put back the original pixels (no fur, no fill)
room[BT:BB, AX:AX + 30] = img[BT:BB, AX:AX + 30]
# soft contact shadow under the new end
sh = np.zeros((16, EXT), np.float32); sh[4:8, 8:] = 0.22
sh = cv2.GaussianBlur(sh, (0, 0), 2.5)[..., None]
dd = room[BB - 4:BB + 12, AX - EXT:AX].astype(np.float32)
room[BB - 4:BB + 12, AX - EXT:AX] = (dd * (1 - sh)).astype(np.uint8)

cv2.imwrite(os.path.join(DBG, "room.png"), room)
cv2.imwrite(os.path.join(OUT, "bg", "room.webp"), room, [cv2.IMWRITE_WEBP_QUALITY, 95])
print("room done")
