"""Build TomatoInsight Sign up user-guide image from a real screenshot."""
from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

SRC = Path(
    r"C:\Users\SPARK-PC-05\.cursor\projects\c-Users-SPARK-PC-05-Desktop-TomatoInsightMObileApplication"
    r"\assets\c__Users_SPARK-PC-05_AppData_Roaming_Cursor_User_workspaceStorage_"
    r"82e08f38620094439e9fdb09641b34f0_images_image-7df74ee8-b87f-44e0-8bbd-f2926cc66635.png"
)
OUT = Path(r"Z:\USER_GUIDE\TomatoInsight_Signup_Guide.png")

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


def cluster(rows: list[int], gap: int = 6) -> list[tuple[int, int]]:
    if not rows:
        return []
    groups: list[list[int]] = [[rows[0]]]
    for r in rows[1:]:
        if r - groups[-1][-1] <= gap:
            groups[-1].append(r)
        else:
            groups.append([r])
    return [(g[0], g[-1]) for g in groups if len(g) >= 6]


def find_regions(im: Image.Image) -> dict[str, tuple[int, int, int, int]]:
    arr = np.array(im.convert("RGB"))
    h, w, _ = arr.shape

    card_y = None
    for y in range(int(h * 0.20), int(h * 0.55)):
        center = arr[y, int(w * 0.15) : int(w * 0.85)]
        if (center > 245).all(axis=1).mean() > 0.9:
            card_y = y
            break
    if card_y is None:
        card_y = int(h * 0.34)

    gray_rows: list[int] = []
    for y in range(card_y + 30, h - 30):
        strip = arr[y, int(w * 0.12) : int(w * 0.88)]
        mean = strip.mean(axis=0)
        if (
            228 <= mean[0] <= 248
            and 228 <= mean[1] <= 248
            and 228 <= mean[2] <= 248
            and abs(float(mean[0]) - float(mean[1])) < 5
            and mean[0] < 252
        ):
            gray_rows.append(y)

    fields = cluster(gray_rows, gap=5)
    # Prefer taller bands (actual inputs), take up to 6
    fields = sorted(fields, key=lambda t: t[1] - t[0], reverse=True)
    fields = sorted(fields[:6], key=lambda t: t[0])
    print("fields:", fields)

    pad_x = int(w * 0.07)

    if len(fields) >= 6:
        name_fields = (pad_x, fields[0][0] - 4, w - pad_x, fields[2][1] + 4)
        email_field = (pad_x, fields[3][0] - 4, w - pad_x, fields[3][1] + 4)
        password_fields = (pad_x, fields[4][0] - 4, w - pad_x, fields[5][1] + 4)
        eye_y0, eye_y1 = fields[4][0] + 2, fields[5][1] - 2
    elif len(fields) >= 4:
        mid = len(fields) // 2
        name_fields = (pad_x, fields[0][0] - 4, w - pad_x, fields[mid - 1][1] + 4)
        email_field = (pad_x, fields[mid][0] - 4, w - pad_x, fields[mid][1] + 4)
        password_fields = (pad_x, fields[mid + 1][0] - 4, w - pad_x, fields[-1][1] + 4)
        eye_y0, eye_y1 = fields[mid + 1][0] + 2, fields[-1][1] - 2
    else:
        name_fields = (pad_x, int(h * 0.42), w - pad_x, int(h * 0.58))
        email_field = (pad_x, int(h * 0.58), w - pad_x, int(h * 0.64))
        password_fields = (pad_x, int(h * 0.66), w - pad_x, int(h * 0.80))
        eye_y0, eye_y1 = int(h * 0.66), int(h * 0.80)

    # Coral Create Account button
    button_rows: list[int] = []
    start_y = password_fields[3] + 4
    for y in range(start_y, h - 10):
        strip = arr[y, int(w * 0.15) : int(w * 0.85)]
        mean = strip.mean(axis=0)
        if mean[0] > 200 and 70 < mean[1] < 170 and mean[2] < 130:
            button_rows.append(y)
    btn_groups = cluster(button_rows, gap=4)
    if btn_groups:
        button = max(btn_groups, key=lambda t: t[1] - t[0])
    else:
        button = (int(h * 0.86), int(h * 0.92))

    eye_size = int(w * 0.12)
    eyes = (
        w - pad_x - eye_size - 4,
        eye_y0,
        w - pad_x - 4,
        eye_y1,
    )

    # Login link under Sign up title
    login_link = (int(w * 0.52), card_y + int(h * 0.035), w - pad_x, card_y + int(h * 0.085))

    # All identity + email fields together for step 1
    all_info = (pad_x, name_fields[1], w - pad_x, email_field[3])

    return {
        "all_info": all_info,
        "password_fields": password_fields,
        "eyes": eyes,
        "create_btn": (pad_x, button[0] - 4, w - pad_x, button[1] + 4),
        "login_link": login_link,
        "name_fields": name_fields,
        "email_field": email_field,
    }


def draw_box(
    draw: ImageDraw.ImageDraw,
    box: tuple[int, int, int, int],
    number: int,
    font: ImageFont.ImageFont,
) -> None:
    x0, y0, x1, y1 = box
    width = max(3, int((x1 - x0) * 0.012) + 3)
    draw.rounded_rectangle([x0, y0, x1, y1], radius=10, outline=RED, width=width)

    label = str(number)
    tb = draw.textbbox((0, 0), label, font=font)
    tw, th = tb[2] - tb[0], tb[3] - tb[1]
    pad = 6
    bx0 = max(0, x0 - (tw + pad * 2) // 2)
    by0 = max(0, y0 - (th + pad * 2) // 2)
    bx1 = bx0 + tw + pad * 2
    by1 = by0 + th + pad * 2
    draw.rounded_rectangle([bx0, by0, bx1, by1], radius=6, fill=RED)
    draw.text((bx0 + pad, by0 + pad - 1), label, fill=WHITE, font=font)


def rounded_phone(src: Image.Image, radius: int = 28) -> Image.Image:
    phone = src.convert("RGBA")
    mask = Image.new("L", phone.size, 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle([0, 0, phone.width - 1, phone.height - 1], radius=radius, fill=255)
    out = Image.new("RGBA", phone.size, (0, 0, 0, 0))
    out.paste(phone, (0, 0))
    out.putalpha(mask)
    border = Image.new("RGBA", phone.size, (0, 0, 0, 0))
    bd = ImageDraw.Draw(border)
    bd.rounded_rectangle(
        [1, 1, phone.width - 2, phone.height - 2],
        radius=radius,
        outline=(180, 180, 180, 255),
        width=2,
    )
    return Image.alpha_composite(out, border)


def main() -> None:
    src = Image.open(SRC).convert("RGBA")
    regions = find_regions(src)
    print("Detected regions:")
    for k, v in regions.items():
        print(f"  {k}: {v}")

    phone = rounded_phone(src)
    pw, ph = phone.size

    margin = 36
    gap = 28
    top = 40
    canvas_w = margin * 2 + pw * 3 + gap * 2

    instructions = [
        "1. Enter your first, middle (optional), last name, and email in the corresponding fields.",
        "2. Enter your password and confirm password.",
        "3. Tap the eye icon if you want to show or hide your password.",
        "4. Tap the Create Account button.",
        "5. Tap Login if you already have an account.",
    ]

    instr_font = load_font(20, bold=False)
    badge_font = load_font(18, bold=True)
    line_h = 32
    instr_block_h = 28 + len(instructions) * line_h + 24
    canvas_h = top + ph + 36 + instr_block_h + 20

    canvas = Image.new("RGB", (canvas_w, canvas_h), BG)
    draw = ImageDraw.Draw(canvas)

    # 3 phones, 5 steps distributed like the AgriLink layout
    phone_boxes = [
        [("all_info", 1), ("password_fields", 2)],
        [("eyes", 3), ("create_btn", 4)],
        [("login_link", 5)],
    ]

    xs = [margin + i * (pw + gap) for i in range(3)]
    for i, x in enumerate(xs):
        annotated = phone.copy()
        ad = ImageDraw.Draw(annotated)
        for key, num in phone_boxes[i]:
            draw_box(ad, regions[key], num, badge_font)
        canvas.paste(annotated, (x, top), annotated)

    iy = top + ph + 40
    for idx, text in enumerate(instructions):
        draw.text((margin, iy + idx * line_h), text, fill=RED, font=instr_font)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(OUT, "PNG")
    print(f"Saved: {OUT}")
    print(f"Size: {canvas.size}")


if __name__ == "__main__":
    main()
