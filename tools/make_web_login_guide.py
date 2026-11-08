"""Build TomatoInsight web Login user-guide image from a real screenshot."""
from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

SRC = Path(
    r"C:\Users\SPARK-PC-05\.cursor\projects\c-Users-SPARK-PC-05-Desktop-TomatoInsightMObileApplication"
    r"\assets\c__Users_SPARK-PC-05_AppData_Roaming_Cursor_User_workspaceStorage_"
    r"82e08f38620094439e9fdb09641b34f0_images_image-0629548a-01f1-4ec8-9a24-c8c1237111fa.png"
)
OUT = Path(r"Z:\USER_GUIDE\TomatoInsight_Web_Login_Guide.png")

RED = (220, 30, 30)
WHITE = (255, 255, 255)
BG = (255, 255, 255)


def load_font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = [
        r"C:\Windows\Fonts\arialbd.ttf" if bold else r"C:\Windows\Fonts\arial.ttf",
        r"C:\Windows\Fonts\segoeuib.ttf" if bold else r"C:\Windows\Fonts\segoeui.ttf",
        r"C:\Windows\Fonts\timesbd.ttf" if bold else r"C:\Windows\Fonts\times.ttf",
    ]
    for path in candidates:
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default()


def cluster(rows: list[int], gap: int = 4) -> list[tuple[int, int]]:
    if not rows:
        return []
    groups: list[list[int]] = [[rows[0]]]
    for r in rows[1:]:
        if r - groups[-1][-1] <= gap:
            groups[-1].append(r)
        else:
            groups.append([r])
    return [(g[0], g[-1]) for g in groups if len(g) >= 4]


def find_regions(im: Image.Image) -> dict[str, tuple[int, int, int, int]]:
    """Locate login form controls on the left half of the web screenshot."""
    arr = np.array(im.convert("RGB"))
    h, w, _ = arr.shape
    left, right = int(w * 0.12), int(w * 0.46)

    # Detect nearly-white input rows using a narrow x sample inside the fields
    sample_x0, sample_x1 = int(w * 0.18), int(w * 0.40)
    white_rows: list[int] = []
    for y in range(int(h * 0.25), int(h * 0.65)):
        strip = arr[y, sample_x0:sample_x1]
        # pure-ish white pixels ratio
        white_ratio = ((strip[:, 0] > 248) & (strip[:, 1] > 248) & (strip[:, 2] > 248)).mean()
        if white_ratio > 0.45:
            white_rows.append(y)

    fields = cluster(white_rows, gap=4)
    fields = [f for f in fields if 10 <= (f[1] - f[0]) <= 45]
    fields = sorted(fields, key=lambda t: t[0])[:2]
    print("input fields:", fields)

    if len(fields) >= 2:
        email, password = fields[0], fields[1]
    else:
        # Calibrated from TomatoInsight web login screenshot (1024x511)
        email = (212, 240)
        password = (260, 288)

    # Red Sign In button via strong red pixels
    btn_rows: list[int] = []
    for y in range(password[1] + 5, int(h * 0.85)):
        strip = arr[y, sample_x0:sample_x1]
        red_ratio = ((strip[:, 0] > 200) & (strip[:, 1] < 80) & (strip[:, 2] < 70)).mean()
        if red_ratio > 0.25:
            btn_rows.append(y)
    btn_groups = cluster(btn_rows, gap=3)
    if btn_groups:
        button = max(btn_groups, key=lambda t: t[1] - t[0])
    else:
        button = (316, 344)

    mid_top = password[1] + 4
    mid_bot = button[0] - 6

    email_password = (left, email[0] - 22, right, password[1] + 4)
    eye = (right - 42, password[0] - 2, right - 4, password[1] + 2)
    remember = (left, mid_top, left + int((right - left) * 0.42), mid_bot)
    forgot = (left + int((right - left) * 0.48), mid_top, right, mid_bot)
    sign_in = (left, button[0] - 2, right, button[1] + 2)
    create = (left + int((right - left) * 0.28), button[1] + 14, right, button[1] + 42)

    return {
        "email_password": email_password,
        "eye": eye,
        "remember": remember,
        "forgot": forgot,
        "sign_in": sign_in,
        "create_account": create,
    }


def draw_box(
    draw: ImageDraw.ImageDraw,
    box: tuple[int, int, int, int],
    number: int,
    font: ImageFont.ImageFont,
) -> None:
    x0, y0, x1, y1 = box
    width = 3
    draw.rounded_rectangle([x0, y0, x1, y1], radius=8, outline=RED, width=width)

    label = str(number)
    tb = draw.textbbox((0, 0), label, font=font)
    tw, th = tb[2] - tb[0], tb[3] - tb[1]
    pad = 5
    bx0 = max(0, x0 - (tw + pad * 2) // 2)
    by0 = max(0, y0 - (th + pad * 2) // 2)
    bx1 = bx0 + tw + pad * 2
    by1 = by0 + th + pad * 2
    draw.rounded_rectangle([bx0, by0, bx1, by1], radius=5, fill=RED)
    draw.text((bx0 + pad, by0 + pad - 1), label, fill=WHITE, font=font)


def rounded_card(src: Image.Image, radius: int = 16) -> Image.Image:
    card = src.convert("RGBA")
    mask = Image.new("L", card.size, 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle([0, 0, card.width - 1, card.height - 1], radius=radius, fill=255)
    out = Image.new("RGBA", card.size, (0, 0, 0, 0))
    out.paste(card, (0, 0))
    out.putalpha(mask)
    border = Image.new("RGBA", card.size, (0, 0, 0, 0))
    bd = ImageDraw.Draw(border)
    bd.rounded_rectangle(
        [1, 1, card.width - 2, card.height - 2],
        radius=radius,
        outline=(190, 190, 190, 255),
        width=2,
    )
    return Image.alpha_composite(out, border)


def main() -> None:
    src = Image.open(SRC).convert("RGBA")
    regions = find_regions(src)
    print("Detected regions:")
    for k, v in regions.items():
        print(f"  {k}: {v}")

    # Scale for a readable 3-up layout
    scale = 0.62
    scaled_w = int(src.width * scale)
    scaled_h = int(src.height * scale)
    scaled_src = src.resize((scaled_w, scaled_h), Image.Resampling.LANCZOS)

    # Scale region coords
    scaled_regions = {
        k: tuple(int(v * scale) for v in box)  # type: ignore[misc]
        for k, box in regions.items()
    }

    card = rounded_card(scaled_src, radius=14)
    cw, ch = card.size

    margin = 28
    gap = 20
    top = 28
    canvas_w = margin * 2 + cw * 3 + gap * 2

    instructions = [
        "1. Enter your registered email and password in the corresponding fields.",
        "2. Click the eye icon if you want to show or hide your password.",
        "3. Click the Remember me checkbox to save your email.",
        "4. Click the Sign In button.",
        "5. If you forget your password, click Forgot password?.",
        "6. Click Create Account if you do not have an account.",
    ]
    instr_font = load_font(20, bold=False)
    badge_font = load_font(16, bold=True)
    line_h = 30
    instr_block_h = 24 + len(instructions) * line_h + 20
    canvas_h = top + ch + 28 + instr_block_h + 16

    canvas = Image.new("RGB", (canvas_w, canvas_h), BG)
    draw = ImageDraw.Draw(canvas)

    phone_boxes = [
        [("email_password", 1), ("eye", 2)],
        [("remember", 3), ("sign_in", 4)],
        [("forgot", 5), ("create_account", 6)],
    ]

    xs = [margin + i * (cw + gap) for i in range(3)]
    for i, x in enumerate(xs):
        annotated = card.copy()
        ad = ImageDraw.Draw(annotated)
        for key, num in phone_boxes[i]:
            draw_box(ad, scaled_regions[key], num, badge_font)
        canvas.paste(annotated, (x, top), annotated)

    iy = top + ch + 30
    for idx, text in enumerate(instructions):
        draw.text((margin, iy + idx * line_h), text, fill=RED, font=instr_font)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(OUT, "PNG")
    print(f"Saved: {OUT}")
    print(f"Size: {canvas.size}")


if __name__ == "__main__":
    main()
