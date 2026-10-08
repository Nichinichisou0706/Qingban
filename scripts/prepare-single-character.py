"""Extract one existing character from the two original paired sprites.

Keeps the source PNGs outside Android resources and uses an alpha seam between
the two figures. Running it again always starts from the saved original.
"""
from pathlib import Path
import shutil
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "feature/ui/src/main/res/drawable-nodpi"
ORIGINALS = ROOT / "artwork/originals"


def single_character(source: Image.Image) -> Image.Image:
    source = source.convert("RGBA")
    width, height = source.size
    alpha = source.getchannel("A")
    pixels = alpha.load()
    left, right = 180, 247
    xs = list(range(left, right))
    previous = [0.0] * len(xs)
    parents = []
    for y in range(height):
        costs, row = [], []
        for i, x in enumerate(xs):
            best, parent = min((previous[j] + abs(i-j)*0.8, j)
                               for j in range(max(0, i-3), min(len(xs), i+4)))
            costs.append(best + pixels[x, y] / 255.0 * 100 + abs(x-213)*0.01)
            row.append(parent)
        previous = costs
        parents.append(row)
    index = min(range(len(xs)), key=previous.__getitem__)
    seam = []
    for y in range(height-1, -1, -1):
        seam.append(xs[index])
        index = parents[y][index]
    seam.reverse()
    masked = Image.new("RGBA", source.size)
    for y, boundary in enumerate(seam):
        masked.paste(source.crop((0, y, boundary, y+1)), (0, y))
    portrait = masked.crop(masked.getchannel("A").getbbox())
    scale = min(375/portrait.width, 625/portrait.height)
    portrait = portrait.resize((round(portrait.width*scale), round(portrait.height*scale)), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (420, 686))
    canvas.paste(portrait, ((420-portrait.width)//2, 654-portrait.height))
    return canvas


def main():
    ORIGINALS.mkdir(parents=True, exist_ok=True)
    for name in ("gpt_idle.png", "gpt_waiting.png"):
        original = ORIGINALS / name
        if not original.exists():
            shutil.copy2(ASSETS / name, original)
        with Image.open(original) as source:
            single_character(source).save(ASSETS / name, optimize=True)
    sheet = Image.new("RGB", (900, 880), "#e6ece8")
    draw = ImageDraw.Draw(sheet)
    for index, path in enumerate(sorted(ASSETS.glob("gpt_*.png"))):
        with Image.open(path) as source:
            portrait = source.convert("RGBA")
        portrait.thumbnail((280, 380))
        x = index % 3 * 300 + (300-portrait.width)//2
        y = index // 3 * 440 + 45
        sheet.paste(portrait, (x, y), portrait)
        draw.text((index % 3 * 300+15, index // 3 * 440+15), path.name, fill="#183b2a")
    (ROOT / "dist").mkdir(parents=True, exist_ok=True)
    sheet.save(ROOT / "dist/character-single-preview.png")
    print("Prepared single-character idle and waiting sprites; originals retained.")


if __name__ == "__main__":
    main()
