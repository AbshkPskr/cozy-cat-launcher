# Art pipeline

Everything you see in the launcher is cut from one picture, `mockup.png` (1672×941), by two
Python scripts. The results land in `app/src/main/assets/`.

```bash
pip install opencv-contrib-python-headless numpy pillow
python build_assets.py    # empty room: kitten, ball, top bar and app row removed and filled in
python build_sprites.py   # kitten parts, eyes, blink, sleeping kitten, ball, knitted tile, glow
```

- `dbg_kmask.png` is the kitten's outline, made with `cut_kitten.py` (GrabCut). If you use a
  different picture, re-make it with `cut_kitten.py` and adjust the coordinates in the scripts.
- `build_sprites.py` writes `assets/layout.json`. Copy its positions into the `Art` object in
  `SceneAssets.kt` if they change.
- `patchfill.py` fills holes by copying real texture from nearby, so filled areas stay sharp
  instead of smeared.
- `sim.py` renders a preview GIF of the motion on your computer, and `posetest.py` checks the joints
  of the kitten's parts. Both write to `dbg/`.

Using your own picture works best when it has the same layout: the room as background, one main
character in the middle facing left, a ball near its front paw, and nothing important along the
bottom quarter, where the app row sits.
