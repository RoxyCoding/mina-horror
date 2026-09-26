"""Builds the flying broom (mina-horror:broom) at real dimensions after the witch's broom in the reference art:
a gently crooked pearl-white handle ending in a gilded ferrule set with a garnet, a red satin bow near the front
with a Moroccan lantern hanging from it, and at the back the bristles bound in a spiral grey leather wrap with a
black cord, a gold filigree cuff, a thin gold hoop and a fan of long white feathery bristle locks.
The art shows the broom from its right side only; the left side and the underside mirror it.

Writes to src/main/resources/assets/mina-horror/:
  models/entity/broom.bin     gzip mesh read by BroomMesh (big endian)
  textures/entity/broom.png   1024 px material sheet the mesh samples
  textures/item/broom.png     16 px icon (particles)
With --obj DIR it also writes broom.obj and broom.mtl to DIR, for a look in a 3D tool.

Mesh layout: int magic 'BROM', floats x y z of the lantern's hook, then four groups (solid, lantern, flame,
glass), each an int quad count followed by 4 vertices per quad of
  x y z  nx ny nz  u v    (floats; blocks, uv 0..1)
Model space: the seat on the handle axis at the origin, front towards +z, up +y, the broom's right side
towards -x. The lantern, flame and glass groups hang from the hook and swing about it.

Usage: python tools/generate_broom.py [--obj DIR]   (needs numpy and Pillow)
"""
import gzip
import math
import os
import struct
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(__file__), '..', 'src/main/resources/assets/mina-horror')
TEX = 1024
MM = 0.0015                 # blocks per millimetre: the 2.25 m broom is drawn 3.4 blocks long, as long beside
                            # its rider as in the art
MAGIC = 0x42524F4D
GROUPS = ('solid', 'lantern', 'flame', 'glass')

# Material sheet cells (x, y, w, h) in px. u runs across the width (once around a round part); v runs down.
CELL = {
    'wood': (0, 0, 1024, 256), 'bristle': (0, 256, 1024, 256),
    'gold': (0, 512, 256, 128), 'engraved': (256, 512, 512, 128), 'cord': (768, 512, 256, 128),
    'wrap': (0, 640, 512, 128), 'lattice': (512, 640, 512, 128),
    'glass': (0, 768, 128, 256), 'ribbon': (128, 768, 256, 256), 'candle': (384, 768, 64, 128),
    'flame': (448, 768, 64, 128), 'garnet': (512, 768, 64, 128), 'dark': (576, 768, 64, 128),
    'gilt': (640, 768, 128, 128),
}
# Materials tiled along v every so many mm; the others take v as a 0..1 fraction of their cell.
TILE_MM = {'wood': 220.0, 'gold': 40.0, 'cord': 30.0, 'wrap': 60.0, 'ribbon': 60.0, 'candle': 40.0,
           'dark': 40.0, 'gilt': 40.0}

HANDLE = (-215.0, 1280.0)   # z of its hidden ends, inside the wrap and inside the ferrule
FERRULE_Z = 1256.0
BOW_Z = 1080.0              # clear of a sitting rider's feet (0.75 blocks ahead of the seat)
WRAP_S = (180.0, 365.0)     # the binding, measured backwards from the seat
CUFF_S = (365.0, 419.5)
HOOP_S, HOOP_R, HOOP_T = 462.0, 81.5, 4.3


def smoothstep(a, b, x):
    t = min(max((x - a) / (b - a), 0.0), 1.0)
    return t * t * (3 - 2 * t)


def unit(v):
    v = np.asarray(v, float)
    return v / (np.linalg.norm(v, axis=-1, keepdims=True) + 1e-12)


# ---------------------------------------------------------------- mesh primitives

class Mesh:
    def __init__(self):
        self.groups = {g: [] for g in GROUPS}
        self.pivot = np.zeros(3)

    def quad(self, group, material, verts):
        """verts: 4 x (pos, normal, u, v): u a 0..1 fraction of the cell width, v in mm inside one tile (tiled
        materials) or a 0..1 fraction of the cell height."""
        x, y, w, h = CELL[material]
        tile = TILE_MM.get(material)
        out = []
        for pos, nrm, u, v in verts:
            fv = v / tile if tile else v
            fv = min(max(fv, 0.0), 1.0)
            out.append((np.asarray(pos, float), unit(nrm), (x + 0.5 + u * (w - 1)) / TEX,
                        (y + 0.5 + fv * (h - 1)) / TEX))
        self.groups[group].append(out)


def face(mesh, group, material, pts, n, uv):
    """One flat quad, wound to face n."""
    verts = [(p, n, u, v) for p, (u, v) in zip(pts, uv)]
    if np.dot(np.cross(pts[2] - pts[0], pts[3] - pts[1]), n) < 0:
        verts.reverse()
    mesh.quad(group, material, verts)


def grid(mesh, group, material, P, N, U, V):
    """Quads over a grid of points P[row, col]: rows follow v, columns follow u. For a tiled material rows are
    inserted where v crosses a tile edge, so every quad samples a single tile. Quads are wound to face N."""
    P, N, U, V = (np.asarray(a, float) for a in (P, N, U, V))
    tile = TILE_MM.get(material)
    rows = [(P[0], N[0], V[0])]
    for i in range(len(V) - 1):
        v0, v1 = V[i], V[i + 1]
        if tile and abs(v1 - v0) > 1e-9:
            lo, hi = min(v0, v1), max(v0, v1)
            k = math.floor(lo / tile + 1e-9) + 1
            cuts = []
            while k * tile < hi - 1e-6:
                cuts.append((k * tile - v0) / (v1 - v0))
                k += 1
            for c in sorted(cuts):
                rows.append((P[i] * (1 - c) + P[i + 1] * c, unit(N[i] * (1 - c) + N[i + 1] * c), v0 + (v1 - v0) * c))
        rows.append((P[i + 1], N[i + 1], v1))
    for (pa, na, va), (pb, nb, vb) in zip(rows, rows[1:]):
        if tile:
            base = math.floor((va + vb) / 2 / tile) * tile
            va, vb = va - base, vb - base
        for j in range(len(U) - 1):
            verts = [(pa[j], na[j], U[j], va), (pa[j + 1], na[j + 1], U[j + 1], va),
                     (pb[j + 1], nb[j + 1], U[j + 1], vb), (pb[j], nb[j], U[j], vb)]
            f = np.cross(verts[2][0] - verts[0][0], verts[3][0] - verts[1][0])
            if np.dot(f, na[j] + na[j + 1] + nb[j] + nb[j + 1]) < 0:
                verts.reverse()
            mesh.quad(group, material, verts)


def grid_normals(P, ref, closed_cols=False, closed_rows=False):
    """Smooth normals of a grid surface, turned to agree with ref[row, col]. With closed columns (or rows) the
    last one repeats the first."""
    def diff(A, axis, closed):
        if closed:
            core = np.take(A, range(A.shape[axis] - 1), axis=axis)
            d = np.roll(core, -1, axis=axis) - np.roll(core, 1, axis=axis)
            return np.concatenate([d, np.take(d, [0], axis=axis)], axis=axis)
        return np.gradient(A, axis=axis)

    n = np.cross(diff(P, 1, closed_cols), diff(P, 0, closed_rows))
    length = np.linalg.norm(n, axis=-1)
    for i in range(len(P)):          # a row shrunk to a point (a tip) borrows its neighbour's normals
        if length[i].max() < 1e-9:
            j = i - 1 if i > 0 else i + 1
            n[i] = n[j]
            length[i] = length[j]
    n = n / (length[..., None] + 1e-12)
    sign = np.where((n * ref).sum(-1, keepdims=True) < 0, -1.0, 1.0)
    return n * sign


def frames(C, up=(0.0, 1.0, 0.0)):
    """Tangents and a parallel-transported frame (A, B) along a centreline, A starting as up x T."""
    T = unit(np.gradient(C, axis=0))
    a = unit(np.cross(up, T[0]))
    A = []
    for t in T:
        a = unit(a - np.dot(a, t) * t)
        A.append(a)
    A = np.array(A)
    return T, A, np.cross(T, A)


def tube(mesh, group, material, C, A, B, section, V, seg, u=(0.0, 1.0), closed_rows=False):
    """Sweeps a closed cross-section along C; section(i, phi) gives the offsets along A[i] and B[i]."""
    phis = 2 * math.pi * np.arange(seg + 1) / seg
    P = np.zeros((len(C), seg + 1, 3))
    for i in range(len(C)):
        for j, phi in enumerate(phis):
            a, b = section(i, phi)
            P[i, j] = C[i] + a * A[i] + b * B[i]
    P[:, -1] = P[:, 0]
    N = grid_normals(P, P - C[:, None], closed_cols=True, closed_rows=closed_rows)
    grid(mesh, group, material, P, N, u[0] + (u[1] - u[0]) * phis / (2 * math.pi), V)


def P_(s, r, material, smooth=False, v=None):
    return (s, r, material, smooth, v)


def lathe(mesh, group, profile, origin, axis, ref, seg=48, flat=False, phase=0.0):
    """Surface of revolution about axis through origin. profile: [(s, r, material, smooth, v)] with s along the
    axis, traversed with s increasing along the outer surface. Each span takes the material of its first point;
    smooth points share one normal between the spans meeting there. A tiled material's v follows the arc length
    through a run of it; other materials take v from the points. flat gives each of the seg sides one normal."""
    origin, axis = np.asarray(origin, float), unit(axis)
    ref = unit(np.asarray(ref, float) - np.dot(ref, axis) * axis)
    ref2 = np.cross(axis, ref)
    thetas = phase + 2 * math.pi * np.arange(seg + 1) / seg

    def radial(th):
        return math.cos(th) * ref + math.sin(th) * ref2

    def point(p, th):
        return origin + p[0] * axis + p[1] * radial(th)

    spans = []
    for a, b in zip(profile, profile[1:]):
        ds, dr = b[0] - a[0], b[1] - a[1]
        length = math.hypot(ds, dr)
        spans.append(((-dr / length, ds / length) if length > 1e-9 else None, length))

    def span_normal(k):
        return spans[k][0] if 0 <= k < len(spans) else None

    run = 0.0
    for k, (a, b) in enumerate(zip(profile, profile[1:])):
        n2, length = spans[k]
        material = a[2]
        if k > 0 and profile[k - 1][2] != material:
            run = 0.0
        if n2 is None:
            continue
        na = nb = np.array(n2)
        prev, nxt = span_normal(k - 1), span_normal(k + 1)
        if a[3] and prev is not None:
            na = unit(na + np.array(prev))
        if b[3] and nxt is not None:
            nb = unit(nb + np.array(nxt))
        if material in TILE_MM:
            va, vb = run, run + length
        else:
            va, vb = (a[4] if a[4] is not None else 0.0), (b[4] if b[4] is not None else 1.0)
        run += length
        if flat:
            for j in range(seg):
                r = radial((thetas[j] + thetas[j + 1]) / 2)
                P = [[point(a, thetas[j]), point(a, thetas[j + 1])], [point(b, thetas[j]), point(b, thetas[j + 1])]]
                N = [[na[0] * axis + na[1] * r] * 2, [nb[0] * axis + nb[1] * r] * 2]
                grid(mesh, group, material, P, N, [j / seg, (j + 1) / seg], [va, vb])
        else:
            P = [[point(a, th) for th in thetas], [point(b, th) for th in thetas]]
            N = [[na[0] * axis + na[1] * radial(th) for th in thetas], [nb[0] * axis + nb[1] * radial(th) for th in thetas]]
            grid(mesh, group, material, P, N, np.arange(seg + 1) / seg, [va, vb])


def bead(s0, width, r, height, material, steps=5):
    """Half-round bead from s0 to s0 + width on a surface of radius r."""
    return [P_(s0 + width * (1 - math.cos(math.pi * k / steps)) / 2, r + height * math.sin(math.pi * k / steps),
               material, 0 < k < steps) for k in range(steps + 1)]


def box(mesh, group, material, centre, axes, half):
    """Oriented box; axes are three unit vectors, half the half sizes along them."""
    c = np.asarray(centre, float)
    ax = [np.asarray(a, float) * h for a, h in zip(axes, half)]
    for k in range(3):
        i, j = (k + 1) % 3, (k + 2) % 3
        for sgn in (1, -1):
            f = c + sgn * ax[k]
            pts = [f - ax[i] - ax[j], f + ax[i] - ax[j], f + ax[i] + ax[j], f - ax[i] + ax[j]]
            face(mesh, group, material, pts, sgn * unit(ax[k]), [(0, 0), (1, 0), (1, 1), (0, 1)])


def ribbon(mesh, group, C, W, width, thick=1.1, cup=None, closed=False, notch=0.0, cols=4):
    """Satin ribbon along centreline C, its width along W: both faces, both edges and (when open) the end caps,
    the far end cut into a dovetail notch deep. cup bows the ribbon across its width."""
    C = np.asarray(C, float)
    R = len(C)
    T = unit(np.gradient(C, axis=0))
    if closed:
        T[0] = T[-1] = unit(C[1] - C[-2])
    W = unit(np.asarray(W, float) - (np.asarray(W, float) * T).sum(-1, keepdims=True) * T)
    F = np.cross(W, T)
    s = np.linspace(-1, 1, cols + 1)
    cup = np.zeros(R) if cup is None else np.asarray(cup, float)
    V = np.concatenate([[0.0], np.cumsum(np.linalg.norm(np.diff(C, axis=0), axis=1))])

    def surface(side):
        P = (C[:, None] + W[:, None] * (s[None, :, None] * width[:, None, None] / 2)
             + F[:, None] * (side * thick / 2 + cup[:, None, None] * (1 - s[None, :, None] ** 2)))
        if notch and not closed:
            P[-1] -= T[-1] * (notch * (1 - np.abs(s)))[:, None]
        return P

    top, bottom = surface(1), surface(-1)
    Fg = np.repeat(F[:, None], cols + 1, axis=1)
    U = (s + 1) / 2
    grid(mesh, group, 'ribbon', top, grid_normals(top, Fg, closed_rows=closed), U, V)
    grid(mesh, group, 'ribbon', bottom, grid_normals(bottom, -Fg, closed_rows=closed), U, V)
    for k, sgn in ((0, -1.0), (cols, 1.0)):
        E = np.stack([bottom[:, k], top[:, k]], axis=1)
        grid(mesh, group, 'ribbon', E, np.repeat((W * sgn)[:, None], 2, axis=1), [0.0, 0.02] if sgn < 0 else [0.98, 1.0], V)
    if not closed:
        for i, sgn in ((0, -1.0), (R - 1, 1.0)):
            for k in range(cols):
                face(mesh, group, 'ribbon', [bottom[i, k], bottom[i, k + 1], top[i, k + 1], top[i, k]], sgn * T[i],
                     [(U[k], 0), (U[k + 1], 0), (U[k + 1], 0.5), (U[k], 0.5)])


def ellipsoid(mesh, group, material, centre, axes, radii, rows=12, seg=24, pinch=None):
    """Ellipsoid; pinch(lat, lon) may scale the radius for folds."""
    c = np.asarray(centre, float)
    X, Y, Z = (np.asarray(a, float) for a in axes)
    lats = np.linspace(-math.pi / 2, math.pi / 2, rows + 1)
    lons = 2 * math.pi * np.arange(seg + 1) / seg
    P = np.zeros((rows + 1, seg + 1, 3))
    for i, la in enumerate(lats):
        for j, lo in enumerate(lons):
            k = pinch(la, lo) if pinch else 1.0
            P[i, j] = c + k * (radii[0] * math.cos(la) * math.cos(lo) * X + radii[1] * math.cos(la) * math.sin(lo) * Y
                               + radii[2] * math.sin(la) * Z)
    P[:, -1] = P[:, 0]
    N = grid_normals(P, P - c, closed_cols=True)
    grid(mesh, group, material, P, N, lons / (2 * math.pi), (lats - lats[0]) / math.pi * TILE_MM.get(material, 1.0))


# ---------------------------------------------------------------- the broom

def handle_centre(z):
    s = (z - HANDLE[0]) / (HANDLE[1] - HANDLE[0])
    x = 5.0 * math.sin(2 * math.pi * s) * math.sin(math.pi * s)
    # sags into a deep V where the bow and lantern hang, as in the art
    y = -125.0 * math.exp(-((z - BOW_Z) / 75.0) ** 2) + 14.0 * smoothstep(1150.0, 1310.0, z)
    return np.array([x, y, z])


def base_radius(z):
    return 30.0 - 5.4 * (z - HANDLE[0]) / (HANDLE[1] - HANDLE[0])


def handle_radius(z, theta):
    return base_radius(z) * (1 + 0.022 * math.cos(2 * (theta - 0.7 + z * 0.0021)))


def handle(mesh):
    zs = [np.arange(HANDLE[0], HANDLE[1] + 1e-6, 8.0), np.arange(BOW_Z - 200, BOW_Z + 200, 4.0)]
    zs = np.unique(np.round(np.concatenate(zs), 3))
    C = np.array([handle_centre(z) for z in zs])
    _, A, B = frames(C)
    V = np.concatenate([[0.0], np.cumsum(np.linalg.norm(np.diff(C, axis=0), axis=1))])

    def section(i, phi):
        r = handle_radius(zs[i], phi)
        return r * math.cos(phi), r * math.sin(phi)

    tube(mesh, 'solid', 'wood', C, A, B, section, V, 40)


def ferrule(mesh):
    """Gilded end cap: collar, engraved sleeve, beads, a turned finial and a garnet cabochon."""
    c = handle_centre(FERRULE_Z)
    axis = unit(handle_centre(FERRULE_Z + 5) - handle_centre(FERRULE_Z - 5))
    r = base_radius(FERRULE_Z)
    p = [P_(-3.0, r - 0.6, 'gilt'), P_(0.0, r + 1.0, 'gilt')]
    p += bead(0.2, 3.6, r + 1.3, 1.2, 'gilt')
    p += [P_(4.0, r + 0.9, 'gilt'), P_(4.6, r + 1.1, 'engraved', v=0.0), P_(33.4, r + 1.1, 'gilt', v=1.0)]
    p += bead(33.6, 4.6, r + 1.5, 1.9, 'gilt')
    p += [P_(38.8, r + 1.2, 'gilt', True), P_(40.4, r + 0.2, 'gilt', True)]
    for k in range(9):                            # turned bulb
        t = k / 8
        p.append(P_(40.4 + 15.4 * t, r + 0.2 + 2.2 * math.sin(math.pi * t ** 0.8) - 4.0 * t ** 2, 'gilt', True))
    p += [P_(56.2, r - 3.3, 'gilt'), P_(56.4, r - 2.6, 'gilt', True), P_(57.2, r - 2.4, 'gilt', True),
          P_(58.0, r - 2.8, 'gilt')]
    gem = r - 4.3
    for k in range(9):                            # cabochon
        a = k / 8 * math.pi / 2
        p.append(P_(58.0 + 7.4 * math.sin(a), gem * math.cos(a), 'garnet', 0 < k < 8, v=k / 8))
    lathe(mesh, 'solid', p, c, axis, (1.0, 0.0, 0.0), seg=40)


def binding(mesh):
    """Grey leather wrap with a black cord, the filigree cuff and the hoop."""
    s0, s1 = WRAP_S

    def wrap_r(s):
        t = min(max((s - 195.0) / (s1 - 195.0), 0.0), 1.0)
        return 40.0 + (74.0 - 40.0) * t ** 1.1

    p = [P_(176.0, 24.0, 'wrap'), P_(178.0, 29.0, 'wrap', True), P_(180.0, 33.0, 'wrap', True),
         P_(183.0, 36.5, 'wrap', True), P_(188.0, 38.5, 'wrap', True)]
    p += [P_(s, wrap_r(s), 'wrap', True) for s in np.arange(195.0, 240.0, 8.0)]
    # black cord lashing
    p += [P_(240.0, wrap_r(240.0), 'cord'), P_(240.6, wrap_r(240.6) + 2.0, 'cord', True),
          P_(241.6, wrap_r(241.6) + 2.9, 'cord', True), P_(252.4, wrap_r(252.4) + 2.9, 'cord', True),
          P_(253.4, wrap_r(253.4) + 2.0, 'cord', True), P_(254.0, wrap_r(254.0), 'wrap')]
    p += [P_(s, wrap_r(s), 'wrap', True) for s in np.arange(262.0, s1, 8.0)]
    p.append(P_(s1, wrap_r(s1), 'gilt'))
    # filigree cuff between gold lips
    c0, c1 = CUFF_S
    p += bead(c0, 4.6, 76.8, 1.8, 'gilt')
    p[-1] = P_(c0 + 4.6, 76.8, 'lattice', v=0.0)
    for k in range(1, 9):
        t = k / 8
        s = c0 + 4.6 + (c1 - c0 - 9.2) * t
        p.append(P_(s, 77.0 + 1.6 * math.sin(math.pi * t), 'lattice' if k < 8 else 'gilt', True, v=t))
    p += bead(c1 - 4.6, 4.6, 76.8, 1.8, 'gilt')[1:]
    p += [P_(c1, 74.5, 'gilt'), P_(c1, 72.0, 'bristle', v=0.0), P_(c1, 0.0, 'bristle', v=0.02)]
    lathe(mesh, 'solid', p, (0.0, 0.0, 0.0), (0.0, 0.0, -1.0), (1.0, 0.0, 0.0), seg=64)

    # the hoop, a thin gold ring with a raised centre line
    rows = 128
    ang = 2 * math.pi * np.arange(rows + 1) / rows
    C = np.stack([HOOP_R * np.cos(ang), HOOP_R * np.sin(ang), np.full_like(ang, -HOOP_S)], axis=1)
    A = np.stack([np.cos(ang), np.sin(ang), np.zeros_like(ang)], axis=1)
    B = np.tile([0.0, 0.0, 1.0], (rows + 1, 1))

    def ring(i, phi):
        r = HOOP_T * (1 + 0.08 * math.cos(phi) ** 8)
        return r * math.cos(phi), r * 0.8 * math.sin(phi)

    tube(mesh, 'solid', 'gilt', C, A, B, ring, HOOP_R * ang, 14, closed_rows=True)


def bristles(mesh):
    """Three layers of long feathery locks fanning out of the cuff, a core to fill the fan and stray fibres."""
    rng = np.random.default_rng(1031)
    z0 = -(CUFF_S[1] - 12.0)                     # roots hidden inside the cuff
    core = [P_(CUFF_S[1] + 0.1, 71.0, 'bristle', v=0.0)]
    for k, (s, r) in enumerate(((460, 64), (510, 52), (550, 36), (580, 20), (600, 8), (610, 0))):
        core.append(P_(float(s), float(r), 'bristle', 0 < k < 5, v=0.1 + 0.08 * k))
    lathe(mesh, 'solid', core, (0.0, 0.0, 0.0), (0.0, 0.0, -1.0), (1.0, 0.0, 0.0), seg=32)

    rows = 18
    ts = (1 - np.cos(math.pi * np.arange(rows + 1) / rows)) / 2
    layers = ((6, 8.0, 44.0, 370.0, 40.0, 14.0), (10, 24.0, 86.0, 400.0, 42.0, 13.0), (20, 48.0, 132.0, 445.0, 46.0, 11.0),
              (30, 68.0, 178.0, 478.0, 44.0, 9.0))
    for li, (count, r0, rt, length, w0, h0) in enumerate(layers):
        for k in range(count):
            th0 = 2 * math.pi * (k + 0.37 * li + rng.uniform(-0.3, 0.3)) / count
            L = length * rng.uniform(0.9, 1.08)
            tip = rt * rng.uniform(0.86, 1.1)
            twist = 0.14 + rng.normal(0, 0.05)
            droop = 60.0 * rng.uniform(0.7, 1.3)
            wave, phase = rng.uniform(5.0, 12.0), rng.uniform(0, 2 * math.pi)
            flip = rng.uniform(-0.25, 0.25)       # locks lie a little tilted, like overlapping feathers
            C = []
            for t in ts:
                f = (1 - (1 - t) ** 2) * t ** 0.6
                r = r0 + (tip - r0) * f
                th = th0 + twist * t + 0.05 * math.sin(3 * t + phase)
                side = wave * math.sin(2 * math.pi * 1.1 * t + phase) * t
                C.append([r * math.cos(th) - side * math.sin(th), r * math.sin(th) + side * math.cos(th) - droop * t * t,
                          z0 - L * t])
            C = np.array(C)
            T = unit(np.gradient(C, axis=0))
            radial = unit(np.stack([C[:, 0], C[:, 1] + droop * ts * ts, np.zeros(rows + 1)], axis=1))
            N = unit(radial - (radial * T).sum(-1, keepdims=True) * T)
            Wd = np.cross(T, N)
            N, Wd = unit(N * math.cos(flip) + Wd * math.sin(flip)), unit(Wd * math.cos(flip) - N * math.sin(flip))
            width = w0 * (0.62 + 0.55 * np.sin(math.pi * np.minimum(ts * 1.3, 1.0)) ** 2) * (1 - ts ** 4) ** 0.55 + 0.3
            thick = h0 * (1 - 0.65 * ts) * (1 - ts ** 3) + 0.25
            u0 = rng.uniform(0, 0.82)

            def section(i, phi, width=width, thick=thick):
                return width[i] / 2 * math.cos(phi), thick[i] / 2 * math.sin(phi)

            tube(mesh, 'solid', 'bristle', C, Wd, N, section, ts, 6, u=(u0, u0 + 0.18))

    # stray fibres lifting out of the fan
    for k in range(24):
        th0 = rng.uniform(0, 2 * math.pi)
        r0 = rng.uniform(55, 72)
        L = rng.uniform(300, 500)
        tip = rng.uniform(120, 200)
        droop = rng.uniform(20, 70)
        fibre_rows = 12
        t = np.linspace(0, 1, fibre_rows + 1)
        f = (1 - (1 - t) ** 2) * t ** 0.6
        r = r0 + (tip - r0) * f
        th = th0 + rng.normal(0, 0.1) * t
        C = np.stack([r * np.cos(th), r * np.sin(th) - droop * t * t, z0 - L * t], axis=1)
        _, A, B = frames(C, up=(math.cos(th0), math.sin(th0), 0.0))
        rad = 1.3 * (1 - t ** 1.5) + 0.15

        def fibre(i, phi, rad=rad):
            return rad[i] * math.cos(phi), rad[i] * math.sin(phi)

        u0 = rng.uniform(0, 0.95)
        tube(mesh, 'solid', 'bristle', C, A, B, fibre, t, 4, u=(u0, u0 + 0.04))


def bow_frame():
    """Knot centre and the handle's frame at the bow."""
    c = handle_centre(BOW_Z)
    T = unit(handle_centre(BOW_Z + 4) - handle_centre(BOW_Z - 4))
    return c, T, base_radius(BOW_Z)


def bow(mesh):
    """Red satin bow tied on top of the handle: a band around the handle, the knot on top, two loops spreading
    left and right and two dovetailed tails trailing back and draping down the sides. Returns the hook under the
    band that the lantern hangs from."""
    c, T, r = bow_frame()
    right = unit(np.cross(T, (0.0, 1.0, 0.0)))    # -x
    up = np.cross(right, T)
    # band wrapped round the handle
    ang = 2 * math.pi * np.arange(49) / 48
    C = np.array([c + (r + 0.9) * (math.cos(a) * right + math.sin(a) * up) for a in ang])
    ribbon(mesh, 'solid', C, np.tile(T, (49, 1)), np.full(49, 17.0), thick=1.0, closed=True,
           cup=np.zeros(49))
    knot = c + up * (r + 5.5)

    def folds(la, lo):
        return 1.0 - 0.07 * math.cos(la) ** 2 * math.cos(3 * lo) ** 2

    ellipsoid(mesh, 'solid', 'ribbon', knot, (right, T, up), (11.5, 10.0, 6.5), pinch=folds)

    # loops: a ribbon folded back on itself, its outer layer facing up, pinched into the knot
    rows = 64
    for sgn in (-1.0, 1.0):                      # left loop, right loop
        side = right * sgn
        d = unit(side + T * 0.9 + up * 0.3)       # out to the side and forward, as drawn from the rider's view
        across = unit(np.cross(d, up))
        L, depth = 64.0, 15.0
        C, W, width, cup = [], [], [], []
        for k in range(rows + 1):
            u = k / rows
            bulge = math.sin(math.pi * u)
            C.append(knot + d * L * bulge ** 2 + up * (depth / 2 * (1 + math.sin(2 * math.pi * u)) + 2.0)
                     + across * 3.0 * math.sin(2 * math.pi * u) * sgn)
            twist = 0.12 * bulge * sgn
            W.append(unit(across * math.cos(twist) + up * math.sin(twist)))
            width.append(6.0 + 34.0 * bulge ** 1.4)          # a teardrop lobe, pinched at the knot
            cup.append(3.5 * bulge)
        C[-1] = C[0]
        ribbon(mesh, 'solid', np.array(C), np.array(W), np.array(width), thick=1.0, closed=True, cup=np.array(cup))

    # tails trailing back from the knot and draping down either side of the handle
    for sgn, reach, spread in ((-1.0, 40.0, 42.0), (1.0, 58.0, 46.0)):   # left tail (shorter), right tail
        side = right * sgn
        rows = 20
        C, W = [], []
        for k in range(rows + 1):
            t = k / rows
            q = knot + side * spread * math.sin(math.pi / 2 * t) - T * reach * t - up * (r + 12.0) * t ** 1.6
            C.append(q)
            tangent = (side * spread * math.pi / 2 * math.cos(math.pi / 2 * t) - T * reach
                       - up * (r + 12.0) * 1.6 * t ** 0.6)
            outward = unit(up * math.cos(1.3 * t) + side * math.sin(1.3 * t))   # lies flat, then hangs
            W.append(unit(np.cross(unit(tangent), outward)))
        width = np.linspace(17.0, 23.0, rows + 1)
        cup = 1.6 * np.sin(np.pi * np.linspace(0, 1, rows + 1))
        ribbon(mesh, 'solid', np.array(C), np.array(W), width, thick=1.0, notch=8.0, cup=cup)
    return c - up * (r + 1.5)


def lantern(mesh, pivot):
    """Moroccan lantern hanging from the knot: cord and ring, gilded onion cap, six frosted panes with arched
    filigree between corner posts, a bowl base and a lit candle."""
    down = np.array([0.0, -1.0, 0.0])
    x = np.array([1.0, 0.0, 0.0])
    # cord and ring
    tube(mesh, 'lantern', 'gilt', np.array([pivot + down * t for t in (-1.5, 4.0, 8.2)]),
         np.tile(x, (3, 1)), np.tile([0.0, 0.0, 1.0], (3, 1)),
         lambda i, phi: (1.2 * math.cos(phi), 1.2 * math.sin(phi)), [0.0, 5.5, 9.7], 8)
    ring_c = pivot + down * 14.0
    ang = 2 * math.pi * np.arange(33) / 32
    C = np.array([ring_c + 6.0 * (math.cos(a) * x + math.sin(a) * -down) for a in ang])
    A = np.array([math.cos(a) * x + math.sin(a) * -down for a in ang])
    tube(mesh, 'lantern', 'gilt', C, A, np.tile([0.0, 0.0, 1.0], (33, 1)),
         lambda i, phi: (1.3 * math.cos(phi), 1.3 * math.sin(phi)), 6.0 * ang, 8, closed_rows=True)

    top = pivot + down * 20.0
    zref = x                                     # a flat side of the hexagon faces each side
    # finial ball and collar (round)
    p = []
    for k in range(9):
        a = -math.pi / 2 + math.pi * k / 8
        p.append(P_(4.2 + 4.2 * math.sin(a), 4.2 * math.cos(a), 'gilt', 0 < k < 8))
    p[-1] = P_(8.2, 1.6, 'gilt')
    p += [P_(9.5, 2.2, 'gilt'), P_(10.3, 3.9, 'gilt', True), P_(11.8, 3.9, 'gilt', True), P_(12.5, 4.2, 'gilt')]
    lathe(mesh, 'lantern', p, top, down, zref, seg=20)
    # onion cap, eaves and ceiling (six flat sides)
    hexa = dict(seg=6, flat=True, phase=math.pi / 6)
    p = [P_(12.5, 4.2, 'gilt')]
    for k in range(1, 9):
        t = k / 8
        p.append(P_(12.5 + 19.5 * t, 4.2 + 23.0 * (math.sin(math.pi / 2 * t) ** 1.6) + 2.5 * math.sin(math.pi * t), 'gilt'))
    p += [P_(32.4, 33.2, 'gilt'), P_(35.4, 33.2, 'gilt'), P_(36.0, 31.0, 'dark'), P_(36.0, 0.0, 'dark')]
    lathe(mesh, 'lantern', p, top, down, zref, **hexa)
    # floor, bottom rim and bowl
    p = [P_(86.0, 0.0, 'dark'), P_(86.0, 31.0, 'gilt'), P_(86.6, 33.2, 'gilt'), P_(89.6, 33.2, 'gilt'),
         P_(90.2, 30.5, 'gilt')]
    for k in range(1, 7):
        t = k / 6
        p.append(P_(90.2 + 18.0 * t, 30.5 - 24.0 * t ** 1.4, 'gilt'))
    lathe(mesh, 'lantern', p, top, down, zref, **hexa)
    # bottom finial
    p = [P_(108.0, 0.0, 'gilt'), P_(108.2, 6.4, 'gilt')]
    for k in range(1, 8):
        a = k / 8 * math.pi
        p.append(P_(108.2 + 5.6 * (1 - math.cos(a)), 3.2 + 3.2 * math.sin(a), 'gilt', True))
    p += [P_(119.6, 2.0, 'gilt', True), P_(123.5, 0.0, 'gilt')]
    lathe(mesh, 'lantern', p, top, down, zref, seg=20)

    # corner posts and panes
    ref2 = np.cross(down, x)
    for j in range(6):
        a0 = math.pi / 6 + j * math.pi / 3
        a1 = a0 + math.pi / 3
        v0 = math.cos(a0) * x + math.sin(a0) * ref2
        v1 = math.cos(a1) * x + math.sin(a1) * ref2
        box(mesh, 'lantern', 'gilt', top + down * 61.0 + v0 * 29.8, (v0, np.cross(down, v0), down), (1.9, 1.9, 25.0))
        pts = [top + down * 36.0 + v0 * 28.8, top + down * 36.0 + v1 * 28.8,
               top + down * 86.0 + v1 * 28.8, top + down * 86.0 + v0 * 28.8]
        face(mesh, 'glass', 'glass', pts, unit(v0 + v1), [(0, 0), (1, 0), (1, 1), (0, 1)])
    # candle, wick and flame
    lathe(mesh, 'lantern', [P_(60.0, 0.0, 'candle'), P_(60.0, 7.2, 'candle', True), P_(60.8, 8.0, 'candle', True),
                            P_(86.0, 8.0, 'candle')], top, down, zref, seg=16)
    tube(mesh, 'lantern', 'dark', np.array([top + down * t for t in (56.4, 60.5)]), np.tile(x, (2, 1)),
         np.tile([0.0, 0.0, 1.0], (2, 1)), lambda i, phi: (0.55 * math.cos(phi), 0.55 * math.sin(phi)), [0.0, 4.0], 6)
    flame = [(42.0, 0.0), (43.6, 0.9), (46.5, 2.1), (50.2, 3.3), (53.4, 3.6), (55.6, 3.0), (57.0, 1.8), (57.6, 0.0)]
    lathe(mesh, 'flame', [P_(s, r, 'flame', 0 < k < len(flame) - 1, v=k / (len(flame) - 1))
                          for k, (s, r) in enumerate(flame)], top, down, zref, seg=16)


def build():
    mesh = Mesh()
    handle(mesh)
    ferrule(mesh)
    binding(mesh)
    bristles(mesh)
    hook = bow(mesh)
    mesh.pivot = hook
    lantern(mesh, hook)
    return mesh


def write_mesh(mesh, path):
    data = bytearray(struct.pack('>i3f', MAGIC, *(mesh.pivot * MM)))
    for name in GROUPS:
        quads = mesh.groups[name]
        data += struct.pack('>i', len(quads))
        for quad in quads:
            for pos, nrm, u, v in quad:
                data += struct.pack('>8f', *(pos * MM), *nrm, u, v)
    with gzip.open(path, 'wb', compresslevel=9) as f:
        f.write(bytes(data))


def write_obj(mesh, folder):
    with open(os.path.join(folder, 'broom.mtl'), 'w', encoding='utf-8') as f:
        f.write('newmtl broom\nKd 1 1 1\nmap_Kd broom.png\n')
    n = 0
    with open(os.path.join(folder, 'broom.obj'), 'w', encoding='utf-8') as f:
        f.write('mtllib broom.mtl\nusemtl broom\n')
        for name in GROUPS:
            f.write(f'g {name}\n')
            for quad in mesh.groups[name]:
                for pos, nrm, u, v in quad:
                    p = pos * MM
                    f.write(f'v {p[0]:.6g} {p[1]:.6g} {p[2]:.6g}\nvt {u:.6g} {1 - v:.6g}\nvn {nrm[0]:.5g} {nrm[1]:.5g} {nrm[2]:.5g}\n')
                f.write('f ' + ' '.join(f'{n + i}/{n + i}/{n + i}' for i in range(1, 5)) + '\n')
                n += 4


# ---------------------------------------------------------------- textures

RNG = np.random.default_rng(413)


def periodic_noise(h, w, sy, sx=None, rng=RNG):
    """Tileable smooth noise in about [-1, 1]; sy, sx are the feature sizes in px down and across."""
    sx = sy if sx is None else sx
    white = rng.standard_normal((h, w))
    fy = np.fft.fftfreq(h)[:, None]
    fx = np.fft.fftfreq(w)[None, :]
    spectrum = np.fft.fft2(white) * np.exp(-((fx * sx) ** 2 + (fy * sy) ** 2) * math.pi ** 2)
    n = np.real(np.fft.ifft2(spectrum))
    return n / (np.abs(n).max() + 1e-9)


def rgb(h, w, colour):
    return np.ones((h, w, 3)) * np.array(colour, float)


def shade(height, strength, light=(-0.45, -0.6, 0.66)):
    """Lambert shading of a height field lit from the upper left, 1 on flat ground."""
    gy, gx = np.gradient(height)
    n = np.dstack([-gx * strength, -gy * strength, np.ones_like(gx)])
    n /= np.linalg.norm(n, axis=2, keepdims=True)
    light = np.array(light) / np.linalg.norm(light)
    return (n * light).sum(axis=2) / light[2]


def wood(h, w):
    """Pearl-white lacquered wood: fine long grain under the lacquer."""
    img = rgb(h, w, (238, 235, 228))
    grain = periodic_noise(h, w, 40, 1.6)
    img += grain[..., None] * np.array([7, 8, 11.0])
    img += periodic_noise(h, w, 90, 30)[..., None] * np.array([4, 5, 7.0])
    rays = np.clip(periodic_noise(h, w, 6, 0.9) - 0.55, 0, 1) * 30     # thin darker grain lines
    img -= rays[..., None] * np.array([0.8, 0.85, 1.0])
    return img


def bristle(h, w):
    """Feathery white bristle fibres running from the root (top) to the tip (bottom)."""
    fibres = periodic_noise(h, w, 60, 1.1) * 0.6 + periodic_noise(h, w, 25, 2.5) * 0.4
    img = rgb(h, w, (247, 247, 250)) - np.clip(-fibres, 0, 1)[..., None] * np.array([48, 46, 40.0])
    img += np.clip(fibres, 0, 1)[..., None] * 6
    t = np.linspace(0, 1, h)[:, None, None]
    img *= 0.72 + 0.28 * np.clip(t / 0.3, 0, 1) ** 0.7                 # shadowed near the binding
    img = img * (1 - 0.06 * t) + np.array([200, 210, 228.0]) * 0.06 * t  # cooler, finer tips
    return img


def gold(h, w, base=(232, 190, 122)):
    img = rgb(h, w, base) + periodic_noise(h, w, 30, 1.2)[..., None] * 12 + periodic_noise(h, w, 3)[..., None] * 5
    return img


def engraved(h, w, repeats=6):
    """Gold sleeve engraved with a running scroll, six repeats around."""
    img = gold(h, w, (236, 196, 128))
    s = 2
    layer = Image.new('L', (w * s, h * s), 0)
    draw = ImageDraw.Draw(layer)
    period = w * s / repeats
    for k in range(repeats + 1):
        x0 = k * period
        cy = h * s / 2
        # a wave through the band with a spiral curled into each crest and trough
        pts = [(x0 + period * t / 60, cy + h * s * 0.22 * math.sin(2 * math.pi * t / 60)) for t in range(61)]
        draw.line(pts, fill=255, width=5)
        for sgn, off in ((1, 0.25), (-1, 0.75)):
            cx, cyy = x0 + period * off, cy - sgn * h * s * 0.02
            spiral = [(cx + (14 - 1.9 * a) * math.cos(a + (0 if sgn > 0 else math.pi)) * 1.2,
                       cyy + sgn * (14 - 1.9 * a) * math.sin(a)) for a in np.linspace(0, 6.5, 50)]
            draw.line(spiral, fill=255, width=4)
            for leaf in (-1, 1):
                lx = cx + leaf * period * 0.12
                draw.ellipse([lx - 9, cyy + sgn * 26 - 5, lx + 9, cyy + sgn * 26 + 5], fill=200)
    for y in (int(h * s * 0.08), int(h * s * 0.92)):
        draw.line([(0, y), (w * s, y)], fill=255, width=4)
    groove = np.asarray(layer.resize((w, h), Image.LANCZOS).filter(ImageFilter.GaussianBlur(0.6)), float) / 255
    lit = shade(-groove, 6.0)
    img = img * (1 - 0.45 * groove[..., None]) * np.clip(lit, 0.5, 1.3)[..., None]
    return img


def cord(h, w):
    """Black waxed cord in tight turns."""
    y, x = np.mgrid[0:h, 0:w].astype(float)
    turns = 26
    phase = (y / h * turns + x / w * 2) % 1.0
    round_ = np.sin(phase * math.pi)
    img = rgb(h, w, (20, 20, 23)) * (0.55 + 0.45 * round_[..., None]) + (round_ ** 12)[..., None] * 55
    return img + periodic_noise(h, w, 2)[..., None] * 3


def wrap(h, w):
    """Grey leather strap wound in a spiral, each turn overlapping the next."""
    y, x = np.mgrid[0:h, 0:w].astype(float)
    turns_per_tile = 3
    phase = (y / h * turns_per_tile + x / w * 1.0) % 1.0
    img = rgb(h, w, (150, 150, 155))
    img += periodic_noise(h, w, 2.5)[..., None] * 9 + periodic_noise(h, w, 14)[..., None] * 7
    img *= (0.84 + 0.2 * phase ** 0.7)[..., None]                     # each turn rises to its edge
    edge = np.exp(-((1 - phase) / 0.025) ** 2) + np.exp(-(phase / 0.02) ** 2) * 0.8
    img *= (1 - 0.35 * np.clip(edge, 0, 1))[..., None]
    stitch = ((x + y * 0.8) % 9 < 3.5) & (np.abs(phase - 0.93) < 0.012)
    img[stitch] *= 0.7
    return img


def lattice(h, w, repeats=14):
    """Ivory cuff under raised gold lattice: diamonds with pearls at the crossings."""
    y, x = np.mgrid[0:h, 0:w].astype(float)
    pu = w / repeats
    pv = h / 2
    a = (x / pu + y / pv) % 1.0
    b = (x / pu - y / pv) % 1.0
    bar = np.maximum(np.clip(1 - np.abs(a - 0.5) / 0.07, 0, 1), np.clip(1 - np.abs(b - 0.5) / 0.07, 0, 1))
    cu, cv = (x / pu) % 1.0, (y / pv) % 1.0
    pearl = np.clip(1 - np.hypot(np.minimum(cu, 1 - cu) * pu, (np.abs(cv - 0.5)) * pv) / 5.0, 0, 1)
    height = np.maximum(bar ** 0.5 * 0.8, np.sqrt(pearl) * 1.1)
    lit = np.clip(shade(height * 6, 1.0), 0.45, 1.5)[..., None]
    ivory = rgb(h, w, (238, 230, 212)) + periodic_noise(h, w, 4)[..., None] * 5
    gilt = gold(h, w, (230, 186, 112))
    pearl_c = rgb(h, w, (246, 242, 236))
    img = ivory * (1 - bar[..., None]) + gilt * bar[..., None]
    img = img * (1 - (pearl > 0.05)[..., None]) + pearl_c * (pearl > 0.05)[..., None]
    img *= lit
    return img * (0.9 + 0.1 * np.clip(1 - np.abs(y / h - 0.5) * 2.4, 0, 1))[..., None]


def ribbon_tex(h, w):
    """Red satin: a soft sheen across the width and a fine weave along it."""
    y, x = np.mgrid[0:h, 0:w].astype(float)
    u = x / (w - 1)
    img = rgb(h, w, (198, 34, 46))
    sheen = np.exp(-((u - 0.38) / 0.18) ** 2) * (0.7 + 0.3 * periodic_noise(h, w, 50, 60))
    img = img * (0.85 + 0.35 * sheen[..., None]) + sheen[..., None] * np.array([40, 18, 22.0])
    img *= (1 - 0.25 * (np.minimum(u, 1 - u) < 0.04))[..., None]            # woven selvedge
    img += periodic_noise(h, w, 1.0, 12)[..., None] * 6
    return img


def glass_tex(h, w):
    """Frosted cream pane lit from within, with a pointed arch of gilt filigree; alpha in the fourth channel."""
    s = 4
    ov = Image.new('L', (w * s, h * s), 0)
    d = ImageDraw.Draw(ov)
    W, H = w * s, h * s
    m = 0.1 * W
    d.rectangle([2, 2, W - 3, H - 3], outline=255, width=int(0.06 * W))
    # pointed arch window
    spring = 0.34 * H
    left, right = m + 0.06 * W, W - m - 0.06 * W
    arch = [(left, H - 0.08 * H), (left, spring)]
    for k in range(21):
        t = k / 20
        arch.append((left + (right - left) / 2 * t, spring - 0.2 * H * math.sin(math.pi / 2 * t) ** 0.8))
    for k in range(1, 21):
        t = k / 20
        arch.append(((left + right) / 2 + (right - left) / 2 * t, spring - 0.2 * H * math.cos(math.pi / 2 * t) ** 0.8))
    arch += [(right, spring), (right, H - 0.08 * H)]
    d.line(arch, fill=255, width=int(0.05 * W), joint='curve')
    d.line([((left + right) / 2, spring - 0.2 * H), ((left + right) / 2, H - 0.08 * H)], fill=255, width=int(0.03 * W))
    for yy in np.linspace(spring + 0.05 * H, H - 0.14 * H, 5):
        d.ellipse([(left + right) / 2 - 0.05 * W, yy - 0.05 * W, (left + right) / 2 + 0.05 * W, yy + 0.05 * W], fill=255)
    d.ellipse([W / 2 - 0.1 * W, 0.07 * H, W / 2 + 0.1 * W, 0.07 * H + 0.2 * W], outline=255, width=int(0.03 * W))
    frame = np.asarray(ov.resize((w, h), Image.LANCZOS), float) / 255
    y, x = np.mgrid[0:h, 0:w].astype(float)
    r = np.hypot((x / w - 0.5) * 1.3, y / h - 0.55)
    img = np.zeros((h, w, 4))
    glow = np.exp(-(r / 0.35) ** 2)
    img[..., :3] = np.array([250, 236, 205.0]) + glow[..., None] * np.array([5, 14, 30.0])
    img[..., :3] += periodic_noise(h, w, 2)[..., None] * 6
    img[..., 3] = 168 + 50 * glow
    gilt = np.array([196, 150, 84.0])
    img[..., :3] = img[..., :3] * (1 - frame[..., None]) + gilt * frame[..., None]
    img[..., 3] = img[..., 3] * (1 - frame) + 255 * frame
    return img


def flame_tex(h, w):
    v = np.linspace(0, 1, h)[:, None]                     # 0 at the tip, 1 at the wick
    u = np.abs(np.linspace(-1, 1, w))[None, :]
    core = np.exp(-((v - 0.72) / 0.28) ** 2)
    img = np.zeros((h, w, 4))
    img[..., 0] = 255
    img[..., 1] = 150 + 100 * core
    img[..., 2] = 50 + 170 * core ** 2
    img[..., 3] = 255
    blue = np.clip((v - 0.85) / 0.15, 0, 1) * (1 - u)
    img[..., :3] = img[..., :3] * (1 - blue[..., None] * 0.6) + np.array([120, 150, 255.0]) * blue[..., None] * 0.6
    return img


def garnet(h, w):
    v = np.linspace(0, 1, h)[:, None, None]               # 0 at the bezel, 1 at the top
    img = np.array([96, 6, 22.0]) * (1 - v) + np.array([196, 34, 52.0]) * v
    img = img + np.zeros((h, w, 3))
    spark = np.exp(-((v[..., 0] - 0.62) / 0.06) ** 2) * np.exp(-((np.linspace(0, 1, w)[None, :] - 0.3) / 0.08) ** 2)
    return img + spark[..., None] * 160


def build_texture():
    sheet = np.zeros((TEX, TEX, 4))
    sheet[..., 3] = 255

    def put(name, img):
        x, y, w, h = CELL[name]
        if img.shape[2] == 3:
            sheet[y:y + h, x:x + w, :3] = img
        else:
            sheet[y:y + h, x:x + w] = img

    put('wood', wood(256, 1024))
    put('bristle', bristle(256, 1024))
    put('gold', gold(128, 256))
    put('engraved', engraved(128, 512))
    put('cord', cord(128, 256))
    put('wrap', wrap(128, 512))
    put('lattice', lattice(128, 512))
    put('glass', glass_tex(256, 128))
    put('ribbon', ribbon_tex(256, 256))
    put('candle', rgb(128, 64, (240, 230, 206)) + periodic_noise(128, 64, 6)[..., None] * 5)
    put('flame', flame_tex(128, 64))
    put('garnet', garnet(128, 64))
    put('dark', rgb(128, 64, (38, 30, 22)))
    put('gilt', gold(128, 128, (236, 196, 128)))
    return Image.fromarray(np.clip(sheet, 0, 255).astype(np.uint8), 'RGBA')


def icon():
    """16 px inventory sprite (a 3D broom is too thin to read in a slot): lying from the bristles at the lower
    left to the ferrule at the upper right. t runs along the handle, p across it (positive below)."""
    colours = {
        'W': (242, 240, 234), 'w': (196, 194, 190), 'g': (156, 156, 162), 'd': (112, 112, 120), 'k': (30, 30, 34),
        'G': (236, 196, 128), 'o': (176, 128, 62), 'X': (160, 16, 36), 'R': (206, 38, 50), 'r': (140, 20, 32),
        'L': (255, 244, 206), 'l': (255, 214, 130), 'B': (212, 213, 222), 'b': (172, 173, 186),
    }
    px = {}
    for y in range(16):
        for x in range(16):
            t, p = x - y, x + y - 15
            if t >= 15:
                px[x, y] = 'X'
            elif 12 <= t <= 14 and abs(p) <= 1:
                px[x, y] = 'o' if p > 0 else 'G'
            elif 1 <= t <= 11 and 0 <= p <= 1:
                px[x, y] = 'W' if p == 0 else 'w'
            elif -3 <= t <= 0 and abs(p) <= 1 + -t * 0.45:
                px[x, y] = 'k' if t == -2 else ('d' if p >= 1 else 'g')
            elif -5 <= t <= -4 and abs(p) <= 3:
                px[x, y] = 'G' if (t + p) % 4 == 1 else 'o'
            elif t == -7 and abs(p) <= 4:
                px[x, y] = 'G'
            elif t <= -6 and abs(p) <= 3 + (-6 - t) * 0.5:
                edge = abs(p) > 2 + (-6 - t) * 0.5
                if edge and (x * 7 + y * 3) % 5 < 2:
                    continue                          # feathery ragged edge
                streak = (p * 2 + t) % 5 == 0
                px[x, y] = 'b' if p >= 3 else ('B' if streak or edge else 'W')
    # bow straddling the handle near the front, lantern hanging below
    px.update({(11, 3): 'R', (12, 2): 'R', (12, 4): 'R', (11, 5): 'r', (13, 4): 'r',
               (12, 5): 'o', (12, 6): 'G', (13, 6): 'o', (12, 7): 'L', (13, 7): 'l', (12, 8): 'o', (13, 8): 'o'})
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for (x, y), c in px.items():
        img.putpixel((x, y), colours[c] + (255,))
    return img


def main():
    mesh = build()
    for folder in ('models/entity', 'textures/entity', 'textures/item'):
        os.makedirs(os.path.join(OUT, folder), exist_ok=True)
    write_mesh(mesh, os.path.join(OUT, 'models/entity/broom.bin'))
    texture = build_texture()
    texture.save(os.path.join(OUT, 'textures/entity/broom.png'), optimize=True)
    icon().save(os.path.join(OUT, 'textures/item/broom.png'))
    if '--obj' in sys.argv:
        folder = sys.argv[sys.argv.index('--obj') + 1]
        os.makedirs(folder, exist_ok=True)
        write_obj(mesh, folder)
        texture.save(os.path.join(folder, 'broom.png'))
    print({k: len(v) for k, v in mesh.groups.items()})


if __name__ == '__main__':
    main()
