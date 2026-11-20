"""Offline preview of the launcher animation (same sprites, positions and motion maths as
KittenScene.kt) rendered to a GIF, so motion can be reviewed frame by frame.

usage: python -I sim.py out.gif
"""
import cv2, numpy as np, json, os, sys, math
from PIL import Image

D = os.path.dirname(os.path.abspath(__file__))
A = os.path.join(D, "..", "app", "src", "main", "assets")
L = json.load(open(os.path.join(A, "layout.json")))
P = L["parts"]
FPS = 25
SCALE = 0.5

def load(p):
    im = cv2.imread(os.path.join(A, p), cv2.IMREAD_UNCHANGED)
    if im.shape[2] == 3:
        im = np.dstack([im, np.full(im.shape[:2], 255, np.uint8)])
    return im.astype(np.float32)
room = cv2.resize(cv2.imread(os.path.join(A, "bg", "room.webp")), None, fx=SCALE, fy=SCALE, interpolation=cv2.INTER_AREA).astype(np.float32)
S = {n: load("cat/%s.png" % n) for n in ["kitten", "tail", "ear_l", "ear_r", "leg_f", "leg_h", "blink", "sleeper"]}
ball = load("ball/ball.png"); shade = load("ball/shade.png")
tiles = [load("tiles/blank.png") for _ in range(8)]
glow = load("tiles/glow.png")

FLIP_X, FEET_Y, PAW_TIP = 1006.0, 672.0, -331.0
BALL_R, BALL_Y = 70.0, 636.0
TW, TH, ROW_TOP, ROW_START, GAP = 200, 186, 711, 55, 38

def affine(M_parts):
    M = np.eye(3)
    for m in M_parts: M = M @ m
    return M
def T(x, y): return np.array([[1, 0, x], [0, 1, y], [0, 0, 1]], float)
def Sc(sx, sy, px, py): return T(px, py) @ np.array([[sx, 0, 0], [0, sy, 0], [0, 0, 1]], float) @ T(-px, -py)
def R(deg, px, py):
    a = math.radians(deg); c, s = math.cos(a), math.sin(a)
    return T(px, py) @ np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]], float) @ T(-px, -py)

def blit(dst, img, M, x, y, alpha=1.0):
    """Draw img (placed at x,y in art space) through 3x3 transform M."""
    M2 = np.diag([SCALE, SCALE, 1.0]) @ M @ T(x, y)
    warped = cv2.warpAffine(img, M2[:2], (dst.shape[1], dst.shape[0]), flags=cv2.INTER_LINEAR, borderValue=(0, 0, 0, 0))
    a = warped[..., 3:4] / 255.0 * alpha
    dst *= (1 - a); dst += warped[..., :3] * a

class Spring:
    """Critically-ish damped spring like Compose spring(dampingRatio, stiffness)."""
    def __init__(self, v, damping, stiffness):
        self.x = v; self.v = 0.0; self.target = v; self.z = damping; self.k = stiffness
    def step(self, dt):
        for _ in range(4):
            h = dt / 4
            w = math.sqrt(self.k)
            acc = -self.k * (self.x - self.target) - 2 * self.z * w * self.v
            self.v += acc * h; self.x += self.v * h

def tile_x(i): return ROW_START + i * (TW + GAP) + TW / 2

def run(script, seconds, out):
    ballx = Spring(tile_x(0), 0.8, 60); ballx.x = tile_x(0)
    walk = Spring(0.0, 0.92, 26)
    facing, from_f, turn_t = 1.0, 1.0, 1.0
    focus = 0; last = tile_x(0); pending_walk = None
    frames = []
    t = 0.0; dt = 1.0 / FPS
    # place kitten at start
    walk.x = walk.target = max(-470, min(500, tile_x(0) + BALL_R * 0.85 - PAW_TIP - FLIP_X))
    events = sorted(script)
    while t < seconds:
        while events and events[0][0] <= t:
            _, key = events.pop(0)
            focus = max(0, min(7, focus + (1 if key == "R" else -1)))
            bx = tile_x(focus)
            d = 1 if bx > last else -1
            last = bx
            ballx.target = bx
            want = 1.0 if d < 0 else -1.0
            tf = lambda f: bx + BALL_R * 0.85 - PAW_TIP - FLIP_X if f > 0 else bx - BALL_R * 0.85 + PAW_TIP - FLIP_X
            fits = lambda f: -500 <= tf(f) <= 530
            if not fits(want) and fits(-want): want = -want
            delay = 0.0
            if want != facing:
                from_f, facing, turn_t = facing, want, 0.0
                delay = 0.20
            tgt = bx + BALL_R * 0.85 - PAW_TIP - FLIP_X if facing > 0 else bx - BALL_R * 0.85 + PAW_TIP - FLIP_X
            pending_walk = (t + delay, max(-470, min(500, tgt)))
        if pending_walk and t >= pending_walk[0]:
            walk.target = pending_walk[1]; pending_walk = None
        ballx.step(dt); walk.step(dt)
        turn_t = min(1.0, turn_t + dt / 0.30)
        frames.append(render(t, ballx, walk, facing, from_f, turn_t, focus))
        t += dt
    imgs = [Image.fromarray(cv2.cvtColor(f, cv2.COLOR_BGR2RGB)) for f in frames]
    imgs[0].save(out, save_all=True, append_images=imgs[1:], duration=int(1000 / FPS), loop=0, optimize=False)
    return frames

def render(t, ballx, walk, facing, from_f, turn_t, focus):
    c = room.copy()
    br = math.sin(t * 2 * math.pi / 4.2)
    blit(c, S["sleeper"], Sc(1 + br * 0.006, 1 + br * 0.018, L["sleeper"][0] + 160, L["sleeper"][1] + 200), L["sleeper"][0], L["sleeper"][1])
    wx = walk.x
    moving = min(1.0, abs(walk.v) / 250.0)
    phase = wx / 95.0
    step = math.sin(phase * math.pi)
    gait_hop = abs(step) * 14 * moving
    rock = math.sin(phase * 2 * math.pi) * 2.2 * moving
    liftF = max(step, 0) * 18 * moving
    liftH = max(-step, 0) * 16 * moving
    breath = math.sin(t * 2 * math.pi / 3.2)
    turn_hop = math.sin(turn_t * math.pi)
    mix = min(1, max(0, (turn_t - 0.38) / 0.24))
    sway = math.sin(t * 1.4) * 3 + math.sin(t * 2.7) * 1 + math.sin(phase * 2 * math.pi) * 4 * moving
    hop = turn_hop * 30 + gait_hop
    tipX = FLIP_X + wx + (280 if facing > 0 else -280)
    tail_low = 0.0

    def kitten(face, alpha):
        if alpha < 0.01: return
        narrow = 1.0
        M = affine([T(wx, -hop), Sc(face * narrow, 1, FLIP_X, FEET_Y),
                    Sc(1, 1 + breath * 0.007 + turn_hop * 0.05 + abs(step) * 0.025 * moving, FLIP_X, FEET_Y), R(rock, FLIP_X + 200, FEET_Y)])
        blit(c, S["tail"], M @ R(sway + tail_low * 50, P["tail"]["px"], P["tail"]["py"]), P["tail"]["x"], P["tail"]["y"], alpha)
        blit(c, S["kitten"], M, L["kitten"][0], L["kitten"][1], alpha)
        blit(c, S["leg_f"], M, P["leg_f"]["x"], P["leg_f"]["y"] - liftF, alpha)
        blit(c, S["leg_h"], M, P["leg_h"]["x"], P["leg_h"]["y"] - liftH, alpha)
        e = math.sin(phase * 2 * math.pi) * 3 * moving
        blit(c, S["ear_l"], M @ R(e, P["ear_l"]["px"], P["ear_l"]["py"]), P["ear_l"]["x"], P["ear_l"]["y"], alpha)
        blit(c, S["ear_r"], M @ R(-e, P["ear_r"]["px"], P["ear_r"]["py"]), P["ear_r"]["x"], P["ear_r"]["y"], alpha)
    if turn_t >= 1 or from_f == facing:
        kitten(facing, 1)
    else:
        e = turn_t * turn_t * (3 - 2 * turn_t)
        cth = math.cos(e * math.pi)
        sx = (1 if cth >= 0 else -1) * max(abs(cth), 0.45)
        kitten(from_f * sx, 1)
    # ball
    cx = ballx.x; r = BALL_R
    half = r + 6
    blit(c, ball, R(math.degrees(cx / r), cx, BALL_Y), cx - half, BALL_Y - half)
    blit(c, shade, np.eye(3), cx - half, BALL_Y - half)
    # tiles
    for i, tl in enumerate(tiles):
        x = ROW_START + i * (TW + GAP)
        if i == focus:
            blit(c, glow, Sc(1.18, 1.18, x + TW / 2, ROW_TOP + TH / 2), x - 44, ROW_TOP - 44)
            blit(c, tl, Sc(1.18, 1.18, x + TW / 2, ROW_TOP + TH / 2), x, ROW_TOP)
        else:
            blit(c, tl, np.eye(3), x, ROW_TOP)
    out = np.clip(c, 0, 255).astype(np.uint8)
    return out

if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(D, "dbg", "sim.gif")
    script = [(0.4, "R"), (1.2, "R"), (2.0, "R"), (3.6, "R"), (4.6, "L"), (6.0, "L")]
    frames = run(script, 7.5, out)
    # contact sheet of every 4th frame for quick review
    sel = frames[::4][:40]
    rows = [np.hstack(sel[i:i + 5]) for i in range(0, len(sel) - len(sel) % 5, 5)]
    cv2.imwrite(os.path.join(D, "dbg", "sim_sheet.png"), cv2.resize(np.vstack(rows), None, fx=0.5, fy=0.5))
    print("frames", len(frames), out)
