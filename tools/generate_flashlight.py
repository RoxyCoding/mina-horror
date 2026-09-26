"""Builds the flashlight item: a 140 mm tactical LED torch modelled at real
dimensions (18650 body, finned head, stainless crenellated bezel, deep
orange-peel reflector, 2x2-die LED, rubber tail switch, pocket clip).

Writes to src/main/resources/assets/mina-horror/:
  models/item/flashlight.bin      gzip mesh read by FlashlightMesh (big endian)
  textures/model/flashlight.png   1024 px material sheet the mesh samples
  textures/item/flashlight.png    16 px icon (particles)

Mesh layout: int magic 'FLSH', then three groups (solid, glow, glass), each an
int quad count followed by 4 vertices per quad of
  x y z  nx ny nz  u v  u_lit v_lit    (floats; model space in blocks, uv 0..1)
The glow group (reflector, LED) and glass group (lens, LED dome) switch to the
*_lit uv and full brightness while the light is on.

Usage: python tools/generate_flashlight.py   (needs numpy and Pillow)
"""
import gzip
import math
import os
import struct

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = os.path.join(os.path.dirname(__file__), '..', 'src/main/resources/assets/mina-horror')
TEX = 1024
SEG = 72                      # facets around the axis
SCALE = 0.875 / 140.0         # blocks per millimetre: the 140 mm torch is 14 px long
CLIP_ANGLE = math.pi / 2      # pocket clip on +y
RNG = np.random.default_rng(413)

# Material sheet cells (x, y, w, h) in px. Cells wrapped around the axis tile vertically every h px.
CELL = {
    'knurl': (0, 0, 1024, 256), 'anod': (0, 256, 1024, 256), 'engrave': (0, 512, 1024, 256),
    'steel': (0, 768, 512, 64), 'worn': (512, 768, 512, 64),
    'reflector': (0, 832, 512, 96), 'reflector_lit': (512, 832, 512, 96),
    'rubber': (0, 928, 192, 96), 'clip': (192, 928, 192, 96), 'dark': (384, 928, 64, 96),
    'glass': (448, 928, 96, 96), 'glass_lit': (544, 928, 96, 96),
    'led': (640, 928, 96, 96), 'led_lit': (736, 928, 96, 96), 'white': (832, 928, 64, 96),
}
LIT = {'reflector': 'reflector_lit', 'glass': 'glass_lit', 'led': 'led_lit'}
KNURL_PX_PER_MM = 256 / 18.9  # 14 rows of 1.35 mm diamonds per tile, 56 around the body
PX_PER_MM = {'knurl': KNURL_PX_PER_MM, 'anod': KNURL_PX_PER_MM, 'engrave': 256 / 23.0,
             'steel': 12.0, 'worn': 12.0, 'reflector': 4.2, 'rubber': 12.0, 'clip': 12.0,
             'dark': 12.0, 'white': 12.0}
PLANAR_RADIUS = {'glass': 14.6, 'led': 3.4}  # disks mapped flat onto their cell
ENGRAVE_BAND = (58.5, 81.5)


# ---------------------------------------------------------------- geometry

def crenellation(count, depth, width, phase):
    """z offset of a scalloped rim: flat teeth, rounded notches `width` (fraction of a period) wide."""
    def dz(theta):
        t = ((theta - phase) * count / (2 * math.pi)) % 1.0
        d = abs(t - 0.5) / (width / 2)
        return depth * math.cos(d * math.pi / 2) ** 1.5 if d < 1 else 0.0
    return dz


TAIL = crenellation(3, 3.1, 0.42, CLIP_ANGLE + math.pi / 3)
BEZEL = crenellation(5, -1.5, 0.36, 0.0)


class Mesh:
    def __init__(self):
        self.groups = {'solid': [], 'glow': [], 'glass': []}

    def uv(self, material, theta, v_mm, x, y):
        cell = CELL[material]
        if material in PLANAR_RADIUS:
            k = cell[2] / 2 / PLANAR_RADIUS[material]
            u, v = cell[0] + cell[2] / 2 + x * k, cell[1] + cell[3] / 2 - y * k
        else:
            # u runs against theta so the sheet is not mirrored when seen from outside
            u = cell[0] + cell[2] * (1 - theta / (2 * math.pi))
            v = cell[1] + v_mm * PX_PER_MM[material]
        return u, v

    def quad(self, group, material, verts):
        """verts: 4 x (pos, normal, theta, v_mm)."""
        out = []
        for pos, nrm, theta, v_mm in verts:
            u, v = self.uv(material, theta, v_mm, pos[0], pos[1])
            lit = LIT.get(material)
            if lit:
                du = CELL[lit][0] - CELL[material][0]
                dv = CELL[lit][1] - CELL[material][1]
                ul, vl = u + du, v + dv
            else:
                ul, vl = u, v
            out.append((pos, nrm, u / TEX, v / TEX, ul / TEX, vl / TEX))
        self.groups[group].append(out)


def lathe(mesh, group, profile, seg=SEG):
    """Surface of revolution. profile: [(z, r, material, smooth, dz_fn)], traversed with the outside on the left
    (tail axis -> rim -> head axis for the body). Each span takes the material of its first point; smooth
    points share one normal between the spans meeting there, other points give a hard machined edge.
    Texture v follows the arc length through a run of one material and wraps at the cell height."""
    thetas = [2 * math.pi * j / seg for j in range(seg + 1)]

    def point(p, theta):
        z = p[0] + (p[4](theta) if p[4] else 0.0)
        return np.array([p[1] * math.cos(theta), p[1] * math.sin(theta), z])

    def direction(a, b, theta):
        d = point(b, theta) - point(a, theta)
        return d / (np.linalg.norm(d) + 1e-12)

    def normal(span, t, theta):
        a, b = profile[span], profile[span + 1]
        along = direction(a, b, theta)
        if t == 0 and span > 0 and a[3]:
            along = along + direction(profile[span - 1], a, theta)
        if t == 1 and span + 2 < len(profile) and b[3]:
            along = along + direction(b, profile[span + 2], theta)
        p = point(a, theta) * (1 - t) + point(b, theta) * t
        around = point(a, theta + 1e-3) * (1 - t) + point(b, theta + 1e-3) * t - p
        n = np.cross(around, along)
        if np.linalg.norm(n) < 1e-9:  # on the axis: face along it
            inward = along[0] * math.cos(theta) + along[1] * math.sin(theta) < 0
            n = np.array([0.0, 0.0, 1.0 if inward else -1.0])
        return n / np.linalg.norm(n)

    run = 0.0  # arc length since the material last changed
    for span in range(len(profile) - 1):
        a, b = profile[span], profile[span + 1]
        material = a[2]
        if span > 0 and profile[span - 1][2] != material:
            run = 0.0
        length = math.hypot(b[0] - a[0], b[1] - a[1])
        if length < 1e-6:
            continue
        cuts = [0.0, 1.0]
        tile = 0.0
        if material not in PLANAR_RADIUS:
            tile = CELL[material][3] / PX_PER_MM[material]
            k = math.floor(run / tile + 1e-6) + 1
            while k * tile < run + length - 1e-4:
                cuts.insert(-1, (k * tile - run) / length)
                k += 1
        for t0, t1 in zip(cuts, cuts[1:]):
            v0 = (run + t0 * length) % tile if tile else 0.0
            if tile and v0 > tile - 1e-4:
                v0 = 0.0
            v1 = v0 + (t1 - t0) * length
            for j in range(seg):
                ring = []
                for t, v in ((t0, v0), (t1, v1)):
                    for th in (thetas[j], thetas[j + 1]):
                        pos = point(a, th) * (1 - t) + point(b, th) * t
                        ring.append((pos, normal(span, t, th), th, v))
                mesh.quad(group, material, [ring[0], ring[1], ring[3], ring[2]])
        run += length


def P(z, r, material, smooth=False, dz=None):
    return (z, r, material, smooth, dz)


def body_profile():
    p = []
    # rubber tail switch boot, domed
    for k in range(7):
        a = k / 6 * (math.pi / 2) * 0.93
        p.append(P(3.3 - 1.7 * math.cos(a), 7.0 * math.sin(a) / math.sin(math.pi / 2 * 0.93), 'rubber', True))
    p[-1] = P(3.3, 7.0, 'rubber')
    p += [P(4.6, 7.2, 'dark'), P(4.6, 9.2, 'anod'),
          # crenellated tail rim, anodising worn off the tooth tops
          P(0.7, 9.2, 'worn', dz=TAIL), P(0.0, 9.9, 'worn', dz=TAIL), P(0.0, 12.3, 'worn', dz=TAIL),
          P(0.7, 13.0, 'anod', dz=TAIL),
          P(7.0, 13.0, 'anod'), P(7.2, 12.4, 'anod'), P(7.8, 12.4, 'worn'), P(8.0, 13.0, 'knurl'),
          P(14.0, 13.0, 'worn'), P(14.5, 12.5, 'anod'), P(15.4, 12.5, 'dark'),
          # thread gap with the o-ring, then the clip band
          P(15.5, 11.9, 'rubber'), P(16.2, 11.9, 'clip'), P(16.3, 12.9, 'clip'), P(21.8, 12.9, 'clip'),
          P(22.0, 12.3, 'dark'), P(22.1, 12.0, 'anod'),
          # battery tube: grooves, knurled grip, engraved band
          P(23.0, 12.0, 'anod'), P(23.2, 11.5, 'anod'), P(23.8, 11.5, 'anod'), P(24.0, 12.0, 'anod'),
          P(26.3, 12.0, 'worn'), P(26.5, 12.0, 'knurl'), P(57.3, 12.0, 'worn'), P(57.5, 11.5, 'anod'),
          P(58.3, 11.5, 'anod'), P(ENGRAVE_BAND[0], 12.0, 'engrave'), P(ENGRAVE_BAND[1], 12.0, 'anod'),
          P(81.7, 11.5, 'anod'), P(82.3, 11.5, 'anod'), P(82.5, 12.0, 'anod'),
          # flare into the head
          P(89.0, 12.0, 'anod', True)]
    for z, r in ((91.0, 12.12), (93.0, 12.55), (95.0, 13.35), (97.0, 14.55), (98.5, 15.85)):
        p.append(P(z, r, 'anod', True))
    p += [P(99.2, 16.6, 'worn'), P(99.6, 17.0, 'anod')]
    # heat-sink fins
    for g in range(6):
        z = 101.3 + 2.5 * g
        p += [P(z - 0.12, 17.0, 'worn'), P(z, 16.88, 'anod'), P(z, 15.6, 'dark'), P(z + 1.2, 15.6, 'anod'),
              P(z + 1.2, 16.88, 'worn'), P(z + 1.32, 17.0, 'anod')]
    p += [P(131.0, 17.0, 'dark'), P(131.1, 16.7, 'dark'), P(131.6, 16.7, 'steel'),
          # stainless strike bezel
          P(131.7, 17.2, 'steel'), P(139.2, 17.2, 'steel', dz=BEZEL), P(140.0, 16.6, 'steel', dz=BEZEL),
          P(140.0, 15.0, 'steel', dz=BEZEL), P(139.6, 14.6, 'steel', dz=BEZEL), P(137.6, 14.6, 'dark'),
          P(137.6, 14.2, 'dark')]
    return p


def reflector_profile():
    # parabola z = 117.9 + 0.0949 r^2: focal length 2.6 mm, LED dies near the focus
    p = []
    for k in range(25):
        r = 14.2 - (14.2 - 3.4) * k / 24
        p.append(P(117.9 + 0.0949 * r * r, r, 'reflector', True))
    p[-1] = P(p[-1][0], 3.4, 'led')
    p.append(P(p[-1][0], 0.0, 'led'))
    return p


def box(mesh, group, lo, hi, top_material, side_material):
    x0, y0, z0 = lo
    x1, y1, z1 = hi
    faces = [
        ((0, 0, 1), [(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)], top_material),
        ((1, 0, 0), [(x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1)], side_material),
        ((-1, 0, 0), [(x0, y1, z0), (x0, y0, z0), (x0, y0, z1), (x0, y1, z1)], side_material),
        ((0, 1, 0), [(x1, y1, z0), (x0, y1, z0), (x0, y1, z1), (x1, y1, z1)], side_material),
        ((0, -1, 0), [(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)], side_material),
    ]
    for n, pts, material in faces:
        mesh.quad(group, material, [(np.array(q), np.array(n, float), 0.0, (q[2] - z0) * 4) for q in pts])


def pocket_clip(mesh):
    """Spring-steel clip, curved to the body, from the clip band towards the head with a flared tip."""
    path = [(16.4, 13.45), (21.4, 13.45), (22.6, 13.62), (24.0, 13.95), (26.0, 14.08), (40.0, 13.75),
            (55.0, 13.42), (64.0, 13.2), (67.6, 13.15), (69.4, 13.35), (70.6, 13.85), (71.4, 14.55)]
    half_w, half_t, ch = 3.5, 0.6, 0.28
    # cross-section (b across the width, n outward), counter-clockwise seen from the tip
    section = [(-half_w + ch, -half_t), (half_w - ch, -half_t), (half_w, -half_t + ch), (half_w, half_t - ch),
               (half_w - ch, half_t), (-half_w + ch, half_t), (-half_w, half_t - ch), (-half_w, -half_t + ch)]
    frames = []
    for i, (z, r) in enumerate(path):
        a = path[max(i - 1, 0)]
        b = path[min(i + 1, len(path) - 1)]
        dz, dr = b[0] - a[0], b[1] - a[1]
        length = math.hypot(dz, dr)
        frames.append((z, r, -dr / length, dz / length))  # (z, r, normal z, normal r)
    along = [0.0]
    for a, b in zip(path, path[1:]):
        along.append(along[-1] + math.hypot(b[0] - a[0], b[1] - a[1]))

    def place(frame, b, n):
        z, r, nz, nr = frame
        theta = CLIP_ANGLE + b / r
        radial = r + n * nr
        return np.array([radial * math.cos(theta), radial * math.sin(theta), z + n * nz]), theta

    def normal(frame, theta, sb, sn):
        _, _, nz, nr = frame
        e_r = np.array([math.cos(theta), math.sin(theta), 0.0])
        e_t = np.array([-math.sin(theta), math.cos(theta), 0.0])
        v = sb * e_t + sn * (nz * np.array([0, 0, 1.0]) + nr * e_r)
        return v / np.linalg.norm(v)

    edges = []
    for k in range(len(section)):
        s0, s1 = section[k], section[(k + 1) % len(section)]
        eb, en = s1[0] - s0[0], s1[1] - s0[1]
        sb, sn = en, -eb  # outward for a counter-clockwise section
        steps = 8 if abs(eb) > 1 else 1  # wide faces follow the body curve
        for m in range(steps):
            edges.append((s0[0] + eb * m / steps, s0[1] + en * m / steps,
                          s0[0] + eb * (m + 1) / steps, s0[1] + en * (m + 1) / steps, sb, sn, k))
    for i in range(len(path) - 1):
        for b0, n0, b1, n1, sb, sn, k in edges:
            verts = []
            for f, (b, n) in ((i, (b0, n0)), (i, (b1, n1)), (i + 1, (b1, n1)), (i + 1, (b0, n0))):
                pos, th = place(frames[f], b, n)
                verts.append((pos, normal(frames[f], th, sb, sn), CLIP_ANGLE + (b + half_w) / 13.4 * 0.5, along[f]))
            mesh.quad('solid', 'clip', verts)
    # end caps
    for f, sign in ((0, -1), (len(path) - 1, 1)):
        z, r, nz, nr = frames[f]
        cap_n = np.array([0, 0, 1.0]) * nr - np.array([math.cos(CLIP_ANGLE), math.sin(CLIP_ANGLE), 0]) * nz  # path tangent
        cap_n = cap_n * sign / np.linalg.norm(cap_n)
        centre, _ = place(frames[f], 0.0, 0.0)
        for k in range(len(section)):
            p0, _ = place(frames[f], *section[k])
            p1, _ = place(frames[f], *section[(k + 1) % len(section)])
            mesh.quad('solid', 'worn', [(centre, cap_n, 0.3, 0), (p0, cap_n, 0.3, 0.5), (p1, cap_n, 0.35, 0.5),
                                        (p1, cap_n, 0.35, 0.5)])


def build():
    mesh = Mesh()
    lathe(mesh, 'solid', body_profile())
    lathe(mesh, 'glow', reflector_profile())
    # LED: white ceramic package with the phosphor on top, silicone dome over the dies
    box(mesh, 'glow', (-1.75, -1.75, 118.22), (1.75, 1.75, 118.85), 'led', 'white')
    dome = []
    for k in range(9):
        a = k / 8 * math.pi / 2
        dome.append(P(118.85 + 1.45 * math.sin(a), 1.45 * math.cos(a), 'glass', True))
    lathe(mesh, 'glass', dome, seg=24)
    lathe(mesh, 'glass', [P(137.45, 14.6, 'glass'), P(137.45, 0.0, 'glass')])
    pocket_clip(mesh)
    return mesh


def to_model(pos, nrm):
    """Millimetres, tail at z = 0 -> item model space in blocks, centred, head towards -z."""
    x, y, z = pos
    return (0.5 - x * SCALE, 0.5 + y * SCALE, 0.5 - (z - 70.0) * SCALE), (-nrm[0], nrm[1], -nrm[2])


def write_mesh(mesh, path):
    data = bytearray(struct.pack('>i', 0x464C5348))
    for name in ('solid', 'glow', 'glass'):
        quads = mesh.groups[name]
        data += struct.pack('>i', len(quads))
        for quad in quads:
            for pos, nrm, u, v, ul, vl in quad:
                p, n = to_model(pos, nrm)
                data += struct.pack('>10f', *p, *n, u, v, ul, vl)
    with gzip.open(path, 'wb', compresslevel=9) as f:
        f.write(bytes(data))


# ---------------------------------------------------------------- textures

def periodic_noise(h, w, scale, rng=RNG):
    """Tileable smooth noise in about [-1, 1]; scale is the feature size in px."""
    white = rng.standard_normal((h, w))
    fy = np.fft.fftfreq(h)[:, None]
    fx = np.fft.fftfreq(w)[None, :]
    spectrum = np.fft.fft2(white) * np.exp(-(fx * fx + fy * fy) * (scale * math.pi) ** 2)
    n = np.real(np.fft.ifft2(spectrum))
    return n / (np.abs(n).max() + 1e-9)


def _row(h, scale, rng):
    white = rng.standard_normal(h)
    f = np.fft.fftfreq(h)
    n = np.real(np.fft.ifft(np.fft.fft(white) * np.exp(-(f * scale * math.pi) ** 2)))
    return (n / (np.abs(n).max() + 1e-9))[:, None, None]


def rgb(h, w, colour):
    return np.ones((h, w, 3)) * np.array(colour, float)


def anodised(h, w, base=(44, 45, 49)):
    img = rgb(h, w, base)
    img += periodic_noise(h, w, 3)[..., None] * 3.0
    img += periodic_noise(h, w, 40)[..., None] * 4.0
    img += _row(h, 1.2, RNG) * 3.5            # lathe turning marks run around the tube
    return img


def scratches(img, count, colour, rng=RNG):
    h, w, _ = img.shape
    layer = Image.new('L', (w, h), 0)
    draw = ImageDraw.Draw(layer)
    for _ in range(count):
        x, y = rng.uniform(0, w), rng.uniform(0, h)
        a = rng.uniform(0, math.pi)
        length = rng.uniform(6, 40)
        for ox in (-w, 0, w):
            for oy in (-h, 0, h):
                draw.line([(x + ox, y + oy), (x + ox + math.cos(a) * length, y + oy + math.sin(a) * length)],
                          fill=int(rng.uniform(60, 150)), width=1)
    alpha = np.asarray(layer.filter(ImageFilter.GaussianBlur(0.4)), float)[..., None] / 255 * 0.55
    return img * (1 - alpha) + np.array(colour, float) * alpha


def knurl(h, w):
    pu, pv = w / 56, h / 14
    y, x = np.mgrid[0:h, 0:w].astype(float)
    a = (x / pu + y / pv) % 1.0
    b = (x / pu - y / pv) % 1.0
    ga = 1 - np.abs(2 * a - 1)
    gb = 1 - np.abs(2 * b - 1)
    height = np.clip(np.minimum(ga, gb) * 1.35, 0, 1)
    # shade the pyramids with a light from the upper left, darken the grooves
    gy, gx = np.gradient(np.pad(height, 1, mode='wrap'))
    gx, gy = gx[1:-1, 1:-1], gy[1:-1, 1:-1]
    n = np.dstack([-gx * 6, -gy * 6, np.ones_like(gx)])
    n /= np.linalg.norm(n, axis=2, keepdims=True)
    light = np.array([-0.45, -0.55, 0.7])
    light /= np.linalg.norm(light)
    lambert = np.clip((n * light).sum(axis=2), 0, 1)
    shade = 0.45 + 0.75 * lambert
    occlusion = 0.35 + 0.65 * np.clip(height * 1.6, 0, 1)
    img = anodised(h, w, (50, 51, 55)) * (shade * occlusion)[..., None]
    wear = np.clip(periodic_noise(h, w, 18) * 1.4 + 0.1, 0, 1) * np.clip(height * 3 - 2.2, 0, 1)
    return img * (1 - wear[..., None] * 0.7) + np.array([120, 121, 124.0]) * wear[..., None] * 0.7


def worn(h, w):
    img = rgb(h, w, (142, 143, 147)) + periodic_noise(h, w, 2)[..., None] * 10 + _row(h, 0.8, RNG) * 10
    patches = np.clip(periodic_noise(h, w, 3) * 1.6 - 0.35, 0, 1)[..., None] * 0.8
    return img * (1 - patches) + anodised(h, w) * patches


def steel(h, w):
    return rgb(h, w, (166, 169, 174)) + _row(h, 0.6, RNG) * 14 + periodic_noise(h, w, 2)[..., None] * 5


def reflector(h, w, lit):
    bumps = periodic_noise(h, w, 2.2)
    gy, gx = np.gradient(bumps)
    orange_peel = 1 + (gx * -0.6 + gy * -0.8) * 2.2 + periodic_noise(h, w, 12) * 0.04
    v = np.linspace(0, 1, h)[:, None, None]   # 0 at the lens rim, 1 at the LED
    if lit:
        base = np.array([255, 250, 236.0]) * (0.93 + 0.07 * v)
        return base * (1 + (orange_peel[..., None] - 1) * 0.25)
    # a mirror of the dark room: bright near the rim, a dim band, the lit gasket glow near the LED
    tone = 150 + 70 * np.exp(-((v - 0.05) / 0.12) ** 2) - 40 * np.exp(-((v - 0.55) / 0.25) ** 2) + 50 * v ** 4
    return np.array([1.0, 1.01, 1.03]) * tone * orange_peel[..., None]


def glass(n, lit):
    y, x = (np.mgrid[0:n, 0:n] - (n - 1) / 2) / (n / 2)
    r = np.hypot(x, y)
    img = np.zeros((n, n, 4))
    if lit:
        img[..., :3] = np.array([255, 249, 232.0])
        img[..., 3] = 150 + 90 * np.exp(-(r / 0.35) ** 2)
    else:
        img[..., :3] = np.array([196, 212, 222.0])
        img[..., 3] = 34
        # anti-reflective coating tint at the rim and a soft window reflection
        rim = np.clip((r - 0.82) / 0.18, 0, 1)
        img[..., :3] = img[..., :3] * (1 - rim[..., None] * 0.4) + np.array([150, 120, 200.0]) * rim[..., None] * 0.4
        img[..., 3] += rim * 40
        streak = np.exp(-((x * 0.8 + y * 0.6 + 0.25) / 0.09) ** 2) * (r < 0.95)
        img[..., 3] += streak * 70
        img[..., :3] += streak[..., None] * 40
    return img


def led(n, lit):
    k = n / 2 / PLANAR_RADIUS['led']
    y, x = (np.mgrid[0:n, 0:n] - (n - 1) / 2) / k   # mm, y down
    img = rgb(n, n, (226, 226, 221))
    img *= (1 - 0.35 * np.clip((np.hypot(x, y) - 3.0) / 0.4, 0, 1))[..., None]   # gasket edge in shadow
    package = (np.abs(x) < 1.75) & (np.abs(y) < 1.75)
    img[package] = (240, 240, 237)
    img[(np.abs(x) < 1.75) & (np.abs(y) < 1.75) & ((np.abs(x) > 1.65) | (np.abs(y) > 1.65))] = (190, 190, 186)
    cell = (x + 1.3) % 1.3
    die = (np.abs(x) < 1.3) & (np.abs(y) < 1.3) & (cell > 0.08) & (((y + 1.3) % 1.3) > 0.08)
    img[(np.abs(x) < 1.32) & (np.abs(y) < 1.32)] = (70, 62, 40)
    img[die] = (224, 197, 88)
    if lit:
        r = np.hypot(x, y)
        img = img * 0.25 + np.array([255, 250, 238.0]) * 0.75
        img[die] = (255, 255, 252)
        img += (np.exp(-(r / 1.6) ** 2) * 60)[..., None]
    return img


def engraving(h, w):
    img = anodised(h, w)
    text = Image.new('L', (h, w), 0)            # drawn along the tube, rotated into place below
    draw = ImageDraw.Draw(text)

    def font(size, bold=True):
        for name in (('arialbd.ttf' if bold else 'arial.ttf'), 'DejaVuSans-Bold.ttf'):
            try:
                return ImageFont.truetype(name, size)
            except OSError:
                pass
        return ImageFont.load_default(size)

    # px per mm across the text is the circumference scale (width/75.4mm); along it the band scale
    across = w / (2 * math.pi * 12.0)
    along = h / 23.0

    def line(txt, size_mm, centre_theta, bold=True):
        f = font(int(size_mm * across * 1.35), bold)
        box = draw.textbbox((0, 0), txt, font=f)
        tw, th = box[2] - box[0], box[3] - box[1]
        piece = Image.new('L', (tw + 4, th + 4), 0)
        ImageDraw.Draw(piece).text((2 - box[0], 2 - box[1]), txt, font=f, fill=255)
        piece = piece.resize((int(piece.width * along / across), piece.height), Image.LANCZOS)
        cx = h / 2
        cy = centre_theta / (2 * math.pi) % 1.0 * w  # becomes u = 1 - theta / 2 pi after the rotation
        text.paste(piece, (int(cx - piece.width / 2), int(cy - piece.height / 2)), piece)

    side = (CLIP_ANGLE + math.pi / 2) % (2 * math.pi)  # beside the clip, facing out when it is on top
    line('MINA  MH-18', 2.0, side)
    line('1200 lm   IPX-8   1x18650', 0.95, side + 0.25, bold=False)  # below: letter tops face -theta
    line('No. 0413-0666', 0.9, side + math.pi / 2, bold=False)
    # text reads tail to head: rotate into the sheet (letter tops towards +u, which faces the viewer's up)
    mask = np.asarray(text.rotate(-90, expand=True).filter(ImageFilter.GaussianBlur(0.35)), float)[..., None] / 255
    etched = rgb(h, w, (178, 178, 181)) + periodic_noise(h, w, 1.5)[..., None] * 12
    return img * (1 - mask) + etched * mask


def build_texture():
    sheet = np.zeros((TEX, TEX, 4))
    sheet[..., 3] = 255

    def put(name, img):
        x, y, w, h = CELL[name]
        if img.shape[2] == 3:
            sheet[y:y + h, x:x + w, :3] = img
        else:
            sheet[y:y + h, x:x + w] = img

    put('knurl', knurl(256, 1024))
    put('anod', scratches(anodised(256, 1024), 40, (110, 111, 115)))
    put('engrave', scratches(engraving(256, 1024), 20, (110, 111, 115)))
    put('steel', steel(64, 512))
    put('worn', worn(64, 512))
    put('reflector', reflector(96, 512, False))
    put('reflector_lit', reflector(96, 512, True))
    put('rubber', rgb(96, 192, (40, 40, 42)) + periodic_noise(96, 192, 1.2)[..., None] * 6)
    put('clip', anodised(96, 192, (31, 31, 34)) + periodic_noise(96, 192, 6)[..., None] * 4)
    put('dark', rgb(96, 64, (13, 13, 14)))
    put('glass', glass(96, False))
    put('glass_lit', glass(96, True))
    put('led', led(96, False))
    put('led_lit', led(96, True))
    put('white', rgb(96, 64, (232, 232, 228)))
    return Image.fromarray(np.clip(sheet, 0, 255).astype(np.uint8), 'RGBA')


def main():
    mesh = build()
    os.makedirs(os.path.join(OUT, 'textures/model'), exist_ok=True)
    write_mesh(mesh, os.path.join(OUT, 'models/item/flashlight.bin'))
    build_texture().save(os.path.join(OUT, 'textures/model/flashlight.png'), optimize=True)
    print({k: len(v) for k, v in mesh.groups.items()})


if __name__ == '__main__':
    main()
