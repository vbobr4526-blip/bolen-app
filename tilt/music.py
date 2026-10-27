# Оригинальный тильт-фонк для ролика: 120 BPM, ковбелл, 808, клэп, хэты.
# Таймлайн совпадает с video.py: 0–8 спокойное интро, 8 выстрел + звон, 10 дроп,
# 22 перегруз, 26 «тейп-стоп», 27–31 грустный аутро.
import sys
import numpy as np
from scipy.signal import butter, sosfilt, fftconvolve
from scipy.io import wavfile

SR = 44100
DUR = 31.0
BEAT = 0.5          # 120 BPM
S16 = BEAT / 4
N = int(SR * DUR)
rng = np.random.default_rng(7)

def t_(sec):
    return np.arange(int(SR * sec)) / SR

def add(buf, sig, at, gain=1.0):
    i = int(at * SR)
    if i >= len(buf):
        return
    sig = sig[: len(buf) - i]
    buf[i : i + len(sig)] += sig * gain

def lp(x, f, order=2):
    return sosfilt(butter(order, f, "low", fs=SR, output="sos"), x)

def hp(x, f, order=2):
    return sosfilt(butter(order, f, "high", fs=SR, output="sos"), x)

def bp(x, lo, hi, order=2):
    return sosfilt(butter(order, [lo, hi], "band", fs=SR, output="sos"), x)

def note(n):
    # MIDI → Гц
    return 440.0 * 2 ** ((n - 69) / 12)

# ---------- инструменты ----------
def kick():
    t = t_(0.45)
    f = 45 + 110 * np.exp(-t * 28)
    ph = 2 * np.pi * np.cumsum(f) / SR
    return np.tanh(2.2 * np.sin(ph) * np.exp(-t * 7))

def bass808(n, length):
    t = t_(length)
    f = note(n) * (1 + 0.6 * np.exp(-t * 40))
    ph = 2 * np.pi * np.cumsum(f) / SR
    env = np.minimum(1, t * 200) * np.exp(-t * 1.1)
    return np.tanh(3.0 * np.sin(ph) * env) * 0.9

def clap():
    t = t_(0.3)
    nz = rng.standard_normal(len(t))
    env = np.exp(-t * 18)
    for d in (0.0, 0.011, 0.022):  # три «хлопка» подряд
        env += (t > d) * np.exp(-(t - d).clip(0) * 140) * 0.8
    return bp(nz, 900, 5000) * env * 0.55

def hat(open_=False):
    t = t_(0.25 if open_ else 0.05)
    return hp(rng.standard_normal(len(t)), 7000) * np.exp(-t * (14 if open_ else 90)) * 0.25

def cowbell(n, length=0.22):
    # 808-ковбелл: две квадратные волны в соотношении ~1.48, полосовой фильтр
    t = t_(length)
    f1 = note(n)
    sq = np.sign(np.sin(2 * np.pi * f1 * t)) + np.sign(np.sin(2 * np.pi * f1 * 1.48 * t))
    x = bp(sq, f1 * 0.8, f1 * 4)
    return x * np.exp(-t * 11) * 0.32

def gunshot():
    t = t_(1.2)
    nz = rng.standard_normal(len(t))
    crack = hp(nz, 1500) * np.exp(-t * 60)
    boom = lp(nz, 180, 4) * np.exp(-t * 6) * 6
    body = np.sin(2 * np.pi * (60 + 80 * np.exp(-t * 30)) * t) * np.exp(-t * 9)
    return np.tanh(1.5 * (crack + boom + body))

def tinnitus(length):
    t = t_(length)
    env = np.minimum(1, t * 3) * np.exp(-t * 0.9)
    return np.sin(2 * np.pi * 3950 * t) * env * 0.12

def riser(length):
    t = t_(length)
    nz = rng.standard_normal(len(t))
    out = np.zeros_like(nz)
    # нарастающий шум: склеиваем куски с растущей частотой среза
    k = 16
    seg = len(t) // k
    for i in range(k):
        s = slice(i * seg, (i + 1) * seg if i < k - 1 else len(t))
        out[s] = bp(nz[s], 300 + i * 500, 1200 + i * 900)
    return out * (t / length) ** 2 * 0.5

# ---------- аранжировка ----------
# Минор: ре минор. Ковбелл-рифф на 2 такта (32 шестнадцатых), None — пауза.
D4, F4, G4, A4, C5, D5, E5 = 62, 65, 67, 69, 72, 74, 76
RIFF = [D5, None, D5, A4, None, A4, C5, None, D5, None, F4, None, G4, A4, None, None,
        D5, None, D5, A4, None, A4, C5, None, E5, None, D5, None, C5, A4, G4, None]
BASS = [(0, 38, 1.5), (1.75, 38, 0.25), (2.0, 41, 1.0), (3.0, 43, 1.0)]  # (бит, нота, длина в битах)

music = np.zeros(N)
drums = np.zeros(N)
fx = np.zeros(N)

def riff(start, end, gain=1.0, octave=0):
    t = start
    i = 0
    while t < end - 1e-6:
        n = RIFF[i % len(RIFF)]
        if n is not None:
            add(music, cowbell(n + octave), t, gain)
        t += S16
        i += 1

def beat(start, end, hard=False):
    bar = start
    while bar < end - 1e-6:
        for b in range(4):
            bt = bar + b * BEAT
            if b in (0, 2) or (hard and b == 3):
                add(drums, kick(), bt, 0.9)
            if b in (1, 3):
                add(drums, clap(), bt, 0.9)
            # хэты шестнадцатыми, триольная дробь на последней доле
            if b == 3:
                for k in range(6):
                    add(drums, hat(), bt + k * BEAT / 6, 0.8)
            else:
                for k in range(4):
                    add(drums, hat(open_=(k == 2 and b == 1)), bt + k * S16, 0.7 if k % 2 else 1.0)
        for off, n, ln in BASS:
            add(music, bass808(n, ln * BEAT), bar + off * BEAT, 0.8)
        bar += 4 * BEAT

# 0–8: тёплое лоу-фай интро (будет приглушено фильтром)
riff(0, 7.5, 0.8)
for k in range(0, 15):
    add(drums, kick(), k * BEAT * 2, 0.35)
add(fx, riser(1.5), 6.5, 0.8)
# 8: выстрел из кустов и звон в ушах (контузия)
add(fx, gunshot(), 8.0, 1.0)
add(fx, tinnitus(2.0), 8.05, 1.0)
# 10–26: дроп
riff(10, 26, 1.0)
riff(18, 26, 0.45, octave=12)   # вторая октава — тильт растёт
beat(10, 22)
beat(22, 26, hard=True)
# 26–31: грустный аутро — ковбелл в два раза медленнее, на октаву ниже
t = 27.0
for i, n in enumerate(RIFF[:16]):
    if n is not None and t < 30.5:
        add(music, cowbell(n - 12, 0.5), t, 0.9)
    t += S16 * 2

mix = music + drums
# интро: лоу-пасс как из соседней комнаты
i8 = int(8 * SR)
mix[:i8] = lp(mix[:i8], 900, 2) * 1.4
# 22–26: перегруз и биткраш
a, b = int(22 * SR), int(26 * SR)
seg = np.tanh(mix[a:b] * 2.2)
step = 6
seg = np.repeat(seg[::step], step)[: b - a]
mix[a:b] = np.round(seg * 24) / 24 * 0.8
# 26–27: тейп-стоп — проигрываем такт с замедлением до нуля
src = mix[a - SR : a].copy()
ln = SR
rate = np.linspace(1, 0, ln) ** 1.3
pos = np.cumsum(rate)
pos = np.clip(pos, 0, len(src) - 2)
mix[b : b + ln] = np.interp(pos, np.arange(len(src)), src) * np.linspace(1, 0.2, ln)
mix[b + ln : int(27 * SR)] = 0
# аутро: мягко и с ревером
c = int(27 * SR)
ir_t = t_(2.2)
ir = rng.standard_normal(len(ir_t)) * np.exp(-ir_t * 2.5)
wet = fftconvolve(mix[c:], ir)[: N - c]
mix[c:] = lp(mix[c:] + wet / np.abs(wet).max() * 0.35, 1600)

mix += fx
# винил-треск везде, чуть громче в интро и аутро
crackle = (rng.random(N) > 0.9993) * rng.standard_normal(N) * 0.25
mix += lp(crackle, 4000)
fade = np.ones(N)
fade[-int(1.5 * SR):] = np.linspace(1, 0, int(1.5 * SR))
mix *= fade
mix = np.tanh(mix / np.abs(mix).max() * 1.4) * 0.92

out = sys.argv[1] if len(sys.argv) > 1 else "tilt.wav"
wavfile.write(out, SR, (np.stack([mix, mix], 1) * 32767).astype(np.int16))
print("ok", out)
