#!/usr/bin/env python3
"""Optically center the launcher foreground artwork in its canvas.

The foreground webp's bounding box is geometrically centered, but the Todosian glyph is
top-heavy: its alpha-weighted centroid (visual mass) sits noticeably above the canvas
center, which makes the icon read as floating high on the background. This script shifts
the artwork in each density's ic_launcher_foreground.webp so the centroid lands on the
canvas center, then saves losslessly.

Usage: python3 scripts/center_launcher_icon_foreground.py
"""

from pathlib import Path

from PIL import Image

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"


def main() -> None:
    files = sorted(RES.glob("mipmap-*/ic_launcher_foreground.webp"))
    if not files:
        raise SystemExit("No launcher foreground webp files found")

    for path in files:
        img = Image.open(path).convert("RGBA")
        w, h = img.size

        total = cx = cy = 0
        for y in range(h):
            for x in range(w):
                a = img.getpixel((x, y))[3]
                if a > 0:
                    total += a
                    cx += x * a
                    cy += y * a
        if total == 0:
            print(f"{path.parent.name}: empty, skipped")
            continue

        center_x, center_y = (w - 1) / 2, (h - 1) / 2
        dx = round(center_x - cx / total)
        dy = round(center_y - cy / total)

        if dx == 0 and dy == 0:
            print(f"{path.parent.name}: already optically centered")
            continue

        # Guard: the shifted artwork must stay inside the canvas.
        alpha_bbox = img.getchannel("A").getbbox()
        shifted = (
            alpha_bbox[0] + dx,
            alpha_bbox[1] + dy,
            alpha_bbox[2] + dx,
            alpha_bbox[3] + dy,
        )
        if shifted[0] < 0 or shifted[1] < 0 or shifted[2] >= w or shifted[3] >= h:
            raise SystemExit(f"{path.parent.name}: shift {dx},{dy} would clip the artwork")

        centered = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        centered.paste(img, (dx, dy), img)
        centered.save(path, lossless=True)
        print(
            f"{path.parent.name}: shifted by ({dx:+d},{dy:+d}) px "
            f"(centroid offset was ({center_x - cx / total:+.1f},{center_y - cy / total:+.1f}))"
        )


if __name__ == "__main__":
    main()
