from pathlib import Path

from PIL import Image, ImageDraw


OUT = Path(__file__).with_name("major-feature-icons")
NAVY = "#0B438F"
SCALE = 4
SIZE = 256


def canvas():
    return Image.new("RGBA", (SIZE * SCALE, SIZE * SCALE), (0, 0, 0, 0))


def save(name, draw_icon):
    image = canvas()
    draw_icon(ImageDraw.Draw(image))
    image = image.resize((SIZE, SIZE), Image.Resampling.LANCZOS)
    OUT.mkdir(parents=True, exist_ok=True)
    image.save(OUT / f"{name}.png", optimize=True)
    return image


def search(draw):
    w = 18 * SCALE
    draw.ellipse((48*SCALE, 38*SCALE, 164*SCALE, 154*SCALE), outline=NAVY, width=w)
    draw.line((146*SCALE, 139*SCALE, 211*SCALE, 204*SCALE), fill=NAVY, width=w)


def account(draw):
    draw.rounded_rectangle((35*SCALE, 62*SCALE, 221*SCALE, 194*SCALE), 22*SCALE, fill=NAVY)
    draw.polygon([(48*SCALE, 65*SCALE), (154*SCALE, 28*SCALE),
                  (179*SCALE, 65*SCALE)], fill=NAVY)
    draw.rounded_rectangle((147*SCALE, 104*SCALE, 229*SCALE, 161*SCALE), 14*SCALE, fill="#FFFFFF")
    draw.ellipse((170*SCALE, 124*SCALE, 186*SCALE, 140*SCALE), fill=NAVY)


def anomaly(draw):
    points = [(128*SCALE, 25*SCALE), (215*SCALE, 60*SCALE), (204*SCALE, 148*SCALE),
              (174*SCALE, 194*SCALE), (128*SCALE, 226*SCALE), (82*SCALE, 194*SCALE),
              (52*SCALE, 148*SCALE), (41*SCALE, 60*SCALE)]
    draw.polygon(points, fill=NAVY)
    draw.rounded_rectangle((118*SCALE, 73*SCALE, 138*SCALE, 151*SCALE), 9*SCALE, fill="#FFFFFF")
    draw.ellipse((116*SCALE, 169*SCALE, 140*SCALE, 193*SCALE), fill="#FFFFFF")


def watchlist(draw):
    points = [(128, 23), (158, 89), (230, 96), (176, 145), (192, 218),
              (128, 181), (64, 218), (80, 145), (26, 96), (98, 89)]
    draw.polygon([(x*SCALE, y*SCALE) for x, y in points], fill=NAVY)


def auditory_chart(draw):
    for x, top, width, bottom in [(43, 130, 30, 211), (88, 91, 30, 211),
                                  (133, 43, 30, 211), (178, 112, 30, 211)]:
        draw.rounded_rectangle((x*SCALE, top*SCALE, (x+width)*SCALE, bottom*SCALE),
                               8*SCALE, fill=NAVY)


def chatbot(draw):
    draw.rounded_rectangle((31*SCALE, 43*SCALE, 225*SCALE, 181*SCALE), 27*SCALE, fill=NAVY)
    draw.polygon([(73*SCALE, 171*SCALE), (65*SCALE, 224*SCALE),
                  (123*SCALE, 178*SCALE)], fill=NAVY)
    for x in (84, 128, 172):
        draw.ellipse(((x-10)*SCALE, 101*SCALE, (x+10)*SCALE, 121*SCALE), fill="#FFFFFF")


icons = [
    ("stock-search", search),
    ("account", account),
    ("anomaly-detection", anomaly),
    ("watchlist", watchlist),
    ("auditory-chart", auditory_chart),
    ("ai-chatbot", chatbot),
]

rendered = [(name, save(name, painter)) for name, painter in icons]

preview = Image.new("RGB", (960, 640), "#F5F7FA")
for index, (name, icon) in enumerate(rendered):
    x = 40 + (index % 3) * 310
    y = 35 + (index // 3) * 300
    card = Image.new("RGB", (270, 260), "#FFFFFF")
    card_draw = ImageDraw.Draw(card)
    card_draw.rounded_rectangle((1, 1, 268, 258), 12, outline="#D6DDE7", width=2)
    card.paste(icon.resize((132, 132), Image.Resampling.LANCZOS), (69, 35), icon.resize((132, 132), Image.Resampling.LANCZOS))
    card_draw.text((135, 215), name, fill=NAVY, anchor="mm")
    preview.paste(card, (x, y))

preview.save(OUT / "major-feature-icons-preview.jpg", quality=95, optimize=True)
print(OUT)
