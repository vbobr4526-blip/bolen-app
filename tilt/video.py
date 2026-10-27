# Вертикальный ролик «Тильт» 1080×1920 под music.py.
# Запуск: python3 video.py <tilt.wav> <out.mp4>
import math
import subprocess
import sys
from functools import lru_cache
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageOps

W, H, FPS, DUR = 1080, 1920, 30, 31.0
ROOT = Path(__file__).resolve().parent.parent
BLACK = "/usr/share/fonts/opentype/inter/Inter-Black.otf"
MONO = "/usr/share/fonts/truetype/jetbrains-mono/JetBrainsMono-ExtraBold.ttf"

INK = (18, 15, 36)
LAV, SKY, LILAC = (200, 182, 255), (166, 212, 255), (224, 198, 255)
RED, RED2 = (255, 59, 78), (190, 20, 40)
WHITE = (255, 255, 255)
BEAT = 0.5

rng = np.random.default_rng(3)

def font(size, mono=False):
    return ImageFont.truetype(MONO if mono else BLACK, size)

def clamp(x, a=0.0, b=1.0):
    return max(a, min(b, x))

def lerp(a, b, k):
    return a + (b - a) * k

def mix(c1, c2, k):
    return tuple(int(lerp(a, b, k)) for a, b in zip(c1, c2))

def spring(k, start=0.4):
    # пружинистое появление с перелётом, как в анимациях приложения
    if k <= 0:
        return start
    return 1 + (start - 1) * math.exp(-7 * k) * math.cos(12 * k)

def keyframes(t, pts):
    # кусочно-линейная интерполяция по [(время, значение)]
    if t <= pts[0][0]:
        return pts[0][1]
    for (t0, v0), (t1, v1) in zip(pts, pts[1:]):
        if t < t1:
            return lerp(v0, v1, (t - t0) / (t1 - t0))
    return pts[-1][1]

# ---------- фон и постоянные слои ----------
yy = np.linspace(0, 1, H)[:, None, None]
xx = np.linspace(0, 1, W)[None, :, None]

def gradient(top, bottom, glow, gx, gy):
    g = np.array(top) * (1 - yy) + np.array(bottom) * yy
    d = np.sqrt((xx - gx) ** 2 + ((yy - gy) * 1.6) ** 2)
    g = g + np.array(glow) * np.clip(1 - d / 0.8, 0, 1) ** 2 * 0.35
    return np.broadcast_to(g, (H, W, 3)).astype(np.float32)

BG_CALM = gradient((21, 19, 42), (34, 30, 64), LAV, 0.85, 0.1)
BG_TILT = gradient((40, 4, 10), (8, 2, 4), RED, 0.5, 0.45)
BG_OUT = gradient((14, 13, 24), (24, 22, 40), (120, 110, 170), 0.5, 0.2)
d = np.sqrt((xx - 0.5) ** 2 + ((yy - 0.5) * 0.9) ** 2)
VIGNETTE = np.clip(1.2 - d * 1.2, 0.45, 1).astype(np.float32)
SCAN = np.ones((H, 1, 1), np.float32)
SCAN[::4] = 0.6

def sticker(name, scale=3.2):
    im = Image.open(ROOT / f"talia-{name}.webp").convert("RGBA")
    return im.resize((int(im.width * scale), int(im.height * scale)), Image.LANCZOS)

TALIA = {n: sticker(n) for n in ("hi", "ok", "wink")}

def sad(im):
    # обесцвеченная, с красным оттенком
    a = im.getchannel("A")
    g = ImageOps.grayscale(im.convert("RGB"))
    tinted = ImageOps.colorize(g, (30, 0, 6), (255, 170, 170))
    tinted.putalpha(a)
    return tinted

TALIA_SAD = sad(TALIA["wink"])

# ---------- подписи в стиле канала ----------
def plate_img(w, h, colors, radius):
    grad = Image.linear_gradient("L").rotate(90).resize((w, h))
    c0, c1 = colors
    base = Image.composite(Image.new("RGB", (w, h), c1), Image.new("RGB", (w, h), c0), grad)
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), radius, fill=255)
    out = base.convert("RGBA")
    out.putalpha(mask)
    return out

PLATES = {"lav": (LILAC, SKY), "sky": (SKY, (143, 166, 255)), "red": (RED, (255, 120, 60))}

@lru_cache(maxsize=None)
def caption(lines, size):
    """lines: кортеж строк; строка вида '[red]ТЕКСТ' рисуется на плашке."""
    f = font(size)
    stroke = max(4, size // 14)
    rendered = []
    for line in lines:
        style = None
        if line.startswith("["):
            style, line = line[1:].split("]", 1)
        x0, y0, x1, y1 = f.getbbox(line)
        tw, th = x1 - x0, y1 - y0
        pad = int(size * 0.16)
        lw, lh = tw + pad * 2 + stroke * 2, int(size * 1.12) + stroke * 2
        layer = Image.new("RGBA", (lw, lh + stroke * 2), (0, 0, 0, 0))
        dr = ImageDraw.Draw(layer)
        tx, ty = pad + stroke - x0, (lh - th) // 2 - y0
        if style:
            p = plate_img(lw, lh - stroke, PLATES[style], int(size * 0.14))
            shadow = Image.new("RGBA", p.size, INK + (255,))
            shadow.putalpha(p.getchannel("A"))
            layer.alpha_composite(shadow, (0, stroke + 6))
            layer.alpha_composite(p, (0, stroke // 2))
            dr.text((tx, ty), line, font=f, fill=INK)
        else:
            dr.text((tx, ty + 8), line, font=f, fill=INK, stroke_width=stroke, stroke_fill=INK)
            dr.text((tx, ty), line, font=f, fill=WHITE, stroke_width=stroke, stroke_fill=INK)
        rendered.append(layer)
    gap = -int(size * 0.02)
    cw = max(l.width for l in rendered)
    ch = sum(l.height for l in rendered) + gap * (len(rendered) - 1)
    out = Image.new("RGBA", (cw, ch), (0, 0, 0, 0))
    y = 0
    for l in rendered:
        out.alpha_composite(l, ((cw - l.width) // 2, y))
        y += l.height + gap
    return out

@lru_cache(maxsize=None)
def label(text, size, color, mono=False, bg=None, pad=14):
    f = font(size, mono)
    x0, y0, x1, y1 = f.getbbox(text)
    w, h = x1 - x0 + pad * 2, y1 - y0 + pad * 2
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    dr = ImageDraw.Draw(im)
    if bg:
        dr.rounded_rectangle((0, 0, w - 1, h - 1), 12, fill=bg)
    dr.text((pad - x0, pad - y0), text, font=f, fill=color)
    return im

def put(frame, layer, cx, cy, scale=1.0, angle=0.0, alpha=1.0):
    if scale != 1.0:
        layer = layer.resize((max(1, int(layer.width * scale)), max(1, int(layer.height * scale))), Image.BILINEAR)
    if angle:
        layer = layer.rotate(angle, Image.BICUBIC, expand=True)
    if alpha < 1.0:
        layer = layer.copy()
        layer.putalpha(layer.getchannel("A").point(lambda v: int(v * alpha)))
    frame.alpha_composite(layer, (int(cx - layer.width / 2), int(cy - layer.height / 2)))

def stamp(text):
    # красный штамп «УМЕР»
    f = font(150)
    x0, y0, x1, y1 = f.getbbox(text)
    w, h = x1 - x0 + 90, y1 - y0 + 70
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    dr = ImageDraw.Draw(im)
    dr.rounded_rectangle((6, 6, w - 7, h - 7), 18, outline=RED + (255,), width=12)
    dr.text((45 - x0, 35 - y0), text, font=f, fill=RED + (255,))
    return im

STAMP = stamp("УМЕР")

# ---------- частицы: блёстки → пепел ----------
P = 46
px = rng.random(P) * W
py = rng.random(P) * H
pv = 0.6 + rng.random(P) * 1.6
pr = 3 + rng.random(P) * 7
pp = rng.random(P) * 6.3

def particles(dr, t, tilt):
    for i in range(P):
        if tilt < 0.5:
            y = (py[i] - t * pv[i] * 40) % (H + 40) - 20   # плывут вверх
        else:
            y = (py[i] + t * pv[i] * 90) % (H + 40) - 20   # пепел падает
        x = px[i] + math.sin(t * 0.8 + pp[i]) * 20
        a = 0.3 + 0.6 * (0.5 + 0.5 * math.sin(t * 2.5 + pp[i]))
        col = mix(LAV if i % 2 else SKY, RED if i % 3 else (255, 140, 60), clamp(tilt * 1.5))
        r = pr[i]
        if tilt < 0.5:
            q = r * 0.25
            dr.polygon([(x, y - r * 2), (x + q, y - q), (x + r * 2, y), (x + q, y + q),
                        (x, y + r * 2), (x - q, y + q), (x - r * 2, y), (x - q, y - q)],
                       fill=col + (int(255 * a),))
        else:
            dr.ellipse((x - r / 2, y - r / 2, x + r / 2, y + r / 2), fill=col + (int(255 * a),))

# ---------- HUD «НАСТРОЕНИЕ» ----------
MOOD = [(0, 100), (8.0, 100), (8.1, 60), (10, 60), (10.1, 12), (14, 12), (18, 5), (19, 5),
        (22, 1), (22.1, 0), (29.4, 0), (29.5, 1), (31, 1)]

def hud(frame, t, tilt):
    mood = keyframes(t, MOOD)
    col = mix(SKY, RED, clamp((60 - mood) / 50))
    dr = ImageDraw.Draw(frame)
    x, y, w = 70, 120, W - 140
    put(frame, label("НАСТРОЕНИЕ", 34, (220, 214, 255), mono=True, pad=0), x + 130, y)
    put(frame, label(f"{round(mood):>3}%", 40, col, mono=True, pad=0), x + w - 60, y)
    dr.rounded_rectangle((x, y + 36, x + w, y + 66), 15, fill=(255, 255, 255, 30), outline=(255, 255, 255, 60), width=2)
    fw = w * mood / 100
    if mood >= 1:
        flick = 1 if mood > 10 or int(t * 12) % 2 else 0.4   # мигает, когда почти пусто
        dr.rounded_rectangle((x + 4, y + 40, x + 4 + max(22, fw - 8), y + 62), 11,
                             fill=col + (int(255 * flick),))

# ---------- сцены ----------
def beat_pulse(t):
    return math.exp(-((t % BEAT) * 9))

CUTS = ["#3", "#4", "#7", "#9", "#12", "#23"]

def scene(frame, t):
    dr = ImageDraw.Draw(frame)
    cy = 780
    if t < 4:
        k = t
        put(frame, label("НУ ВСЁ, ЩАС ПОПРЁТ", 36, INK, bg=LAV, pad=16), 340, 470,
            spring(k - 0.2, 0.3), angle=3)
        put(frame, caption(("ЗАХОЖУ", "[lav]В РЕЙД"), 150), W / 2, cy, spring(k))
        put(frame, label("фулл шестёрка · настрой топ", 40, (170, 164, 212), pad=0), W / 2, cy + 230,
            alpha=clamp((k - 0.5) * 2))
        put(frame, TALIA["hi"], W - 290, 1480 + 14 * math.sin(t * 2.4), angle=2 * math.sin(t * 2.4))
    elif t < 8:
        k = t - 4
        put(frame, caption(("НАШЁЛ ЛУТ", "[sky]НА 2 ЛЯМА"), 130), W / 2, cy, spring(k))
        put(frame, label("до выхода 40 метров", 40, (170, 164, 212), pad=0), W / 2, cy + 210,
            alpha=clamp((k - 0.5) * 2))
        put(frame, TALIA["ok"], 300, 1480 + 14 * math.sin(t * 2.4), angle=-2 * math.sin(t * 2.4))
    elif t < 10:
        k = t - 8
        put(frame, caption(("ВЫСТРЕЛ", "[red]ИЗ КУСТОВ"), 140), W / 2, cy, spring(k, 2.2))
        put(frame, label("*звон в ушах*", 44, (255, 200, 200), pad=0), W / 2, cy + 230,
            alpha=clamp((k - 0.6) * 2))
    elif t < 14:
        k = t - 10
        put(frame, caption(("Я", "[red]УМЕР"), 260), W / 2, cy + 20, spring(k, 2.5) + 0.05 * beat_pulse(t))
        # килфид
        feed = label("КУСТ_228  ▸  BOLEN", 40, WHITE, mono=True, bg=(120, 10, 25, 220), pad=18)
        put(frame, feed, W / 2 + (1 - clamp(k * 3)) * 700, 300)
        put(frame, TALIA_SAD, W / 2 + 6 * math.sin(t * 40), 1500, angle=-12)
    elif t < 18:
        k = t - 14
        put(frame, caption(("ВЕСЬ ЛУТ", "[red]ПОТЕРЯН"), 140), W / 2, cy - 60, spring(k) + 0.04 * beat_pulse(t))
        lost = int(2_000_000 * clamp(k / 2.2) ** 0.6)
        money = f"−{lost:,} ₽".replace(",", " ")
        put(frame, label(money, 92, RED, mono=True, pad=0), W / 2, cy + 230)
        put(frame, label("страховка? не, не слышал", 40, (255, 180, 180), pad=0), W / 2, cy + 340,
            alpha=clamp((k - 1.5) * 2))
        put(frame, TALIA_SAD, W / 2, 1520, angle=-12 + 3 * math.sin(t * 3))
    elif t < 22:
        if t < 19:
            k = t - 18
            put(frame, caption(("ЕЩЁ ОДНА", "[lav]ПОСЛЕДНЯЯ"), 130), W / 2, cy, spring(k))
        else:
            i = min(int((t - 19) / BEAT), len(CUTS) - 1)
            k = (t - 19) - i * BEAT
            put(frame, caption(("КАТКА", "[red]" + CUTS[i]), 170), W / 2, cy - 80, spring(k, 1.6))
            put(frame, STAMP, W / 2, cy + 280, spring(k * 1.6, 2.4), angle=-14 + (i % 3) * 9)
        put(frame, TALIA_SAD, W / 2, 1560, angle=-12, alpha=0.9)
    elif t < 26:
        k = t - 22
        put(frame, caption(("[red]ТИЛЬТ",), 220), W / 2, cy, spring(k, 2.0) + 0.08 * beat_pulse(t),
            angle=3 * math.sin(t * 13))
        put(frame, label("НЕ ТРОГАЙТЕ МЕНЯ", 54, WHITE, bg=(0, 0, 0, 200), pad=20), W / 2, cy + 260,
            alpha=clamp((k - 0.8) * 3))
        put(frame, label("ремонт брони: 180 000 ₽", 40, (255, 170, 170), pad=0), W / 2, cy + 380,
            alpha=clamp((k - 1.8) * 3))
    elif t >= 27:
        if t < 28.6:
            k = t - 27
            put(frame, caption(("ЛАДНО.",), 150), W / 2, cy, spring(k * 0.7, 0.7), alpha=clamp(k * 2))
        else:
            k = t - 28.6
            put(frame, caption(("ЗАВТРА", "[lav]ОТЫГРАЮСЬ"), 130), W / 2, cy, spring(k))
            put(frame, TALIA["wink"], W / 2, 1480 + 10 * math.sin(t * 2), alpha=clamp(k * 2.5))
        put(frame, label("@bolen_live", 46, LAV, mono=True, pad=0), W / 2, 1830, alpha=clamp((t - 27.5) * 1.5))

# ---------- эффекты ----------
TILT = [(0, 0), (7.9, 0), (8.0, 0.55), (10, 0.55), (10.05, 0.85), (22, 0.85), (22.05, 1), (26, 1), (27, 0.3), (31, 0.3)]

def background(t, tilt):
    if t >= 26.5:
        return BG_OUT
    return BG_CALM * (1 - tilt) + BG_TILT * tilt

def glitch(a, amount):
    # сдвиг каналов + рваные горизонтальные полосы
    s = int(4 + 26 * amount)
    out = a.copy()
    out[:, :, 0] = np.roll(a[:, :, 0], s, axis=1)
    out[:, :, 2] = np.roll(a[:, :, 2], -s, axis=1)
    for _ in range(int(2 + 8 * amount)):
        y = rng.integers(0, H - 120)
        h = rng.integers(8, 120)
        out[y:y + h] = np.roll(out[y:y + h], rng.integers(-60, 60) * (1 + amount), axis=1)
    return out

def render(t, frozen=None):
    tilt = keyframes(t, TILT)
    if frozen is not None and 26 <= t < 27:
        # тейп-стоп: стоп-кадр обесцвечивается и гаснет
        k = t - 26
        g = frozen.mean(axis=2, keepdims=True)
        a = (frozen * (1 - k) + g * k) * (1 - k * 0.92)
        return np.clip(a, 0, 255).astype(np.uint8)

    frame = Image.fromarray(background(t, tilt).astype(np.uint8)).convert("RGBA")
    particles(ImageDraw.Draw(frame), t, tilt)
    scene(frame, t)
    hud(frame, t, tilt)
    a = np.asarray(frame.convert("RGB")).astype(np.float32)

    # тряска: выстрел, смерть и сильные доли во время тильта
    shake = 0.0
    if 8 <= t < 10:
        shake = 40 * math.exp(-(t - 8) * 2.5) + 4
    elif 10 <= t < 26:
        shake = (12 + 24 * (t >= 22)) * beat_pulse(t) + (30 * math.exp(-(t - 10) * 6) if t < 11 else 0)
    if shake > 0.5:
        dx, dy = rng.integers(-int(shake), int(shake) + 1, 2)
        a = np.roll(a, (dy, dx), axis=(0, 1))

    if 10 <= t < 26:
        amt = 0.25 + 0.75 * (t >= 22)
        if beat_pulse(t) > 0.55 or t >= 22:
            a = glitch(a, amt * (0.4 + 0.6 * rng.random()))
    if 8 <= t < 10:
        # контузия: темнее, мутнее, белая вспышка от выстрела
        flash = math.exp(-(t - 8) * 9)
        g = a.mean(axis=2, keepdims=True)
        a = (a * 0.5 + g * 0.5) * 0.75 + 255 * flash
    if 22 <= t < 26:
        a = a * SCAN
    if 10 <= t < 10.15:
        a = a * 0.4 + np.array(RED, np.float32) * 0.6

    a = a * VIGNETTE
    grain = rng.integers(-14, 15, (H // 4, W // 4, 1)).repeat(4, 0).repeat(4, 1)
    a = a + grain * (0.35 + 0.8 * tilt)
    # затемнения на стыках интро и аутро
    a = a * clamp(t / 0.4) * clamp((DUR - t) / 1.2)
    return np.clip(a, 0, 255).astype(np.uint8)

def main(wav, out):
    ff = subprocess.Popen([
        "ffmpeg", "-y", "-loglevel", "error",
        "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
        "-i", wav,
        "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart", out,
    ], stdin=subprocess.PIPE)
    frozen = None
    for i in range(int(DUR * FPS)):
        t = i / FPS
        if 26 <= t and frozen is None:
            frozen = render(t - 1 / FPS).astype(np.float32)
        ff.stdin.write(render(t, frozen).tobytes())
        if i % 150 == 0:
            print(f"{t:.0f}s", flush=True)
    ff.stdin.close()
    ff.wait()

if __name__ == "__main__":
    if sys.argv[1] == "--still":
        # быстрый просмотр отдельных кадров: --still out_dir t1 t2 ...
        for ts in sys.argv[3:]:
            Image.fromarray(render(float(ts))).save(f"{sys.argv[2]}/f_{ts}.png")
    else:
        main(sys.argv[1], sys.argv[2])
