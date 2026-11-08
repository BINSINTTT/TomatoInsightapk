"""Build TomatoInsight Login user-guide image from a real screenshot."""
from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

SRC = Path(
    r"C:\Users\SPARK-PC-05\.cursor\projects\c-Users-SPARK-PC-05-Desktop-TomatoInsightMObileApplication"
    r"\assets\c__Users_SPARK-PC-05_AppData_Roaming_Cursor_User_workspaceStorage_"
    r"82e08f38620094439e9fdb09641b34f0_images_image-f9e6d082-592d-4964-8e11-50ec803aaac2.png"
)
OUT = Path(r"Z:\USER_GUIDE\TomatoInsight_Login_Guide.png")

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


def find_regions(im: Image.Image) -> dict[str, tuple[int, int, int, int]]:
    """Detect key UI regions from the screenshot pixels."""
    arr = np.array(im.convert("RGB"))
    h, w, _ = arr.shape

    # White card: first row that is mostly white across center
    card_y = None
    for y in range(int(h * 0.25), int(h * 0.55)):
        center = arr[y, int(w * 0.15) : int(w * 0.85)]
        if (center > 245).all(axis=1).mean() > 0.9:
            card_y = y
            break
    if card_y is None:
        card_y = int(h * 0.36)

    # Gray input fields (#F3F4F6-ish)
    gray_rows: list[int] = []
    for y in range(card_y + 40, h - 40):
        strip = arr[y, int(w * 0.12) : int(w * 0.88)]
        mean = strip.mean(axis=0)
        if (
            230 <= mean[0] <= 248
            and 230 <= mean[1] <= 248
            and 230 <= mean[2] <= 248
            and abs(float(mean[0]) - float(mean[1])) < 4
            and mean[0] < 252
        ):
            gray_rows.append(y)

    def cluster(rows: list[int], gap: int = 8) -> list[tuple[int, int]]:
        if not rows:
            return []
        groups: list[list[int]] = [[rows[0]]]
        for r in rows[1:]:
            if r - groups[-1][-1] <= gap:
                groups[-1].append(r)
            else:
                groups.append([r])
        return [(g[0], g[-1]) for g in groups if len(g) >= 8]

    fields = cluster(gray_rows, gap=6)
    # Keep the two largest field bands
    fields = sorted(fields, key=lambda t: t[1] - t[0], reverse=True)[:2]
    fields = sorted(fields, key=lambda t: t[0])
    if len(fields) < 2:
        # fallback relative positions
        email = (int(h * 0.48), int(h * 0.545))
        password = (int(h * 0.56), int(h * 0.625))
    else:
        email, password = fields[0], fields[1]

    # Coral login button
    button_rows: list[int] = []
    for y in range(password[1] + 10, h - 20):
        strip = arr[y, int(w * 0.15) : int(w * 0.85)]
        mean = strip.mean(axis=0)
        if mean[0] > 200 and 70 < mean[1] < 160 and mean[2] < 120:
            button_rows.append(y)
    btn_groups = cluster(button_rows, gap=4)
    if btn_groups:
        button = max(btn_groups, key=lambda t: t[1] - t[0])
    else:
        button = (int(h * 0.72), int(h * 0.79))

    # Remember Me / Forgot Password row sits between password and button
    mid_top = password[1] + 4
    mid_bot = button[0] - 4
    remember = (mid_top, mid_bot)

    # Sign Up link sits near top of white card under "Login" title
    signup = (card_y + int(h * 0.04), card_y + int(h * 0.09))

    pad_x = int(w * 0.07)
    eye_size = int(w * 0.12)
    eye = (
        w - pad_x - eye_size - 4,
        password[0] + 4,
        w - pad_x - 4,
        password[1] - 4,
    )

    return {
        "email_password": (pad_x, email[0] - 6, w - pad_x, password[1] + 6),
        "eye": eye,
        "remember": (pad_x, remember[0], int(w * 0.52), remember[1]),
        "forgot": (int(w * 0.48), remember[0], w - pad_x, remember[1]),
        "login_btn": (pad_x, button[0] - 4, w - pad_x, button[1] + 4),
        "signup": (int(w * 0.48), signup[0], w - pad_x, signup[1]),
    }


def draw_box(
    draw: ImageDraw.ImageDraw,
    box: tuple[int, int, int, int],
    number: int,
    font: ImageFont.ImageFont,
    offset: tuple[int, int] = (0, 0),
) -> None:
    x0, y0, x1, y1 = box
    ox, oy = offset
    x0, y0, x1, y1 = x0 + ox, y0 + oy, x1 + ox, y1 + oy
    width = max(3, int((x1 - x0) * 0.012) + 3)
    draw.rounded_rectangle([x0, y0, x1, y1], radius=10, outline=RED, width=width)

    # Number badge
    label = str(number)
    tb = draw.textbbox((0, 0), label, font=font)
    tw, th = tb[2] - tb[0], tb[3] - tb[1]
    pad = 6
    bx0 = x0 - (tw + pad * 2) // 2
    by0 = y0 - (th + pad * 2) // 2
    bx0 = max(0, bx0)
    by0 = max(0, by0)
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
    # thin border
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
        "1. Enter your registered email and password in the corresponding fields.",
        "2. Tap the eye icon if you want to show or hide your password.",
        "3. Tap the Remember Me checkbox to save your email.",
        "4. Tap the Login button.",
        "5. If you forget your password, tap Forgot Password?.",
        "6. Tap Sign Up if you do not have an account.",
    ]
    instr_font = load_font(22, bold=False)
    title_font = load_font(20, bold=True)
    badge_font = load_font(18, bold=True)

    # Estimate instruction block height
    line_h = 34
    instr_block_h = 28 + len(instructions) * line_h + 24
    canvas_h = top + ph + 36 + instr_block_h + 20

    canvas = Image.new("RGB", (canvas_w, canvas_h), BG)
    draw = ImageDraw.Draw(canvas)

    phone_boxes = [
        # phone 1: email/password + eye
        [("email_password", 1), ("eye", 2)],
        # phone 2: remember + login
        [("remember", 3), ("login_btn", 4)],
        # phone 3: forgot + signup
        [("forgot", 5), ("signup", 6)],
    ]

    xs = [margin + i * (pw + gap) for i in range(3)]
    for i, x in enumerate(xs):
        annotated = phone.copy()
        ad = ImageDraw.Draw(annotated)
        for key, num in phone_boxes[i]:
            draw_box(ad, regions[key], num, badge_font)
        canvas.paste(annotated, (x, top), annotated)

    # Instructions
    iy = top + ph + 40
    for idx, text in enumerate(instructions):
        draw.text((margin, iy + idx * line_h), text, fill=RED, font=instr_font)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(OUT, "PNG")
    print(f"Saved: {OUT}")
    print(f"Size: {canvas.size}")


if __name__ == "__main__":
    main()
