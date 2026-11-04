"""Genere les textures, modeles 3D et apercus du casino (distributeur de jetons, table de poker, arene de coqs, jetons en relief,
tables de blackjack et de roulette enrichies).

Usage depuis la racine du mod :  python tools/gen_casino.py
Les apercus PNG vont dans docs/ (rendu logiciel tools/rendu_modele.py, meme regles d'UV que le jeu).

Reperes :
 - modele d'un seul bloc : face avant = nord (z = 0), comme le modele « orientable » ;
 - tables de 3 x 2 blocs : on dessine dans un « repere table » TX (0..48, vers la DROITE du joueur), TZ (0..32, en s'eloignant
   du joueur), Y (hauteur). Le morceau (i, j) du bloc couvre TX 16i..16i+16, TZ 16j..16j+16 ; son numero est j*3 + i
   (CasinoBlock.partPos : i vers la droite du joueur, j vers l'arriere). Dans le modele du morceau, x local = 16 - (TX - 16i).
 - modele d'objet d'une table entiere : x = 32 - TX, z = TZ - 8.
 - arene de combats : 5 x 4 blocs (TX 0..80, TZ 0..64), morceau (i, j) numero j*5 + i ; modele d'objet reduit de moitie
   (un modele d'objet doit rester entre -16 et 32).
"""
import json
import math
import os
import random
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "minenorthcasino")
TEX = os.path.join(ASSETS, "textures", "block")
TEX_ITEM = os.path.join(ASSETS, "textures", "item")
MODELS = os.path.join(ASSETS, "models", "block")
ITEMS = os.path.join(ASSETS, "models", "item")
STATES = os.path.join(ASSETS, "blockstates")
LOOT = os.path.join(ROOT, "src", "main", "resources", "data", "minenorthcasino", "loot_tables", "blocks")
DOCS = os.path.join(ROOT, "docs")
NS = "minenorthcasino:block/"
sys.path.insert(0, HERE)
import rendu_modele  # noqa: E402

GLOW = {"block_light": 15, "sky_light": 15, "ambient_occlusion": False}
FACES6 = ("north", "south", "east", "west", "up", "down")

# ---------------------------------------------------------------- atlas de couleurs (cases de 8 x 8 px sur 64 x 64)
CELLS = {}
_order = []


def cell(name, rgb):
    _order.append(name)
    CELLS[name] = rgb


for _n, _c in [
    ("felt", 0x0E6A4C), ("felt_dk", 0x0A4A36), ("wood", 0x6B3518), ("wood_dk", 0x3E1D0C), ("wood_lt", 0xA2632E),
    ("leather", 0x231418), ("leather_hi", 0x3A2228), ("gold", 0xE8B923), ("gold_dk", 0xA67C12), ("chrome", 0xDCE2EA),
    ("black", 0x15131A), ("metal_dk", 0x2A2238), ("white", 0xF4F2F6), ("red", 0xC8283C), ("red_dk", 0x7A1422),
    ("blue", 0x2B5DE9), ("green", 0x1E9E52), ("purple", 0x6A2CBF), ("pink", 0xFF3EA5), ("orange", 0xFF8A3D),
    ("cyan", 0x2FD4E0), ("glass", 0x1B1530), ("sand", 0xC9A066), ("sand_dk", 0x9C7440), ("straw", 0xD9B54A),
    ("straw_dk", 0xA9862C), ("plank", 0x7A5A3A), ("plank_dk", 0x4F3824), ("tarp", 0x4E5A3A), ("tarp_dk", 0x343D26),
    ("crate", 0x9A7442), ("crate_dk", 0x5E4424), ("iron", 0x5C5F66), ("chip_w", 0xE8E8EE), ("chip_k", 0x1E1E24),
    ("chip_p", 0x7B2CBF), ("cream", 0xF3E7C9), ("card_back", 0xB0206E), ("money", 0x5E9E5A), ("yellow", 0xF5C400),
    ("lamp", 0xFFB347), ("rope", 0xB89A6A), ("chip_r", 0xC8283C), ("chip_g", 0x1E9E52), ("neon_pink", 0xFF5CC8),
    ("neon_cyan", 0x6CF2FF),
    # grande arene des animaux (ajoutees a la fin : les UV des cases existantes ne bougent pas)
    ("rust", 0x8A4B2A), ("concrete", 0x8C8A84), ("tire", 0x1C1C1E), ("hazard", 0xF2B705),
]:
    cell(_n, _c)


def uv(name):
    i = _order.index(name)
    col, row = i % 8, i // 8
    return [col * 2 + 0.25, row * 2 + 0.25, col * 2 + 1.75, row * 2 + 1.75]


def atlas():
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    r = random.Random(11)
    for i, name in enumerate(_order):
        base = CELLS[name]
        for x in range(8):
            for y in range(8):
                n = 0 if name.startswith("neon") or name in ("lamp",) else r.randint(-5, 5)
                if name in ("plank", "plank_dk", "wood", "wood_dk", "wood_lt", "crate", "crate_dk") and y % 4 == 0:
                    n -= 10   # veinage / lattes
                if name in ("tarp", "tarp_dk") and (x + y) % 3 == 0:
                    n -= 6
                if name.startswith("chip_") and x in (2, 5):
                    base2 = 0xF4F4F4 if name != "chip_w" else 0x2B5DE9   # encoches du bord des jetons
                    im.putpixel(((i % 8) * 8 + x, (i // 8) * 8 + y), rgba(base2, 0))
                    continue
                im.putpixel(((i % 8) * 8 + x, (i // 8) * 8 + y), rgba(base, n))
    save(im, TEX, "casino_atlas.png")


def rgba(c, n=0):
    return (clamp(((c >> 16) & 255) + n), clamp(((c >> 8) & 255) + n), clamp((c & 255) + n), 255)


def clamp(v):
    return max(0, min(255, v))


def save(im, folder, name):
    os.makedirs(folder, exist_ok=True)
    im.save(os.path.join(folder, name))


# ---------------------------------------------------------------- petite police 3 x 5 pour les enseignes
FONT = {
    "A": "010101111101101", "B": "110101110101110", "C": "011100100100011", "D": "110101101101110", "E": "111100110100111",
    "F": "111100110100100", "G": "011100101101011", "H": "101101111101101", "I": "111010010010111", "J": "001001001101010",
    "K": "101101110101101", "L": "100100100100111", "M": "101111111101101", "N": "110101101101101", "O": "010101101101010",
    "P": "110101110100100", "R": "110101110101101", "S": "011100010001110", "T": "111010010010010", "U": "101101101101111",
    "V": "101101101101010", "X": "101101010101101", "Y": "101101010010010", "Z": "111001010100111", "0": "111101101101111",
    "1": "010110010010111", "2": "110001010100111", "3": "110001010001110", "4": "101101111001001", "5": "111100110001110",
    "6": "011100111101111", "7": "111001010010010", "8": "111101111101111", "9": "111101111001110", "€": "011100110100011",
    " ": "000000000000000", "!": "010010010000010", "-": "000000111000000", "/": "001001010100100", ".": "000000000000010",
}


def text(im, s, x, y, color, scale=1, glow=None):
    for k, ch in enumerate(s):
        g = FONT.get(ch, FONT[" "])
        for i in range(15):
            if g[i] == "1":
                gx, gy = x + k * 4 * scale + (i % 3) * scale, y + (i // 3) * scale
                for dx in range(scale):
                    for dy in range(scale):
                        if glow is not None:
                            for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                                px, py = gx + dx + ox, gy + dy + oy
                                if 0 <= px < im.width and 0 <= py < im.height and im.getpixel((px, py))[:3] != color[:3]:
                                    im.putpixel((px, py), glow)
                        if 0 <= gx + dx < im.width and 0 <= gy + dy < im.height:
                            im.putpixel((gx + dx, gy + dy), color)


def text_width(s, scale=1):
    return len(s) * 4 * scale - scale


# ---------------------------------------------------------------- textures dessinees
def vgrad(im, box, top, bottom):
    x0, y0, x1, y1 = box
    for y in range(y0, y1):
        t = (y - y0) / max(1, y1 - y0 - 1)
        c = tuple(int(top[k] + (bottom[k] - top[k]) * t) for k in range(3)) + (255,)
        for x in range(x0, x1):
            im.putpixel((x, y), c)


def tex_dispenser():
    # ecran tactile 32 x 32 : degrade coucher de soleil, « JETONS », pile de jetons, boutons ACHAT / RACHAT
    im = Image.new("RGBA", (32, 32), (14, 8, 30, 255))
    vgrad(im, (1, 1, 31, 31), (58, 20, 110), (12, 8, 32))
    d = ImageDraw.Draw(im)
    d.ellipse((18, 4, 28, 14), fill=(255, 138, 61, 255))           # soleil
    for y in (8, 10, 12):
        d.line((18, y, 28, y), fill=(58, 20, 110, 255))
    for k, x in enumerate((3, 6, 9)):
        d.rectangle((x, 6 - k, x + 1, 14), fill=(30, 12, 50, 255))   # palmiers stylises
    text(im, "JETONS", 4, 17, (255, 92, 200, 255))
    cols = [(232, 232, 238), (200, 40, 60), (30, 158, 82), (30, 30, 36), (123, 44, 191)]
    for k, c in enumerate(cols):
        x = 3 + k * 5
        d.rectangle((x, 24, x + 3, 25), fill=c + (255,))
        d.rectangle((x, 26, x + 3, 27), fill=c + (255,))
    d.rectangle((0, 0, 31, 0), fill=(255, 62, 165, 255))
    d.rectangle((0, 31, 31, 31), fill=(255, 138, 61, 255))
    save(im, TEX, "dispenser_screen.png")

    # enseigne lumineuse 64 x 16 : « CASINO » en neon rose, liseres cyan
    im = Image.new("RGBA", (64, 16), (16, 8, 28, 255))
    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, 63, 0), fill=(108, 242, 255, 255))
    d.rectangle((0, 15, 63, 15), fill=(108, 242, 255, 255))
    w = text_width("CASINO", 2)
    text(im, "CASINO", (64 - w) // 2, 3, (255, 214, 240, 255), 2, glow=(255, 62, 165, 255))
    for x in (3, 60):
        d.ellipse((x - 1, 6, x + 1, 9), fill=(255, 194, 61, 255))
    save(im, TEX, "dispenser_marquee.png")

    # face du lecteur de carte 16 x 16
    im = Image.new("RGBA", (16, 16), (21, 19, 26, 255))
    d = ImageDraw.Draw(im)
    d.rectangle((2, 3, 13, 4), fill=(5, 5, 8, 255))
    d.rectangle((3, 8, 12, 12), fill=(40, 34, 56, 255))
    for k in range(3):
        for m in range(3):
            d.point((4 + k * 3, 9 + m * 1), fill=(160, 150, 190, 255))
    d.rectangle((12, 1, 13, 2), fill=(108, 255, 154, 255))
    save(im, TEX, "dispenser_reader.png")


def tex_poker_felt():
    """Tapis de poker vu par le joueur (96 x 64 = 48 x 32 unites) : feutre, ligne doree des mises, logo, places."""
    im = Image.new("RGBA", (96, 64), (14, 106, 76, 255))
    r = random.Random(5)
    for x in range(96):
        for y in range(64):
            dx, dy = (x - 47.5) / 48, (y - 31.5) / 32
            v = max(0.0, 1 - (dx * dx + dy * dy))
            n = r.randint(-3, 3)
            c = (int(10 + 10 * v) + n, int(74 + 48 * v) + n, int(54 + 30 * v) + n, 255)
            im.putpixel((x, y), c)
    d = ImageDraw.Draw(im)
    d.ellipse((14, 12, 81, 51), outline=(232, 185, 35, 255))          # ligne des mises
    d.ellipse((15, 13, 80, 50), outline=(166, 124, 18, 255))
    # emplacement des cartes communes
    for k in range(5):
        x = 33 + k * 6
        d.rectangle((x, 28, x + 4, 34), outline=(120, 200, 160, 255))
    w = text_width("MINENORTH")
    text(im, "MINENORTH", (96 - w) // 2, 39, (255, 194, 61, 255))
    w = text_width("POKER")
    text(im, "POKER", (96 - w) // 2, 19, (255, 92, 200, 255))
    # cercles des mises des places (devant les joueurs : bas et cotes)
    for (cx, cy) in ((22, 44), (48, 48), (74, 44), (14, 30), (82, 30)):
        d.ellipse((cx - 2, cy - 2, cx + 2, cy + 2), outline=(232, 185, 35, 255))
    save(im, TEX, "poker_felt.png")


def tex_chip_edges():
    pass   # les bords des jetons viennent de l'atlas (cases chip_*)


# ---------------------------------------------------------------- elements
def face(tex, uvv, glow=False, rot=0):
    f = {"texture": tex, "uv": [round(v, 4) for v in uvv]}
    if rot:
        f["rotation"] = rot
    if glow:
        f["forge_data"] = dict(GLOW)
    return f


def cube(f, t, cellname=None, faces=FACES6, glow=False, tex=None, rotation=None):
    """Element d'un seul bloc ; toutes les faces dans la case {@code cellname} de l'atlas, ou {@code tex} = {face: dict}."""
    el = {"from": [round(v, 4) for v in f], "to": [round(v, 4) for v in t], "faces": {}}
    for n in faces:
        if tex and n in tex:
            el["faces"][n] = tex[n]
        elif cellname:
            el["faces"][n] = face("#atlas", uv(cellname), glow)
    if rotation:
        el["rotation"] = rotation
    return el


class TBox:
    """Boite dans le repere table. faces : {nom: case} avec les noms up/down/front/back/left/right ; 'felt' = tapis texture."""

    def __init__(self, x0, y0, z0, x1, y1, z1, faces, glow=()):
        self.b = (x0, y0, z0, x1, y1, z1)
        self.faces = faces
        self.glow = set(glow)


def tb(x0, y0, z0, x1, y1, z1, c, skip=(), glow=False, top=None):
    f = {n: c for n in ("up", "down", "front", "back", "left", "right") if n not in skip}
    if top:
        f["up"] = top
    return TBox(x0, y0, z0, x1, y1, z1, f, f.keys() if glow else ())


# noms : repere table -> modele (TX vers la droite du joueur = -x du modele)
TO_LOCAL = {"up": "up", "down": "down", "front": "north", "back": "south", "left": "east", "right": "west"}


def felt_uv(txa, tza, txb, tzb, w=48.0, d=32.0):
    """UV dans la texture du tapis (vue joueur : TX vers la droite, le fond de la table en haut)."""
    return [txa / w * 16, (d - tza) / d * 16, txb / w * 16, (d - tzb) / d * 16]


def split(boxes, felt_tex="#felt", cols=3, rows=2):
    """Decoupe des boites du repere table en cols x rows modeles de morceaux. Les faces sur les plans de coupe sont retirees."""
    parts = {i: [] for i in range(cols * rows)}
    for bx in boxes:
        x0, y0, z0, x1, y1, z1 = bx.b
        for i in range(cols):
            for j in range(rows):
                cx0, cx1 = max(x0, 16 * i), min(x1, 16 * i + 16)
                cz0, cz1 = max(z0, 16 * j), min(z1, 16 * j + 16)
                if cx1 - cx0 <= 1e-6 or cz1 - cz0 <= 1e-6:
                    continue
                lx0, lx1 = 16 - (cx1 - 16 * i), 16 - (cx0 - 16 * i)
                lz0, lz1 = cz0 - 16 * j, cz1 - 16 * j
                el = {"from": [round(lx0, 4), y0, round(lz0, 4)], "to": [round(lx1, 4), y1, round(lz1, 4)], "faces": {}}
                cut = {"left": cx0 > x0, "right": cx1 < x1, "front": cz0 > z0, "back": cz1 < z1}
                for n, c in bx.faces.items():
                    if cut.get(n):
                        continue
                    if c == "felt":
                        if n not in ("up", "down"):
                            continue
                        # x local croissant = TX decroissant
                        el["faces"][TO_LOCAL[n]] = face(felt_tex, felt_uv(cx1, cz0, cx0, cz1))
                    else:
                        el["faces"][TO_LOCAL[n]] = face("#atlas", uv(c), n in bx.glow)
                if el["faces"]:
                    parts[j * cols + i].append(el)
    return parts


def whole(boxes, felt_tex="#felt", width=48, depth=32, k=1.0):
    """Toute la table dans un seul modele (objet), centree sur le bloc, reduite d'un facteur k : x = 8 + (width/2 - TX) * k."""
    out = []
    for bx in boxes:
        x0, y0, z0, x1, y1, z1 = bx.b
        if k == 1.0:   # tables de 3 x 2 : formule d'origine (memes nombres ecrits)
            el = {"from": [round(width // 2 + 8 - x1, 4), y0, round(z0 - (depth // 2 - 8), 4)],
                  "to": [round(width // 2 + 8 - x0, 4), y1, round(z1 - (depth // 2 - 8), 4)], "faces": {}}
        else:
            fx = lambda tx: round(8 + (width / 2 - tx) * k, 4)
            fz = lambda tz: round(8 + (tz - depth / 2) * k, 4)
            el = {"from": [fx(x1), round(y0 * k, 4), fz(z0)], "to": [fx(x0), round(y1 * k, 4), fz(z1)], "faces": {}}
        for n, c in bx.faces.items():
            if c == "felt":
                if n in ("up", "down"):
                    el["faces"][TO_LOCAL[n]] = face(felt_tex, felt_uv(x1, z0, x0, z1))
                continue
            el["faces"][TO_LOCAL[n]] = face("#atlas", uv(c), n in bx.glow)
        out.append(el)
    return out


def superellipse(a, b, x, n=2.6):
    t = abs(x) / a
    if t >= 1:
        return 0.0
    return b * (1 - t ** n) ** (1 / n)


def q(v, s=0.5):
    return round(v / s) * s


def stadium(cx, cz, a, b, y0, y1, c, step=2.0, n=2.6, top=None, skip=()):
    """Disque « stade » plein, en bandes de {@code step} unites selon TX (bandes de meme profondeur fusionnees)."""
    strips = []
    x = cx - a
    while x < cx + a - 1e-6:
        xa, xb = x, min(cx + a, x + step)
        h = q(superellipse(a, b, (xa + xb) / 2 - cx, n))
        if h > 0:
            if strips and strips[-1][2] == h and abs(strips[-1][1] - xa) < 1e-6:
                strips[-1][1] = xb
            else:
                strips.append([xa, xb, h])
        x = xb
    return [tb(s[0], y0, cz - s[2], s[1], y1, cz + s[2], c, skip=skip, top=top) for s in strips]


def ring(cx, cz, ao, bo, ai, bi, y0, y1, c, step=2.0, n=2.6, top=None):
    """Anneau « stade » (bord, boudin) : pour chaque bande, l'avant et l'arriere ; plein aux extremites."""
    out = []
    x = cx - ao
    while x < cx + ao - 1e-6:
        xa, xb = x, min(cx + ao, x + step)
        m = (xa + xb) / 2 - cx
        ho, hi = q(superellipse(ao, bo, m, n)), q(superellipse(ai, bi, m, n))
        if ho > 0:
            if hi <= 0:
                out.append(tb(xa, y0, cz - ho, xb, y1, cz + ho, c, top=top))
            else:
                out.append(tb(xa, y0, cz - ho, xb, y1, cz - hi, c, top=top))
                out.append(tb(xa, y0, cz + hi, xb, y1, cz + ho, c, top=top))
        x = xb
    return out


def chip_stack(x, z, y, colors, size=1.6):
    """Pile de jetons : couches de couleur, une fine bande blanche entre chaque."""
    out = []
    for k, c in enumerate(colors):
        out.append(tb(x, y + k * 0.5, z, x + size, y + k * 0.5 + 0.4, z + size, c))
        out.append(tb(x + 0.05, y + k * 0.5 + 0.4, z + 0.05, x + size - 0.05, y + k * 0.5 + 0.5, z + size - 0.05, "white"))
    return out


# ---------------------------------------------------------------- ecriture des modeles / etats
def clamp_elements(elements):
    """Minecraft refuse TOUT le modele (damier noir et violet) si un element sort de -16..32 : on borne chaque coordonnee."""
    for e in elements:
        e["from"] = [max(-16, min(32, v)) for v in e["from"]]
        e["to"] = [max(-16, min(32, v)) for v in e["to"]]
    return elements


def write_model(name, textures, elements, folder=MODELS, extra=None):
    m = {"parent": "minecraft:block/block", "textures": dict(textures), "elements": clamp_elements(elements)}
    if extra:
        m.update(extra)
    os.makedirs(folder, exist_ok=True)
    with open(os.path.join(folder, name + ".json"), "w", encoding="utf-8") as f:
        json.dump(m, f, indent=1, ensure_ascii=False)
    return m


TABLE_DISPLAY = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.3, 0.3, 0.3]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.18, 0.18, 0.18]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.24, 0.24, 0.24]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.135, 0.135, 0.135]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 2, 0], "scale": [0.135, 0.135, 0.135]},
}


def multipart_state(name, models_by_part, extra_prop=None, parts=6):
    """Etat de bloc : facing x part (x open si extra_prop = ('open', {True: prefixe, False: prefixe}))."""
    rot = {"north": 0, "east": 90, "south": 180, "west": 270}
    v = {}
    for fac, y in rot.items():
        for p in range(parts):
            if extra_prop:
                for val, prefix in extra_prop[1].items():
                    v["facing=%s,%s=%s,part=%d" % (fac, extra_prop[0], "true" if val else "false", p)] = {
                        "model": NS + "%s_p%d" % (prefix, p), "y": y}
            else:
                v["facing=%s,part=%d" % (fac, p)] = {"model": NS + models_by_part(p), "y": y}
    with open(os.path.join(STATES, name + ".json"), "w", encoding="utf-8") as f:
        json.dump({"variants": v}, f, indent=1)


def loot(name):
    os.makedirs(LOOT, exist_ok=True)
    data = {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "minenorthcasino:" + name}],
            "conditions": [{"condition": "minecraft:survives_explosion"}, {"condition": "minecraft:block_state_property", "block": "minenorthcasino:" + name, "properties": {"part": "0"}}]}]}
    with open(os.path.join(LOOT, name + ".json"), "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1)


# ---------------------------------------------------------------- distributeur de jetons (caisse)
def dispenser():
    t = {"atlas": NS + "casino_atlas", "screen": NS + "dispenser_screen", "marquee": NS + "dispenser_marquee",
         "reader": NS + "dispenser_reader", "particle": NS + "casino_atlas"}
    els = []
    # socle noir, filet dore, neon rose au ras du sol
    els.append(cube([0.5, 0, 0.5], [15.5, 1.5, 15.5], "black"))
    els.append(cube([0.8, 1.5, 0.8], [15.2, 2, 15.2], "gold"))
    els.append(cube([1, 0.3, 0.3], [15, 0.9, 0.5], "neon_pink", faces=("north",), glow=True))
    # corps violet nuit, arrondi par des chanfreins verticaux
    els.append(cube([2, 2, 3], [14, 13, 14], "metal_dk"))
    els.append(cube([1.4, 2, 3.6], [2, 13, 13.4], "purple"))
    els.append(cube([14, 2, 3.6], [14.6, 13, 13.4], "purple"))
    # colonnes de neon aux angles avant (rose a gauche, cyan a droite)
    els.append(cube([1.3, 2.2, 2.4], [2.1, 13, 3.2], "neon_pink", glow=True))
    els.append(cube([13.9, 2.2, 2.4], [14.7, 13, 3.2], "neon_cyan", glow=True))
    # panneau bas, bac de sortie chrome avec des jetons
    els.append(cube([2.5, 2, 2.4], [13.5, 7, 3], "black"))
    els.append(cube([3.5, 6, 0.8], [12.5, 6.6, 3], "chrome"))
    els.append(cube([3.5, 6.6, 0.8], [4, 8, 3], "chrome"))
    els.append(cube([12, 6.6, 0.8], [12.5, 8, 3], "chrome"))
    els.append(cube([3.5, 6.6, 0.8], [12.5, 8, 1.3], "chrome"))
    for k, c in enumerate(("chip_r", "chip_g", "chip_k", "chip_p", "chip_w")):
        x = 4.4 + k * 1.55
        els.append(cube([x, 6.6, 1.5], [x + 1.3, 7.1 + (k % 2) * 0.4, 2.8], c))
    # ecran tactile incline et son cadre dore
    tilt = {"origin": [8, 8, 3], "axis": "x", "angle": -22.5}
    els.append(cube([3, 7.8, 2.4], [13, 12.8, 3.2], "gold", rotation=tilt))
    els.append(cube([3.5, 8.2, 2.2], [12.5, 12.4, 2.5], None, faces=("north",),
                    tex={"north": face("#screen", [0, 0, 16, 16], glow=True)}, rotation=tilt))
    # lecteur de carte bancaire, a droite du bac
    els.append(cube([10.6, 2.6, 1.8], [13, 5.6, 2.6], "black", faces=("east", "west", "up", "down", "south")))
    els.append(cube([10.6, 2.6, 1.8], [13, 5.6, 2.6], None, faces=("north",), tex={"north": face("#reader", [0, 0, 16, 16])}))
    els.append(cube([3, 2.6, 1.8], [5.4, 5.6, 2.6], "gold"))   # fente des billets (decor)
    els.append(cube([3.3, 3.8, 1.7], [5.1, 4.2, 1.8], "black", faces=("north",)))
    # enseigne lumineuse « CASINO » en haut, chapeau dore
    els.append(cube([1.2, 13, 2], [14.8, 16, 14.2], "gold", faces=("south", "east", "west", "down")))
    els.append(cube([1.2, 13, 2], [14.8, 16, 14.2], None, faces=("north",), tex={"north": face("#marquee", [0, 0, 16, 16], glow=True)}))
    els.append(cube([1.2, 15.6, 2], [14.8, 16, 14.2], "gold_dk", faces=("up",)))
    els.append(cube([6.5, 16, 7], [9.5, 16.6, 10], "gold"))   # gros jeton pose sur le dessus
    els.append(cube([7, 16.6, 7.5], [9, 16.8, 9.5], "chip_p"))
    display = {"display": {
        "gui": {"rotation": [30, 225, 0], "translation": [0, -0.5, 0], "scale": [0.6, 0.6, 0.6]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
        "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]}}}
    write_model("caisse_casino", t, els, extra=display)
    with open(os.path.join(ITEMS, "caisse_casino.json"), "w", encoding="utf-8") as f:
        json.dump({"parent": "minenorthcasino:block/caisse_casino"}, f, indent=1)


# ---------------------------------------------------------------- table de poker
def poker_boxes():
    B = []
    cx, cz = 24, 16
    # pieds : deux socles ronds, colonnes noires baguees d'or
    for px in (14, 34):
        B += stadium(px, cz, 5, 5, 0, 1, "black", step=2, n=2.2)
        B += stadium(px, cz, 4, 4, 1, 1.5, "gold", step=2, n=2.2)
        B.append(tb(px - 2, 1.5, cz - 2, px + 2, 9.5, cz + 2, "black"))
        B.append(tb(px - 2.3, 6, cz - 2.3, px + 2.3, 7, cz + 2.3, "gold"))
    # ceinture sous le plateau
    B += stadium(cx, cz, 19, 11, 9.5, 10.5, "wood_dk")
    # bord en acajou, plateau, piste en bois clair, tapis
    B += ring(cx, cz, 23.5, 15.5, 20.5, 12.5, 10.5, 12.5, "wood")
    B += stadium(cx, cz, 20.5, 12.5, 10.5, 12, "wood_dk", skip=("up",))
    B += ring(cx, cz, 20.5, 12.5, 19, 11, 12, 13.2, "wood_lt")
    B += stadium(cx, cz, 19, 11, 12, 13, "felt_dk", top="felt")
    # boudin en cuir capitonne (deux tons)
    B += ring(cx, cz, 23.5, 15.5, 20.5, 12.5, 12.5, 14, "leather")
    B += ring(cx, cz, 23, 15, 21, 13, 14, 14.6, "leather_hi")
    # porte-gobelets dores dans le boudin, devant chaque place
    for (x, z) in ((10, 1.6), (24, 0.9), (38, 1.6), (10, 28.9), (38, 28.9)):
        B.append(tb(x - 0.8, 14.6, z, x + 0.8, 14.75, z + 1.6, "gold"))
    # rack du croupier (fond de table) avec ses colonnes de jetons
    B.append(tb(17, 13, 23.5, 31, 13.9, 26.6, "black"))
    for k, c in enumerate(("chip_w", "chip_r", "chip_g", "chip_k", "chip_p", "chip_r", "chip_w")):
        B.append(tb(17.4 + k * 1.95, 13.9, 23.9, 19.0 + k * 1.95, 14.5, 26.2, c))
    # sabot de cartes
    B.append(tb(31.6, 13, 23.4, 34.2, 14.2, 26.4, "black"))
    B.append(tb(31.8, 14.2, 23.6, 34, 14.4, 26.2, "card_back"))
    # cinq cartes communes et le bouton du donneur
    for k in range(5):
        x = 17.2 + k * 2.9
        B.append(tb(x, 13, 14.5, x + 2.3, 13.12, 17.8, "white"))
        B.append(tb(x + 0.5, 13.12, 15.5, x + 1.1, 13.16, 16.3, "red" if k % 2 else "black", skip=("down",)))
    B.append(tb(29, 13, 19, 30.6, 13.35, 20.6, "white"))
    # piles de jetons devant quelques places
    B += chip_stack(9.5, 8.5, 13, ["chip_r", "chip_r", "chip_k"])
    B += chip_stack(11.4, 8.8, 13, ["chip_g", "chip_g"])
    B += chip_stack(23, 6.4, 13, ["chip_p", "chip_k", "chip_k", "chip_r"])
    B += chip_stack(36.2, 8.6, 13, ["chip_w", "chip_w", "chip_r"])
    B += chip_stack(6.2, 18, 13, ["chip_k", "chip_g"])
    B += chip_stack(40.4, 18.4, 13, ["chip_p", "chip_p", "chip_w"])
    # cartes de deux joueurs, posees de biais (simples plaques)
    B.append(tb(13.5, 13, 10.5, 15.7, 13.1, 13.4, "card_back"))
    B.append(tb(31.5, 13, 10.5, 33.7, 13.1, 13.4, "card_back"))
    return B


def poker():
    tex_poker_felt()
    t = {"atlas": NS + "casino_atlas", "felt": NS + "poker_felt", "particle": NS + "casino_atlas"}
    boxes = poker_boxes()
    for p, els in split(boxes).items():
        write_model("table_poker_p%d" % p, t, els)
    write_model("table_poker", t, whole(boxes), folder=ITEMS, extra={"display": TABLE_DISPLAY})
    multipart_state("table_poker", lambda p: "table_poker_p%d" % p)
    loot("table_poker")


# ---------------------------------------------------------------- blackjack et roulette : cadre refait, tapis conserves
def frame_boxes(kind):
    B = []
    # ceinture en bois sombre avec filet dore, quatre pieds tournes a sabots dores
    B.append(tb(1.2, 7.5, 1.2, 46.8, 11, 30.8, "wood_dk", skip=("up",)))
    B.append(tb(1, 9.8, 1, 47, 10.4, 31, "gold", skip=("up", "down")))
    for (x, z) in ((2, 2), (43, 2), (2, 27), (43, 27)):
        B.append(tb(x + 0.5, 1, z + 0.5, x + 2.5, 7.5, z + 2.5, "wood"))
        B.append(tb(x, 5.5, z, x + 3, 6.5, z + 3, "wood_dk"))
        B.append(tb(x, 0, z, x + 3, 1, z + 3, "gold"))
    # boudin en cuir devant et sur les cotes
    B.append(tb(0, 14, -0.5, 48, 15.4, 2, "leather"))
    B.append(tb(0.3, 15.4, -0.3, 47.7, 15.8, 1.6, "leather_hi"))
    B.append(tb(-0.5, 14, 2, 2, 15.4, 32, "leather"))
    B.append(tb(46, 14, 2, 48.5, 15.4, 32, "leather"))
    B.append(tb(-0.3, 15.4, 2, 1.6, 15.8, 31.6, "leather_hi"))
    B.append(tb(46.4, 15.4, 2, 48.3, 15.8, 31.6, "leather_hi"))
    if kind == "blackjack":
        # rack de jetons du croupier et sabot, en relief au-dessus des dessins du tapis
        B.append(tb(16, 14, 26.4, 29.5, 14.8, 29.6, "black"))
        for k, c in enumerate(("chip_w", "chip_r", "chip_g", "chip_k", "chip_p", "chip_w")):
            B.append(tb(16.4 + k * 2.15, 14.8, 26.8, 18.3 + k * 2.15, 15.4, 29.2, c))
        B.append(tb(36, 14, 25.6, 43, 16.2, 29.4, "red_dk"))
        B.append(tb(36.2, 16.2, 25.8, 42.8, 16.5, 29.2, "card_back"))
        B.append(tb(36.5, 14.6, 25.2, 42.5, 15.8, 25.6, "white", skip=("down", "back")))
        # quelques jetons sur les cases des joueurs
        B += chip_stack(11, 9, 14, ["chip_r", "chip_r"], 1.4)
        B += chip_stack(23.3, 5.8, 14, ["chip_g", "chip_k", "chip_k"], 1.4)
        B += chip_stack(35.4, 9.6, 14, ["chip_p"], 1.4)
    else:
        # cuvette de la roue en relief (bord en bois, filet dore), jetons poses sur le tapis
        B += ring(8.5, 16, 7, 7, 5.6, 5.6, 14, 15.2, "wood_dk", step=1.4, n=2.0)
        B += ring(8.5, 16, 5.6, 5.6, 5, 5, 14, 14.5, "gold", step=1.2, n=2.0)
        B += chip_stack(27, 22, 14, ["chip_r", "chip_r"], 1.4)
        B += chip_stack(34, 10, 14, ["chip_k", "chip_g", "chip_g"], 1.4)
        B += chip_stack(40.5, 25, 14, ["chip_p"], 1.4)
    return B


BASE_TABLES = os.path.join(HERE, "plateaux_tables.json")


def table_bases():
    """Plateaux d'origine (tapis textures et roue, elements au-dessus de y = 11) des tables de blackjack et de roulette.
    Releves une fois dans les modeles d'avant le nouveau cadre, puis relus ici : le script peut etre relance sans rien empiler."""
    if os.path.exists(BASE_TABLES):
        return json.load(open(BASE_TABLES, encoding="utf-8"))
    data = {}
    for kind in ("blackjack", "roulette"):
        for p in range(6):
            m = json.load(open(os.path.join(MODELS, "table_%s_p%d.json" % (kind, p)), encoding="utf-8"))
            data["%s_p%d" % (kind, p)] = [e for e in m["elements"] if e["from"][1] >= 11]
        m = json.load(open(os.path.join(ITEMS, "table_%s.json" % kind), encoding="utf-8"))
        data["%s_item" % kind] = [e for e in m["elements"] if e["from"][1] >= 11]
    with open(BASE_TABLES, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1)
    return data


def upgrade_table(kind):
    """Garde le plateau texture d'origine (et la roue), remplace le reste par le nouveau cadre."""
    bases = table_bases()
    base = "table_" + kind
    boxes = frame_boxes(kind)
    parts = split(boxes)
    for p in range(6):
        path = os.path.join(MODELS, "%s_p%d.json" % (base, p))
        m = json.load(open(path, encoding="utf-8"))
        m["textures"]["atlas"] = NS + "casino_atlas"
        m["elements"] = bases["%s_p%d" % (kind, p)] + parts[p]
        with open(path, "w", encoding="utf-8") as f:
            json.dump(m, f, indent=1, ensure_ascii=False)
    path = os.path.join(ITEMS, base + ".json")
    m = json.load(open(path, encoding="utf-8"))
    m["textures"]["atlas"] = NS + "casino_atlas"
    extra = whole(boxes)
    if kind == "roulette":
        extra.append({"from": [21.5, 14, 6], "to": [25.5, 18, 10], "faces": {n: face("#spindle", [0, 0, 16, 16]) for n in FACES6}})
    m["elements"] = clamp_elements(bases["%s_item" % kind] + extra)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(m, f, indent=1, ensure_ascii=False)


# ---------------------------------------------------------------- arene de combats de coqs (ouverte / fermee)
ARENA_W, ARENA_D = 80, 64   # 5 x 4 blocs


def arena_open_boxes():
    B = []
    cx, cz = 40, 32
    B.append(tb(0, 0, 0, ARENA_W, 0.5, ARENA_D, "sand_dk", skip=("down",)))
    # palissade en planches usees, chapeau plus sombre, poteaux tout autour
    B += ring(cx, cz, 37, 29, 34, 26, 0.5, 8.5, "plank", n=3.0)
    B += ring(cx, cz, 37.4, 29.4, 33.8, 25.8, 8.5, 9.3, "plank_dk", n=3.0)
    for (x, z) in ((3.4, 31.4), (75.4, 31.4), (12, 5.6), (28, 3.4), (51, 3.4), (67, 5.6), (12, 57.2), (28, 59.4), (51, 59.4), (67, 57.2)):
        B.append(tb(x, 0.5, z, x + 1.4, 10.5, z + 1.4, "plank_dk"))
    # sable du ring, taches, cercle trace au sol et ligne de depart des deux combattants
    B += stadium(cx, cz, 34, 26, 0.5, 1.2, "sand", n=3.0)
    for (x, z, w, d) in ((30, 22, 4, 3), (48, 38, 3, 4), (36, 42, 2, 2), (55, 24, 3, 1.6), (22, 34, 2.5, 2.5)):
        B.append(tb(x, 1.2, z, x + w, 1.26, z + d, "sand_dk", skip=("down",)))
    B += ring(cx, cz, 13, 9, 12.2, 8.2, 1.2, 1.28, "sand_dk", step=1.0, n=2.0)
    B.append(tb(23, 1.2, 30.5, 25, 1.3, 33.5, "white", skip=("down",)))
    B.append(tb(55, 1.2, 30.5, 57, 1.3, 33.5, "white", skip=("down",)))
    # gradins de spectateurs (bottes de paille) devant, et sur les cotes
    for x in (8, 20, 52, 64):
        B.append(tb(x, 0.5, 0.4, x + 8, 4, 4.4, "straw"))
        B.append(tb(x + 0.3, 4, 0.8, x + 7.7, 4.3, 4, "straw_dk"))
    B.append(tb(0.3, 0.5, 14, 4.3, 4, 24, "straw"))
    B.append(tb(75.7, 0.5, 40, 79.7, 4, 50, "straw"))
    B.append(tb(76, 4, 41, 79.4, 7.4, 49, "straw"))
    # caisse du bookmaker avec la cagnotte, billets poses sur la palissade
    B.append(tb(72, 0.5, 0.3, 79.6, 6, 6.3, "crate"))
    B.append(tb(72.3, 6, 0.6, 79.3, 6.2, 6, "crate_dk"))
    B.append(tb(74, 6.2, 1.5, 77.6, 7.6, 4.8, "iron"))
    B.append(tb(74.2, 7.6, 1.7, 77.4, 7.8, 4.6, "money"))
    for (x, z) in ((31, 3.3), (46, 3.1), (38, 60.3), (6.5, 30)):
        B.append(tb(x, 9.3, z, x + 2.6, 9.45, z + 1.3, "money", skip=("down",)))
    # deux poteaux de lanternes au fond, lanternes allumees, guirlande d'ampoules tendue au-dessus du ring
    for (x, lx) in ((0.8, 2.2), (77.8, 72.6)):
        B.append(tb(x, 0.5, 60.6, x + 1.4, 22, 62, "plank_dk"))
    B.append(tb(2.2, 20.4, 60.8, 8, 21.2, 61.8, "plank_dk"))
    B.append(tb(72, 20.4, 60.8, 77.8, 21.2, 61.8, "plank_dk"))
    for lx in (5.6, 72.4):
        B.append(tb(lx, 16.6, 60.4, lx + 2.6, 20.4, 62.2, "iron", skip=("front", "back", "left", "right")))
        B.append(tb(lx + 0.3, 17, 60.7, lx + 2.3, 20, 61.9, "lamp", glow=True))
    B.append(tb(8, 20.6, 61.1, 72, 20.8, 61.3, "rope"))
    for k in range(12):
        x = 11 + k * 5.2
        B.append(tb(x, 19.8, 60.9, x + 0.8, 20.6, 61.5, "lamp", glow=True))
    return B


def arena_closed_boxes():
    B = []
    B.append(tb(0, 0, 0, ARENA_W, 0.5, ARENA_D, "sand_dk", skip=("down",)))
    # gros tas de caisses sous une bache, cordes, caisses et bidons autour : rien qui ressemble a une arene
    B.append(tb(12, 0.5, 12, 66, 10, 52, "tarp", skip=("down",)))
    B.append(tb(16, 10, 16, 50, 13, 44, "tarp_dk", skip=("down",)))
    B.append(tb(11.6, 0.5, 11.6, 66.4, 1.2, 52.4, "tarp_dk", skip=("down", "up")))
    for z in (20, 32, 44):
        B.append(tb(11.8, 0.5, z, 66.2, 10.2, z + 0.4, "rope", skip=("down",)))
    for (x, y, z, s) in ((1, 0.5, 2, 7), (1.5, 7.5, 2.5, 6), (70, 0.5, 54, 8), (71, 0.5, 3, 6), (2, 0.5, 52, 6), (70, 0.5, 30, 6)):
        B.append(tb(x, y, z, x + s, y + s, z + s, "crate"))
        B.append(tb(x + 0.4, y + s, z + 0.4, x + s - 0.4, y + s + 0.2, z + s - 0.4, "crate_dk", skip=("down",)))
    B.append(tb(72, 0.5, 14, 76, 8, 18, "iron"))        # vieux bidons
    B.append(tb(72.2, 8, 14.2, 75.8, 8.4, 17.8, "rust" if "rust" in CELLS else "crate_dk"))
    B.append(tb(66, 0.5, 18, 69.5, 7, 21.5, "iron"))
    B.append(tb(4, 0.5, 26, 10, 4, 36, "straw"))
    return B


def arena():
    t = {"atlas": NS + "casino_atlas", "particle": NS + "casino_atlas"}
    op, cl = arena_open_boxes(), arena_closed_boxes()
    for p, els in split(op, cols=5, rows=4).items():
        write_model("arene_coqs_p%d" % p, t, els)
    for p, els in split(cl, cols=5, rows=4).items():
        write_model("arene_coqs_fermee_p%d" % p, t, els)
    write_model("arene_coqs", t, whole(op, width=ARENA_W, depth=ARENA_D, k=0.5), folder=ITEMS, extra={"display": TABLE_DISPLAY})
    multipart_state("arene_coqs", None, ("open", {True: "arene_coqs", False: "arene_coqs_fermee"}), parts=20)
    loot("arene_coqs")


# ---------------------------------------------------------------- grande arene des animaux (ouverte / fermee)
BIG_W, BIG_D = 112, 96   # 7 x 6 blocs


def big_open_boxes():
    """Fosse en tole rouillee sur dalle de beton : on y fait combattre les animaux des joueurs (chiens, chats...)."""
    B = []
    cx, cz = 56, 48
    B.append(tb(0, 0, 0, BIG_W, 0.5, BIG_D, "concrete", skip=("down",)))
    # muret de beton, tole rouillee par-dessus, chapeau metallique, poteaux tout autour
    B += ring(cx, cz, 52, 44, 49, 41, 0.5, 3, "concrete", step=4.0, n=3.2)
    B += ring(cx, cz, 51.2, 43.2, 49.8, 41.8, 3, 9, "iron", step=4.0, n=3.2)
    B += ring(cx, cz, 51.2, 43.2, 49.8, 41.8, 9, 13, "rust", step=4.0, n=3.2)
    B += ring(cx, cz, 51.6, 43.6, 49.4, 41.4, 13, 13.8, "metal_dk", step=4.0, n=3.2)
    for (x, z) in ((5, 47.3), (105.6, 47.3), (14, 10), (36, 5.2), (56, 4.6), (75, 5.2), (97, 10), (14, 84.6), (36, 89.4), (56, 90),
                   (75, 89.4), (97, 84.6)):
        B.append(tb(x, 0.5, z, x + 1.4, 16, z + 1.4, "metal_dk"))
    # bande jaune et noire de danger sur le muret, devant
    for k in range(10):
        x = 22 + k * 7
        B.append(tb(x, 1.2, 3.5, x + 3.5, 2.6, 4.2, "hazard" if k % 2 == 0 else "black", skip=("down", "up", "back")))
    # sol de sable, taches, lignes de depart des deux combattants
    B += stadium(cx, cz, 49, 41, 0.5, 1.2, "sand", step=4.0, n=3.2)
    for (x, z, w, d) in ((40, 34, 5, 4), (66, 56, 4, 5), (52, 62, 3, 3), (72, 36, 4, 2.4), (30, 52, 3.5, 3.5), (58, 28, 2.4, 2.4)):
        B.append(tb(x, 1.2, z, x + w, 1.26, z + d, "sand_dk", skip=("down",)))
    B += ring(cx, cz, 16, 12, 15.2, 11.2, 1.2, 1.28, "sand_dk", step=1.6, n=2.0)
    B.append(tb(31, 1.2, 45, 33, 1.3, 51, "white", skip=("down",)))
    B.append(tb(79, 1.2, 45, 81, 1.3, 51, "white", skip=("down",)))
    # piles de pneus aux angles, palettes et caisses, bidons
    for (x, z, n) in ((1, 1, 3), (104, 1, 2), (1, 87, 2), (104, 87, 4)):
        for k in range(n):
            B.append(tb(x + 0.4 * (k % 2), 0.5 + k * 2, z + 0.4 * (k % 2), x + 7 - 0.4 * (k % 2), 2.5 + k * 2, z + 7 - 0.4 * (k % 2), "tire"))
            B.append(tb(x + 2.5, 2.5 + k * 2, z + 2.5, x + 4.5, 2.52 + k * 2, z + 4.5, "black", skip=("down",)))
    for x in (12, 26, 72, 86):
        B.append(tb(x, 0.5, 0.4, x + 10, 1.5, 3.6, "crate_dk"))      # palettes-gradins devant
        B.append(tb(x + 0.5, 1.5, 0.6, x + 9.5, 4.5, 3.4, "straw"))
    B.append(tb(0.4, 0.5, 30, 4.4, 8, 38, "crate"))
    B.append(tb(0.6, 8, 30.4, 4.2, 8.2, 37.6, "crate_dk"))
    B.append(tb(107.4, 0.5, 58, 111.4, 7, 62, "iron"))
    B.append(tb(107.6, 7, 58.2, 111.2, 7.4, 61.8, "rust"))
    B.append(tb(107.4, 0.5, 64, 111.4, 7, 68, "iron"))
    # table du bookmaker avec la cagnotte, billets poses sur le muret
    B.append(tb(100, 0.5, 14, 110, 7, 22, "crate"))
    B.append(tb(100.3, 7, 14.3, 109.7, 7.2, 21.7, "crate_dk"))
    B.append(tb(102.5, 7.2, 16, 107, 8.8, 20, "iron"))
    B.append(tb(102.7, 8.8, 16.2, 106.8, 9, 19.8, "money"))
    for (x, z) in ((44, 4.6), (64, 4.5), (52, 91), (5.4, 40)):
        B.append(tb(x, 13.8, z, x + 2.8, 13.95, z + 1.4, "money", skip=("down",)))
    # deux mats de projecteurs au fond, projecteurs allumes, cable tendu avec ampoules au-dessus de la fosse
    for x in (1.2, 109.4):
        B.append(tb(x, 0.5, 92, x + 1.6, 30, 93.6, "metal_dk"))
    B.append(tb(2.8, 28, 92.2, 10, 29, 93.4, "metal_dk"))
    B.append(tb(102, 28, 92.2, 109.4, 29, 93.4, "metal_dk"))
    for lx in (7, 100):
        B.append(tb(lx, 24.6, 91.4, lx + 5, 28, 94, "black", skip=("front",)))
        B.append(tb(lx + 0.4, 25, 91.2, lx + 4.6, 27.6, 91.4, "lamp", glow=True, skip=("back",)))
    B.append(tb(10, 28.2, 92.7, 102, 28.4, 92.9, "rope"))
    for k in range(16):
        x = 13 + k * 5.6
        B.append(tb(x, 27.2, 92.5, x + 0.9, 28.2, 93.1, "lamp", glow=True))
    return B


def big_closed_boxes():
    B = []
    B.append(tb(0, 0, 0, BIG_W, 0.5, BIG_D, "concrete", skip=("down",)))
    # chantier a l'abandon : enorme tas bache, pneus, caisses, bidons
    B.append(tb(16, 0.5, 16, 94, 11, 78, "tarp", skip=("down",)))
    B.append(tb(22, 11, 22, 72, 15, 66, "tarp_dk", skip=("down",)))
    B.append(tb(15.6, 0.5, 15.6, 94.4, 1.2, 78.4, "tarp_dk", skip=("down", "up")))
    for z in (28, 47, 66):
        B.append(tb(15.8, 0.5, z, 94.2, 11.2, z + 0.4, "rope", skip=("down",)))
    for (x, y, z, sz) in ((1, 0.5, 2, 8), (1.5, 8.5, 2.5, 7), (100, 0.5, 84, 9), (101, 0.5, 3, 7), (2, 0.5, 82, 7), (100, 0.5, 44, 7)):
        B.append(tb(x, y, z, x + sz, y + sz, z + sz, "crate"))
        B.append(tb(x + 0.4, y + sz, z + 0.4, x + sz - 0.4, y + sz + 0.2, z + sz - 0.4, "crate_dk", skip=("down",)))
    for k in range(3):
        B.append(tb(1 + 0.4 * (k % 2), 0.5 + k * 2, 40 + 0.4 * (k % 2), 8 - 0.4 * (k % 2), 2.5 + k * 2, 47 - 0.4 * (k % 2), "tire"))
    B.append(tb(100, 0.5, 22, 104, 8, 26, "iron"))
    B.append(tb(100.2, 8, 22.2, 103.8, 8.4, 25.8, "rust"))
    B.append(tb(105, 0.5, 23, 109, 8, 27, "iron"))
    return B


def big_arena():
    t = {"atlas": NS + "casino_atlas", "particle": NS + "casino_atlas"}
    op, cl = big_open_boxes(), big_closed_boxes()
    for p, els in split(op, cols=7, rows=6).items():
        write_model("arene_animaux_p%d" % p, t, els)
    for p, els in split(cl, cols=7, rows=6).items():
        write_model("arene_animaux_fermee_p%d" % p, t, els)
    write_model("arene_animaux", t, whole(op, width=BIG_W, depth=BIG_D, k=0.3), folder=ITEMS, extra={"display": TABLE_DISPLAY})
    multipart_state("arene_animaux", None, ("open", {True: "arene_animaux", False: "arene_animaux_fermee"}), parts=42)
    loot("arene_animaux")


# ---------------------------------------------------------------- jetons en relief
CHIP_EDGE = {1: "chip_w", 5: "chip_r", 25: "chip_g", 100: "chip_k", 500: "chip_p"}


def chips():
    for v, edge in CHIP_EDGE.items():
        t = {"atlas": NS + "casino_atlas", "face": "minenorthcasino:item/jeton_%d" % v, "particle": "minenorthcasino:item/jeton_%d" % v}
        els = []
        # prisme a 8 pans (deux boites en croix + deux a 45 degres), faces du dessus par une plaque a la texture ronde
        for (f, to) in (([1.6, 7, 4.6], [14.4, 8.6, 11.4]), ([4.6, 7, 1.6], [11.4, 8.6, 14.4])):
            els.append(cube(f, to, edge, faces=("north", "south", "east", "west")))
        rot = {"origin": [8, 8, 8], "axis": "y", "angle": 45}
        for (f, to) in (([1.6, 7, 4.6], [14.4, 8.6, 11.4]), ([4.6, 7, 1.6], [11.4, 8.6, 14.4])):
            els.append(cube(f, to, edge, faces=("north", "south", "east", "west"), rotation=rot))
        els.append(cube([1, 8.62, 1], [15, 8.62, 15], None, faces=("up",), tex={"up": face("#face", [0, 0, 16, 16])}))
        els.append(cube([1, 6.98, 1], [15, 6.98, 15], None, faces=("down",), tex={"down": face("#face", [0, 16, 16, 0])}))
        display = {"display": {
            "gui": {"rotation": [55, 20, 0], "translation": [0, 0, 0], "scale": [1.05, 1.05, 1.05]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
            "fixed": {"rotation": [-90, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
            "thirdperson_righthand": {"rotation": [70, 0, 0], "translation": [0, 2, 1.5], "scale": [0.45, 0.45, 0.45]},
            "firstperson_righthand": {"rotation": [30, -20, 10], "translation": [1, 3, 0], "scale": [0.55, 0.55, 0.55]},
            "head": {"rotation": [0, 0, 0], "translation": [0, 6, 0], "scale": [1, 1, 1]}}}
        write_model("jeton_%d" % v, t, els, folder=ITEMS, extra=display)


# ---------------------------------------------------------------- apercus
def tex_map():
    m = {}
    for folder, ns in ((TEX, NS), (TEX_ITEM, "minenorthcasino:item/")):
        for f in os.listdir(folder):
            if f.endswith(".png"):
                m[ns + f[:-4]] = os.path.join(folder, f)
    return m


def merged(prefix, rows=2, cols=3):
    """Modele unique d'une table posee (morceaux decales comme dans le monde, face au nord)."""
    els, textures = [], {}
    for p in range(cols * rows):
        m = json.load(open(os.path.join(MODELS, "%s_p%d.json" % (prefix, p)), encoding="utf-8"))
        i, j = p % cols, p // cols
        tag = "p%d_" % p
        for k, v in m["textures"].items():
            textures[tag + k] = v
        for e in m["elements"]:
            e = json.loads(json.dumps(e))
            for n, fc in e["faces"].items():   # UV implicites calculees AVANT le decalage du morceau
                if "uv" not in fc:
                    fc["uv"] = list(rendu_modele._default_uv(n, e["from"], e["to"]))
            e["from"][0] -= 16 * i
            e["to"][0] -= 16 * i
            e["from"][2] += 16 * j
            e["to"][2] += 16 * j
            if "rotation" in e:
                e["rotation"]["origin"][0] -= 16 * i
                e["rotation"]["origin"][2] += 16 * j
            for fc in e["faces"].values():
                if fc["texture"].startswith("#"):
                    fc["texture"] = "#" + tag + fc["texture"][1:]
            els.append(e)
    return {"textures": textures, "elements": els}


def previews():
    os.makedirs(DOCS, exist_ok=True)
    tm = tex_map()
    tmp = os.path.join(DOCS, "_tmp_model.json")
    shots = []

    def shot(model, name, yaw, pitch, center, scale, size=520):
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(model, f)
        out = os.path.join(DOCS, name)
        rendu_modele.render(tmp, tm, yaw, pitch, out, size=size, scale=scale, center=center, bg=(28, 18, 52, 255), shade=True)
        shots.append(out)

    for prefix, name in (("table_poker", "apercu-table-poker.png"), ("table_blackjack", "apercu-table-blackjack.png"),
                         ("table_roulette", "apercu-table-roulette.png")):
        shot(merged(prefix), name, 200, 32, (-8, 8, 16), 9.0)
    for prefix, name in (("arene_coqs", "apercu-arene-coqs.png"), ("arene_coqs_fermee", "apercu-arene-fermee.png")):
        shot(merged(prefix, rows=4, cols=5), name, 200, 32, (-24, 8, 32), 5.6)
    for prefix, name in (("arene_animaux", "apercu-arene-animaux.png"), ("arene_animaux_fermee", "apercu-arene-animaux-fermee.png")):
        shot(merged(prefix, rows=6, cols=7), name, 200, 32, (-40, 8, 48), 4.0)
    disp = json.load(open(os.path.join(MODELS, "caisse_casino.json"), encoding="utf-8"))
    shot(disp, "apercu-distributeur-jetons.png", 205, 18, (8, 8, 8), 26.0)
    chips_model = {"textures": {}, "elements": []}
    for k, v in enumerate(CHIP_EDGE):
        m = json.load(open(os.path.join(ITEMS, "jeton_%d.json" % v), encoding="utf-8"))
        tag = "c%d_" % k
        for a, b in m["textures"].items():
            chips_model["textures"][tag + a] = b
        for e in m["elements"]:
            e = json.loads(json.dumps(e))
            dx, dy = (k - 2) * 17, 0
            e["from"][0] += dx; e["to"][0] += dx
            e["from"][1] += dy; e["to"][1] += dy
            if "rotation" in e:
                e["rotation"]["origin"][0] += dx
            for fc in e["faces"].values():
                fc["texture"] = "#" + tag + fc["texture"][1:]
            chips_model["elements"].append(e)
    shot(chips_model, "apercu-jetons.png", 0, 40, (8, 8, 8), 6.5, size=560)
    os.remove(tmp)
    # planche recapitulative
    ims = [Image.open(p) for p in shots]
    cols = 3
    w, h = ims[0].size
    sheet = Image.new("RGBA", (w * cols, h * ((len(ims) + cols - 1) // cols)), (28, 18, 52, 255))
    for k, im in enumerate(ims):
        sheet.paste(im, ((k % cols) * w, (k // cols) * h))
    sheet = sheet.resize((sheet.width // 2, sheet.height // 2), Image.LANCZOS)
    sheet.save(os.path.join(DOCS, "apercu-casino-3d.png"))


if __name__ == "__main__":
    atlas()
    tex_dispenser()
    dispenser()
    poker()
    upgrade_table("blackjack")
    upgrade_table("roulette")
    arena()
    big_arena()
    chips()
    previews()
    print("ok")
