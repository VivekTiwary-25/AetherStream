"""Cut the truck marker sprite and the info-card photo out of the live-world mockup.

Run from the repo root:
    python design/ui-glorious/sprites/cut_sprites.py
Needs: rembg[cpu], pillow, numpy, scikit-image (rembg pulls these in). rembg does not
build on Python 3.15 yet; a Python 3.12 venv works.
"""
from pathlib import Path

import numpy as np
from PIL import Image
from rembg import new_session, remove
from skimage.measure import label

SRC = Path("design/ui-glorious/liveworld-mockup.png")
OUT = Path("web/public/glorious")
PREV = Path("design/ui-glorious/sprites")

# Truck A, the isolated one right of "Mysuru Palace". The crop includes the ground
# glow ring under the wheels; u2net drops the ring, and the cleanup below removes any leftovers.
TRUCK_BOX = (1370, 325, 1485, 425)
# The photo inside the info card, inset a little so the rounded corners are cut off.
PHOTO_BOX = (449, 260, 584, 396)
MODEL = "u2net"
TARGET_H = 128


def clean_alpha(rgba: Image.Image) -> Image.Image:
    a = np.array(rgba)
    alpha = a[..., 3]
    alpha[alpha < 24] = 0  # remove faint haze
    lab = label(alpha > 0, connectivity=2)
    if lab.max() > 1:  # keep only the biggest blob (the truck)
        sizes = np.bincount(lab.ravel())
        sizes[0] = 0
        alpha[lab != sizes.argmax()] = 0
    a[..., 3] = alpha
    out = Image.fromarray(a, "RGBA")
    return out.crop(out.getbbox())


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    im = Image.open(SRC).convert("RGB")

    truck = remove(im.crop(TRUCK_BOX), session=new_session(MODEL))
    truck = clean_alpha(truck)
    src_w, src_h = truck.size
    print("truck source size", truck.size)

    def scaled(h: int) -> Image.Image:
        return truck.resize((round(src_w * h / src_h), h), Image.LANCZOS)

    t1 = scaled(TARGET_H)
    t1.save(OUT / "truck.png", optimize=True)
    print("truck.png", t1.size)
    if src_h >= TARGET_H * 2:
        scaled(TARGET_H * 2).save(OUT / "truck@2x.png", optimize=True)
        print("truck@2x.png written")
    else:
        print(f"truck@2x.png skipped: source is only {src_h}px tall")

    for name, color in (("light", "#f3efe6"), ("green", "#9bd3a0")):
        bg = Image.new("RGBA", (t1.width + 40, t1.height + 40), color)
        bg.alpha_composite(t1, (20, 20))
        bg.convert("RGB").save(PREV / f"preview-{name}.png")

    photo = im.crop(PHOTO_BOX)
    photo.save(OUT / "truck-photo.jpg", quality=85)
    print("truck-photo.jpg", photo.size)


if __name__ == "__main__":
    main()
