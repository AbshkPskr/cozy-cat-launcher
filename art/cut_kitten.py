"""Step 1: segment the main kitten with GrabCut and save debug views."""
import cv2, numpy as np, os
D = os.path.dirname(os.path.abspath(__file__))
img = cv2.imread(os.path.join(D, "mockup.png"))
H, W = img.shape[:2]

mask = np.full((H, W), cv2.GC_BGD, np.uint8)
# Probable foreground: generous box around kitten (tail tip up to y~90)
mask[85:600, 565:1205] = cv2.GC_PR_BGD
# Kitten body polygon as probable foreground
body = np.array([[600, 470], [640, 300], [700, 230], [800, 225], [900, 260], [1000, 280], [1080, 300],
                 [1130, 330], [1180, 420], [1185, 560], [1100, 590], [760, 590], [720, 560], [600, 520]])
cv2.fillPoly(mask, [body], cv2.GC_PR_FGD)
# Tail
tail = np.array([[1045, 120], [1080, 95], [1150, 100], [1190, 170], [1185, 260], [1150, 330], [1110, 320], [1100, 200]])
cv2.fillPoly(mask, [tail], cv2.GC_PR_FGD)
# Sure foreground cores
cv2.ellipse(mask, (790, 380), (120, 110), 0, 0, 360, cv2.GC_FGD, -1)        # head
cv2.ellipse(mask, (980, 450), (150, 90), 0, 0, 360, cv2.GC_FGD, -1)        # body
cv2.ellipse(mask, (1140, 220), (25, 70), 15, 0, 360, cv2.GC_FGD, -1)       # tail
# Sure background: the ball and the cat bed on the right
cv2.circle(mask, (534, 553), 66, cv2.GC_BGD, -1)
mask[300:600, 1195:] = cv2.GC_BGD

bgd = np.zeros((1, 65), np.float64); fgd = np.zeros((1, 65), np.float64)
cv2.grabCut(img, mask, None, bgd, fgd, 8, cv2.GC_INIT_WITH_MASK)
fg = np.where((mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD), 255, 0).astype(np.uint8)
# Keep the largest component, fill holes, smooth edge
n, lab, stats, _ = cv2.connectedComponentsWithStats(fg)
big = 1 + np.argmax(stats[1:, cv2.CC_STAT_AREA])
fg = np.where(lab == big, 255, 0).astype(np.uint8)
cnts, _ = cv2.findContours(fg, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
fg = np.zeros_like(fg); cv2.drawContours(fg, cnts, -1, 255, -1)
fg = cv2.morphologyEx(fg, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (9, 9)))
cv2.imwrite(os.path.join(D, "dbg_kmask.png"), fg)

vis = img.copy()
vis[fg == 0] = (vis[fg == 0] * 0.25 + np.array([0, 255, 0]) * 0.35).astype(np.uint8)
cv2.imwrite(os.path.join(D, "dbg_kvis.png"), vis[60:620, 520:1240])
print("done", fg.sum() // 255)
