#!/usr/bin/env python3
"""Recolor the launcher icon webp rasters in place.

Every launcher pixel is either the foreground color, the background color, or an
anti-aliased blend of the two (or of the foreground with transparency on the outer
edge). The script auto-detects the two dominant opaque colors of each image, treats
them as the old foreground/background, and remaps every pixel so that its *blend
factor* is preserved with the new colors — edges stay perfectly smooth, no halos.

Usage: python3 scripts/recolor_launcher_icons.py <new_fg_hex> <new_bg_hex>
Example: python3 scripts/recolor_launcher_icons.py e03829 000000
"""

import sys
from collections import Counter
from pathlib import Path

from PIL import Image

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
# Lossy webp encoding pushes anti-aliased edge pixels off the exact blend line; a generous
# tolerance still separates them from any genuinely different design color.
BLEND_TOLERANCE = 100.0


def hex_to_rgb(value: str) -> tuple[int, int, int]:
    value = value.lstrip("#")
    return tuple(int(value[i : i + 2], 16) for i in (0, 2, 4))


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    new_fg = hex_to_rgb(sys.argv[1])
    new_bg = hex_to_rgb(sys.argv[2])

    files = sorted(RES.glob("mipmap-*/ic_launcher*.webp"))
    if not files:
        raise SystemExit("No launcher webp files found")

    for path in files:
        img = Image.open(path).convert("RGBA")
        pixels = list(img.getdata())

        # Auto-detect the old colors from the image itself.
        opaque = Counter(px[:3] for px in pixels if px[3] == 255)
        if not opaque:
            print(f"{path.name} [{path.parent.name}]: fully transparent, skipped")
            continue
        ranked = [color for color, _ in opaque.most_common(2)]
        if len(ranked) == 1:
            old_bg, old_fg = ranked[0], ranked[0]
        else:
            old_bg, old_fg = ranked[0], ranked[1]

        # The standalone foreground webp is definitionally a single-color glyph with
        # anti-aliased alpha: straight alpha keeps RGB constant, so remapping every opaque
        # pixel's RGB to the new foreground preserves every edge. (Stray lossy-artifact
        # pixels get cleaned up in the process.)
        is_foreground_only = path.stem == "ic_launcher_foreground"

        mapped = unmapped = 0
        out = []
        for r, g, b, a in pixels:
            if is_foreground_only:
                if a > 0:
                    out.append((*new_fg, a))
                    mapped += 1
                else:
                    out.append((r, g, b, a))
                    unmapped += 1
                continue

            rgb = (r, g, b)
            if a == 0:
                out.append((r, g, b, a))
                continue
            if rgb == old_bg:
                out.append((*new_bg, a))
                mapped += 1
                continue
            if rgb == old_fg:
                out.append((*new_fg, a))
                mapped += 1
                continue

            # Anti-aliased pixel: project onto the bg->fg line and keep the blend factor.
            bg_f = [float(c) for c in old_bg]
            fg_f = [float(c) for c in old_fg]
            px_f = [float(c) for c in rgb]
            axis = [f - b for b, f in zip(bg_f, fg_f)]
            axis_len_sq = sum(v * v for v in axis)
            if axis_len_sq == 0:
                out.append((r, g, b, a))
                continue
            t = sum((p - b) * v for p, b, v in zip(px_f, bg_f, axis)) / axis_len_sq
            t = max(0.0, min(1.0, t))
            projected = [b + t * v for b, v in zip(bg_f, axis)]
            dist = (sum((p - q) ** 2 for p, q in zip(px_f, projected))) ** 0.5
            if dist <= BLEND_TOLERANCE:
                blended = tuple(
                    int(round((1 - t) * nb + t * nf))
                    for nb, nf in zip(new_bg, new_fg)
                )
                out.append((*blended, a))
                mapped += 1
            else:
                # Not a blend of the two detected colors (e.g. a third design color):
                # leave untouched and report.
                out.append((r, g, b, a))
                unmapped += 1

        img.putdata(out)
        img.save(path, lossless=True)
        extra = f", {unmapped} unmapped" if unmapped else ""
        print(
            f"{path.name} [{path.parent.name}]: bg=#{ '%02x%02x%02x' % old_bg } "
            f"fg=#{ '%02x%02x%02x' % old_fg } -> "
            f"#{ '%02x%02x%02x' % new_bg }/#{ '%02x%02x%02x' % new_fg } "
            f"({mapped} mapped{extra})"
        )


if __name__ == "__main__":
    main()
