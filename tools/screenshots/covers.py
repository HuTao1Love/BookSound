"""Draws the covers of the demo library used for the screenshots: public-domain books, typographic covers.

usage: covers.py FONT_DIR OUT_DIR
FONT_DIR holds the @fontsource packages playfair-display, cinzel, abril-fatface, bebas-neue and
cormorant-garamond (see screenshots.yml); every cover is written to OUT_DIR as <key>.jpg.
"""
import math
import os
import random
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

SIZE = 1200
FONTS = sys.argv[1]
OUT = sys.argv[2]


def font(family, weight=400, size=60, style="normal"):
    path = os.path.join(FONTS, family, "files", f"{family}-latin-{weight}-{style}.woff2")
    return ImageFont.truetype(path, size)


def hex_color(h, alpha=255):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (alpha,)


def gradient(top, bottom):
    img = Image.new("RGBA", (SIZE, SIZE))
    a, b = hex_color(top), hex_color(bottom)
    draw = ImageDraw.Draw(img)
    for y in range(SIZE):
        t = y / (SIZE - 1)
        draw.line([(0, y), (SIZE, y)], fill=tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(4)))
    return img


def grain(img, amount=10, seed=1):
    """A little noise, so flat colours look printed rather than drawn."""
    rnd = random.Random(seed)
    noise = Image.new("L", (SIZE // 2, SIZE // 2))
    noise.putdata([128 + rnd.randint(-amount, amount) for _ in range((SIZE // 2) ** 2)])
    noise = noise.resize((SIZE, SIZE)).filter(ImageFilter.GaussianBlur(0.6))
    overlay = Image.merge("RGBA", (noise, noise, noise, Image.new("L", (SIZE, SIZE), 28)))
    return Image.alpha_composite(img, overlay)


def overlay(img, paint):
    """Draws [paint] on a transparent layer laid over [img], so translucent colours blend."""
    layer = Image.new("RGBA", img.size)
    paint(ImageDraw.Draw(layer))
    return Image.alpha_composite(img, layer)


def spaced(draw, y, text, fnt, fill, spacing=0.0, x_center=SIZE / 2):
    """Centered text with extra letter spacing (for small capitals)."""
    widths = [draw.textlength(ch, font=fnt) for ch in text]
    total = sum(widths) + spacing * fnt.size * (len(text) - 1)
    x = x_center - total / 2
    for ch, w in zip(text, widths):
        draw.text((x, y), ch, font=fnt, fill=fill)
        x += w + spacing * fnt.size


def centered(draw, y, text, fnt, fill, x_center=SIZE / 2):
    w = draw.textlength(text, font=fnt)
    draw.text((x_center - w / 2, y), text, font=fnt, fill=fill)
    box = draw.textbbox((0, 0), text, font=fnt)
    return box[3] - box[1]


def fit(draw, text, family, weight, max_width, size, style="normal"):
    """The largest font size (from [size] down) that keeps [text] within [max_width]."""
    while size > 20:
        f = font(family, weight, size, style)
        if draw.textlength(text, font=f) <= max_width:
            return f
        size -= 4
    return font(family, weight, size, style)


def save(img, key):
    img.convert("RGB").save(os.path.join(OUT, f"{key}.jpg"), quality=90)


# ------------------------------------------------------------------------------------------- covers

def pride():
    img = grain(gradient("#1b2757", "#101836"))
    d = ImageDraw.Draw(img)
    gold, cream = hex_color("#d9b46a"), hex_color("#f3e6c8")
    for inset, width in ((48, 6), (70, 2)):
        d.rectangle([inset, inset, SIZE - inset, SIZE - inset], outline=gold, width=width)
    # Two cameos facing each other.
    for cx in (330, 870):
        d.ellipse([cx - 150, 330, cx + 150, 700], fill=hex_color("#f0dfbd"), outline=gold, width=10)
        d.ellipse([cx - 120, 365, cx + 120, 665], outline=hex_color("#c99f55"), width=3)
    d.ellipse([280, 420, 380, 560], fill=hex_color("#2a2016"))
    d.ellipse([820, 420, 920, 560], fill=hex_color("#2a2016"))
    d.polygon([(250, 640), (410, 640), (380, 560), (280, 560)], fill=hex_color("#2a2016"))
    d.polygon([(790, 640), (950, 640), (920, 560), (820, 560)], fill=hex_color("#2a2016"))
    # Flourish between them.
    for i in range(7):
        r = 14 + i * 3
        d.ellipse([600 - r, 515 - r, 600 + r, 515 + r], outline=gold, width=2)
    spaced(d, 150, "JANE AUSTEN", font("cinzel", 600, 54), gold, spacing=0.28)
    centered(d, 760, "Pride", font("playfair-display", 700, 150, "italic"), cream)
    centered(d, 930, "& Prejudice", font("playfair-display", 700, 120, "italic"), cream)
    save(img, "pride")


def holmes(key, title, number, top, bottom, accent):
    img = grain(gradient(top, bottom), seed=number)
    brass, paper = hex_color(accent), hex_color("#f1e7d0")
    rnd = random.Random(number * 7)

    def streets(d):
        # A street map of thin lines under the glass.
        d.rectangle([0, 0, SIZE, 120], fill=hex_color("#000000", 70))
        for _ in range(22):
            x = rnd.randint(-200, SIZE)
            d.line([(x, 160), (x + rnd.randint(-300, 300), 760)], fill=hex_color("#ffffff", 18), width=3)
        for _ in range(12):
            y = rnd.randint(160, 760)
            d.line([(0, y), (SIZE, y + rnd.randint(-120, 120))], fill=hex_color("#ffffff", 18), width=3)
        d.ellipse([430, 270, 730, 570], fill=hex_color("#ffffff", 34))

    img = overlay(img, streets)
    d = ImageDraw.Draw(img)
    spaced(d, 38, f"SHERLOCK HOLMES  ·  {number}", font("cinzel", 700, 44), brass, spacing=0.22)
    d.ellipse([400, 240, 760, 600], outline=brass, width=26)
    d.line([(712, 552), (900, 740)], fill=brass, width=44)
    d.line([(830, 670), (920, 760)], fill=hex_color("#3a2414"), width=58)
    lines = title.split("\n")
    y = 800 if len(lines) > 1 else 860
    for line in lines:
        f = fit(d, line, "playfair-display", 700, SIZE - 180, 112)
        y += centered(d, y, line, f, paper) + 26
    spaced(d, 1090, "ARTHUR CONAN DOYLE", font("cinzel", 600, 40), brass, spacing=0.22)
    save(img, key)


def time_machine():
    img = grain(gradient("#0e3b43", "#06191d"), seed=3)
    d = ImageDraw.Draw(img)
    copper = hex_color("#e3a35a")
    cx, cy = 600, 470
    for r, w in ((330, 6), (270, 3), (210, 10), (120, 4)):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=copper, width=w)
    for i in range(60):
        a = math.tau * i / 60
        inner = 300 if i % 5 else 285
        d.line([(cx + inner * math.cos(a), cy + inner * math.sin(a)), (cx + 325 * math.cos(a), cy + 325 * math.sin(a))], fill=copper, width=6 if i % 5 == 0 else 2)
    d.line([(cx, cy), (cx + 180 * math.cos(-1.1), cy + 180 * math.sin(-1.1))], fill=hex_color("#f6e3c6"), width=14)
    d.line([(cx, cy), (cx + 110 * math.cos(2.2), cy + 110 * math.sin(2.2))], fill=hex_color("#f6e3c6"), width=18)
    d.ellipse([cx - 22, cy - 22, cx + 22, cy + 22], fill=copper)
    centered(d, 840, "THE TIME MACHINE", fit(d, "THE TIME MACHINE", "bebas-neue", 400, SIZE - 160, 190), hex_color("#f6e3c6"))
    spaced(d, 1060, "H. G. WELLS", font("bebas-neue", 400, 64), copper, spacing=0.35)
    save(img, "time")


def frankenstein():
    img = grain(gradient("#151b17", "#060807"), seed=4)
    d = ImageDraw.Draw(img)
    green = hex_color("#9be36a")
    bolt = [(640, 80), (470, 470), (600, 470), (430, 900), (760, 400), (620, 400), (760, 80)]
    glow = Image.new("RGBA", (SIZE, SIZE))
    ImageDraw.Draw(glow).polygon(bolt, fill=hex_color("#9be36a", 160))
    img = Image.alpha_composite(img, glow.filter(ImageFilter.GaussianBlur(40)))
    d = ImageDraw.Draw(img)
    d.polygon(bolt, fill=green)
    centered(d, 850, "Frankenstein", fit(d, "Frankenstein", "abril-fatface", 400, SIZE - 140, 170), hex_color("#eef4e6"))
    spaced(d, 1100, "MARY SHELLEY", font("cinzel", 600, 44), green, spacing=0.3)
    save(img, "frankenstein")


def dracula():
    img = grain(gradient("#2a0408", "#070102"), seed=5)
    moon = Image.new("RGBA", (SIZE, SIZE))
    ImageDraw.Draw(moon).ellipse([330, 160, 870, 700], fill=hex_color("#f2e2c4", 255))
    img = Image.alpha_composite(img, moon.filter(ImageFilter.GaussianBlur(30)))
    d = ImageDraw.Draw(img)
    d.ellipse([350, 180, 850, 680], fill=hex_color("#f6ead2"))
    # A castle against the moon.
    black = hex_color("#0a0203")
    d.rectangle([300, 520, 900, 760], fill=black)
    for x, h in ((330, 380), (470, 300), (600, 250), (730, 330), (840, 410)):
        d.rectangle([x - 40, h, x + 40, 760], fill=black)
        d.polygon([(x - 52, h), (x + 52, h), (x, h - 110)], fill=black)
    d.rectangle([0, 740, SIZE, SIZE], fill=black)
    centered(d, 800, "DRACULA", fit(d, "DRACULA", "cinzel", 900, SIZE - 160, 190), hex_color("#c4121f"))
    spaced(d, 1050, "BRAM STOKER", font("cinzel", 600, 46), hex_color("#e8d6b8"), spacing=0.3)
    save(img, "dracula")


def moby_dick():
    img = grain(gradient("#1d5d86", "#0a2236"), seed=6)
    d = ImageDraw.Draw(img)
    # The tail rising out of the sea.
    tail = hex_color("#0b1e2c")
    d.polygon([(560, 640), (640, 640), (620, 420), (580, 420)], fill=tail)
    d.polygon([(600, 440), (360, 300), (420, 420), (600, 470)], fill=tail)
    d.polygon([(600, 440), (840, 300), (780, 420), (600, 470)], fill=tail)
    for i, color in enumerate(("#2c7fb0", "#236c98", "#1a587e", "#124566")):
        y0 = 600 + i * 70
        pts = [(x, y0 + 26 * math.sin(x / 70 + i)) for x in range(0, SIZE + 20, 20)]
        d.polygon(pts + [(SIZE, SIZE), (0, SIZE)], fill=hex_color(color))
    centered(d, 120, "Moby-Dick", fit(d, "Moby-Dick", "abril-fatface", 400, SIZE - 160, 190), hex_color("#f4efe4"))
    spaced(d, 1060, "HERMAN MELVILLE", font("cinzel", 600, 46), hex_color("#f4efe4"), spacing=0.25)
    save(img, "moby")


def alice():
    img = grain(gradient("#f6c6cf", "#e88fa2"), seed=7)
    d = ImageDraw.Draw(img)
    # A chessboard floor in perspective.
    horizon, rows, cols = 700, 8, 10
    for r in range(rows):
        y0 = horizon + (SIZE - horizon) * (r / rows) ** 1.6
        y1 = horizon + (SIZE - horizon) * ((r + 1) / rows) ** 1.6
        for c in range(-cols, cols):
            def x(yy, cc):
                spread = 0.25 + 0.75 * (yy - horizon) / (SIZE - horizon)
                return 600 + (cc * 120) * spread * 1.4
            if (r + c) % 2:
                d.polygon([(x(y0, c), y0), (x(y0, c + 1), y0), (x(y1, c + 1), y1), (x(y1, c), y1)], fill=hex_color("#2b1a33"))
    suits = font("playfair-display", 700, 150)
    for ch, (px, py), color in (("♥", (180, 330), "#c8102e"), ("♠", (930, 300), "#2b1a33"), ("♦", (300, 520), "#c8102e"), ("♣", (820, 520), "#2b1a33")):
        try:
            d.text((px, py), ch, font=ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 150), fill=hex_color(color))
        except OSError:
            d.text((px, py), ch, font=suits, fill=hex_color(color))
    centered(d, 70, "Alice’s Adventures", font("playfair-display", 700, 100, "italic"), hex_color("#2b1a33"))
    centered(d, 195, "in Wonderland", font("playfair-display", 700, 100, "italic"), hex_color("#2b1a33"))
    spaced(d, 1110, "LEWIS CARROLL", font("cinzel", 700, 40), hex_color("#f9e9ec"), spacing=0.3)
    save(img, "alice")


def dorian():
    img = grain(gradient("#4b1022", "#22050f"), seed=8)
    d = ImageDraw.Draw(img)
    gold = hex_color("#d4af5a")
    for inset, w in ((210, 26), (250, 6), (268, 3)):
        d.rectangle([inset, 120 + inset - 210, SIZE - inset, 760 - inset + 210], outline=gold, width=w)
    d.ellipse([440, 230, 760, 650], fill=hex_color("#1a0309"))
    d.ellipse([530, 300, 670, 470], fill=hex_color("#3a0c18"))
    d.polygon([(480, 650), (720, 650), (690, 520), (510, 520)], fill=hex_color("#3a0c18"))
    centered(d, 840, "The Picture of", font("cormorant-garamond", 600, 90, "italic"), hex_color("#f2e3c6"))
    centered(d, 940, "Dorian Gray", font("cormorant-garamond", 700, 140), hex_color("#f2e3c6"))
    spaced(d, 1110, "OSCAR WILDE", font("cinzel", 600, 40), gold, spacing=0.3)
    save(img, "dorian")


def leagues():
    img = grain(gradient("#0b4f5c", "#020d14"), seed=9)
    d = ImageDraw.Draw(img)
    brass = hex_color("#cf9a4a")
    d.ellipse([330, 180, 870, 720], outline=brass, width=40)
    img = overlay(img, lambda o: o.ellipse([380, 230, 820, 670], fill=hex_color("#0f6a7a", 120)))
    d = ImageDraw.Draw(img)
    for i in range(12):
        a = math.tau * i / 12
        x, y = 600 + 290 * math.cos(a), 450 + 290 * math.sin(a)
        d.ellipse([x - 14, y - 14, x + 14, y + 14], fill=hex_color("#7a5524"))
    rnd = random.Random(11)
    for _ in range(18):
        x, y, r = rnd.randint(440, 760), rnd.randint(280, 640), rnd.randint(6, 26)
        d.ellipse([x - r, y - r, x + r, y + r], outline=hex_color("#9fd4dc"), width=3)
    centered(d, 790, "TWENTY THOUSAND", fit(d, "TWENTY THOUSAND", "bebas-neue", 400, SIZE - 200, 150), hex_color("#e9f6f4"))
    centered(d, 935, "LEAGUES UNDER THE SEAS", fit(d, "LEAGUES UNDER THE SEAS", "bebas-neue", 400, SIZE - 200, 110), hex_color("#e9f6f4"))
    spaced(d, 1090, "JULES VERNE", font("bebas-neue", 400, 60), brass, spacing=0.35)
    save(img, "leagues")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    pride()
    holmes("holmes1", "A Study in Scarlet", 1, "#1f3b2f", "#0c1a14", "#d0a85c")
    holmes("holmes2", "The Sign of the Four", 2, "#5a1f24", "#22090c", "#d8b06a")
    holmes("holmes3", "The Adventures of\nSherlock Holmes", 3, "#22344a", "#0b1420", "#d4ad66")
    holmes("holmes4", "The Memoirs of\nSherlock Holmes", 4, "#4a3a14", "#1a1406", "#e0bd78")
    holmes("holmes5", "The Hound of the\nBaskervilles", 5, "#123c3f", "#061719", "#d6ae63")
    time_machine()
    frankenstein()
    dracula()
    moby_dick()
    alice()
    dorian()
    leagues()
