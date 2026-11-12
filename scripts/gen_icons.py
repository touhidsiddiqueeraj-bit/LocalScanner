"""Generates launcher icons for all densities: teal rounded square with a
white document + scan-line glyph."""
from PIL import Image, ImageDraw

BASE = "/home/z/my-project/localscanner/app/src/main/res"

def draw_icon(size: int) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # background: rounded square, teal
    bg = (0x00, 0x69, 0x6C, 0xFF)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.18), fill=bg)
    # document sheet
    white = (255, 255, 255, 255)
    sheet_w = size * 0.44
    sheet_h = size * 0.58
    x0 = (size - sheet_w) / 2
    y0 = (size - sheet_h) / 2
    fold = sheet_w * 0.28
    d.rounded_rectangle(
        [x0, y0, x0 + sheet_w, y0 + sheet_h],
        radius=int(size * 0.03),
        fill=white,
    )
    # corner fold
    d.polygon(
        [(x0 + sheet_w - fold, y0), (x0 + sheet_w, y0 + fold), (x0 + sheet_w - fold, y0 + fold)],
        fill=(0xDA, 0xE4, 0xE5, 0xFF),
    )
    # text lines
    line_color = (0x3F, 0x49, 0x49, 0xFF)
    for i in range(3):
        ly = y0 + sheet_h * (0.30 + 0.13 * i)
        d.line(
            [(x0 + sheet_w * 0.15, ly), (x0 + sheet_w * 0.85, ly)],
            fill=line_color,
            width=max(1, int(size * 0.02)),
        )
    # scan line across
    accent = (0x43, 0xDA, 0xDF, 0xFF)
    sy = y0 + sheet_h * 0.78
    d.rounded_rectangle(
        [x0 - size * 0.06, sy, x0 + sheet_w + size * 0.06, sy + size * 0.035],
        radius=int(size * 0.02),
        fill=accent,
    )
    return img

SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

for folder, px in SIZES.items():
    draw_icon(px).save(f"{BASE}/{folder}/ic_launcher.png")
    print(f"wrote {folder}/ic_launcher.png ({px}px)")
