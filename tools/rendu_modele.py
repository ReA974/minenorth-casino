"""Petit rendu logiciel hors jeu des modeles JSON a elements de Minecraft (projection orthographique, z-buffer).

Respecte les regles du jeu qui comptent pour verifier un modele :
 - UV TOUJOURS dans le repere 0-16 (la taille reelle de l'image est ignoree : u/16 * largeur de l'image) ;
 - ordre des sommets et sens des faces comme FaceInfo de Minecraft ;
 - rotation d'element (origine, axe, angle) comme FaceBakery (rotation directe autour de l'axe) ;
 - faces vues de dos non dessinees (culling).
Usage : from rendu_modele import render ; render(model_json_path, textures_dir_map, yaw, pitch, out_png)
"""
import json
import math
import os

import numpy as np
from PIL import Image

# sommets de chaque face dans l'ordre de FaceInfo (indices dans (minX,minY,minZ,maxX,maxY,maxZ))
FACES = {
    "down":  [(0, 1, 5), (0, 1, 2), (3, 1, 2), (3, 1, 5)],
    "up":    [(0, 4, 2), (0, 4, 5), (3, 4, 5), (3, 4, 2)],
    "north": [(3, 4, 2), (3, 1, 2), (0, 1, 2), (0, 4, 2)],
    "south": [(0, 4, 5), (0, 1, 5), (3, 1, 5), (3, 4, 5)],
    "west":  [(0, 4, 2), (0, 1, 2), (0, 1, 5), (0, 4, 5)],
    "east":  [(3, 4, 5), (3, 1, 5), (3, 1, 2), (3, 4, 2)],
}
NORMALS = {"down": (0, -1, 0), "up": (0, 1, 0), "north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}


def _load_tex(ref, tex_map, cache):
    if ref not in cache:
        path = tex_map.get(ref)
        cache[ref] = np.array(Image.open(path).convert("RGBA")) if path and os.path.exists(path) else None
    return cache[ref]


def _rot(p, origin, axis, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    x, y, z = (p[0] - origin[0], p[1] - origin[1], p[2] - origin[2])
    if axis == "y":
        x, z = x * c + z * s, -x * s + z * c
    elif axis == "x":
        y, z = y * c - z * s, y * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return (x + origin[0], y + origin[1], z + origin[2])


def _default_uv(name, f, t):
    """UV implicites de Minecraft (BlockElement.uvsByDefault) : la position de la face dans le bloc."""
    return {"up": (f[0], f[2], t[0], t[2]), "down": (f[0], 16 - t[2], t[0], 16 - f[2]),
            "north": (16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]), "south": (f[0], 16 - t[1], t[0], 16 - f[1]),
            "west": (f[2], 16 - t[1], t[2], 16 - f[1]), "east": (16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1])}[name]


def _quads(model, tex_map, cache):
    """Liste de (4 sommets 3D, 4 uv, texture, normale)."""
    out = []
    textures = model.get("textures", {})
    for el in model["elements"]:
        if not el.get("faces"):
            continue
        f, t = el["from"], el["to"]
        box = (f[0], f[1], f[2], t[0], t[1], t[2])
        for name, face in el["faces"].items():
            verts = [(box[i], box[j], box[k]) for (i, j, k) in FACES[name]]
            n = NORMALS[name]
            if "rotation" in el:
                r = el["rotation"]
                verts = [_rot(v, r["origin"], r["axis"], r["angle"]) for v in verts]
                n = _rot(n, (0, 0, 0), r["axis"], r["angle"])
            u1, v1, u2, v2 = face["uv"] if "uv" in face else _default_uv(name, f, t)
            corners = [(u1, v1), (u1, v2), (u2, v2), (u2, v1)]
            sh = (face.get("rotation", 0) // 90) % 4
            uvs = [corners[(i + sh) % 4] for i in range(4)]
            ref = face["texture"]
            while ref.startswith("#"):
                ref = textures.get(ref[1:], "")
            out.append((verts, uvs, _load_tex(ref, tex_map, cache), n, ref, SHADE[name] if "forge_data" not in face else 1.0))
    return out


SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "west": 0.6, "east": 0.6}


def render(model_path, tex_map, yaw, pitch, out_png, size=420, scale=None, bg=(70, 78, 92, 255), center=(8, 8, 8), shade=False):
    """tex_map : {'minenorth_casier:block/xxx': chemin_png}."""
    model = json.load(open(model_path, encoding="utf-8"))
    cache = {}
    quads = _quads(model, tex_map, cache)
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))

    def view(p):
        x, y, z = p[0] - center[0], p[1] - center[1], p[2] - center[2]
        x, z = x * cy + z * sy, -x * sy + z * cy       # yaw autour de Y
        y, z = y * cp - z * sp, y * sp + z * cp        # pitch autour de X
        return x, y, z

    vn = lambda n: view((n[0] + center[0], n[1] + center[1], n[2] + center[2]))
    scale = scale or size / 36.0
    img = np.zeros((size, size, 4), dtype=np.uint8)
    img[:, :] = bg
    zbuf = np.full((size, size), -1e9)
    for verts, uvs, tex, n, ref, sh in quads:
        vv = [view(v) for v in verts]
        nn = vn(n)
        nc = view(center)
        # la camera regarde vers -z (z vers la camera) : face visible si sa normale a z > 0
        if (nn[2] - nc[2]) <= 1e-6:
            continue
        pts = [(size / 2 + v[0] * scale, size / 2 - v[1] * scale, v[2]) for v in vv]
        for tri in ((0, 1, 2), (0, 2, 3)):
            _fill(img, zbuf, [pts[i] for i in tri], [uvs[i] for i in tri], tex, sh if shade else 1.0)
    Image.fromarray(img).save(out_png)


def _fill(img, zbuf, p, uv, tex, sh=1.0):
    (x0, y0, z0), (x1, y1, z1), (x2, y2, z2) = p
    minx, maxx = int(max(0, math.floor(min(x0, x1, x2)))), int(min(img.shape[1] - 1, math.ceil(max(x0, x1, x2))))
    miny, maxy = int(max(0, math.floor(min(y0, y1, y2)))), int(min(img.shape[0] - 1, math.ceil(max(y0, y1, y2))))
    den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
    if abs(den) < 1e-9 or maxx < minx or maxy < miny:
        return
    xs, ys = np.meshgrid(np.arange(minx, maxx + 1) + 0.5, np.arange(miny, maxy + 1) + 0.5)
    a = ((y1 - y2) * (xs - x2) + (x2 - x1) * (ys - y2)) / den
    b = ((y2 - y0) * (xs - x2) + (x0 - x2) * (ys - y2)) / den
    c = 1 - a - b
    inside = (a >= -1e-4) & (b >= -1e-4) & (c >= -1e-4)
    if not inside.any():
        return
    z = a * z0 + b * z1 + c * z2
    u = a * uv[0][0] + b * uv[1][0] + c * uv[2][0]
    v = a * uv[0][1] + b * uv[1][1] + c * uv[2][1]
    if tex is None:
        col = np.zeros(xs.shape + (4,), dtype=np.uint8)
        col[:] = (255, 0, 255, 255)
    else:
        th, tw = tex.shape[0], tex.shape[1]
        ti = np.clip((u / 16.0 * tw).astype(int), 0, tw - 1)
        tj = np.clip((v / 16.0 * th).astype(int), 0, th - 1)
        col = tex[tj, ti]
    if sh != 1.0:
        col = col.copy()
        col[..., :3] = (col[..., :3] * sh).astype(np.uint8)
    sub = zbuf[miny:maxy + 1, minx:maxx + 1]
    ok = inside & (z > sub) & (col[..., 3] > 8)
    sub[ok] = z[ok]
    region = img[miny:maxy + 1, minx:maxx + 1]
    region[ok] = col[ok]
