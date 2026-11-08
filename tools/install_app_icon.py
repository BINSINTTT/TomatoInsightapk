from pathlib import Path

from PIL import Image, ImageEnhance

src = Path(
    r"C:\Users\SPARK-PC-05\.cursor\projects\c-Users-SPARK-PC-05-Desktop-TomatoInsightMObileApplication\assets\tomatoinsight_app_icon.jpg"
)
res = Path(r"C:\Users\SPARK-PC-05\Desktop\TomatoInsightMObileApplication\app\src\main\res")
out_master = res / "drawable-nodpi" / "tomatoinsight_logo.png"
out_fg = res / "drawable" / "ic_launcher_foreground.png"

img = Image.open(src).convert("RGB")
img = ImageEnhance.Color(img).enhance(1.12)
img = ImageEnhance.Contrast(img).enhance(1.08)
img = ImageEnhance.Sharpness(img).enhance(1.25)

w, h = img.size
side = min(w, h)
pad = int(side * 0.04)
left = (w - side) // 2 + pad
top = (h - side) // 2 + pad
right = left + side - 2 * pad
bottom = top + side - 2 * pad
img = img.crop((left, top, right, bottom))

master = img.resize((1024, 1024), Image.Resampling.LANCZOS)
out_master.parent.mkdir(parents=True, exist_ok=True)
master.save(out_master, format="PNG", optimize=True)

fg = master.resize((432, 432), Image.Resampling.LANCZOS)
fg.save(out_fg, format="PNG", optimize=True)

sizes = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
for folder, size in sizes.items():
    d = res / folder
    d.mkdir(parents=True, exist_ok=True)
    icon = master.resize((size, size), Image.Resampling.LANCZOS)
    icon.save(d / "ic_launcher.png", format="PNG", optimize=True)
    icon.save(d / "ic_launcher_round.png", format="PNG", optimize=True)

px = master.getpixel((8, 8))
print("bg_sample", "#{:02X}{:02X}{:02X}".format(*px))
print("wrote", out_master)
print("wrote", out_fg)
print("mipmaps ok")
