"""Generates the tileable 3D noise the MinaRealism clouds are carved from.

Writes src/client/resources/shaderpacks/MinaRealism/shaders/tex/cloud_noise.png:
two volumes laid out as grids of slices, each slice with a one-texel border
copied from the opposite edge so bilinear lookups wrap correctly.

  shape  (left, 64^3):  r = Perlin-Worley, g/b/a = Worley fbm at rising frequency
  detail (right, 32^3): r/g/b = Worley fbm at rising frequency, a = unused (1)

Usage: python3 tools/generate_cloud_noise.py   (needs numpy and Pillow)
"""
import os

import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), '..', 'src/client/resources/shaderpacks/MinaRealism/shaders/tex/cloud_noise.png')
RNG = np.random.default_rng(20260924)


def grid(n, period):
    """Voxel centres of an n^3 volume in units where the volume spans `period`."""
    c = (np.arange(n) + 0.5) / n * period
    return np.meshgrid(c, c, c, indexing='ij')


def worley(n, cells):
    """Inverted, tileable cellular noise: 1 at feature points, 0 far from them."""
    points = RNG.random((cells, cells, cells, 3))
    x, y, z = grid(n, cells)
    ix, iy, iz = np.floor(x).astype(int), np.floor(y).astype(int), np.floor(z).astype(int)
    nearest = np.full(x.shape, np.inf)
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for dz in (-1, 0, 1):
                cx, cy, cz = ix + dx, iy + dy, iz + dz
                p = points[cx % cells, cy % cells, cz % cells]
                d2 = (cx + p[..., 0] - x) ** 2 + (cy + p[..., 1] - y) ** 2 + (cz + p[..., 2] - z) ** 2
                nearest = np.minimum(nearest, d2)
    return np.clip(1.0 - np.sqrt(nearest), 0.0, 1.0)


def perlin(n, period):
    """Tileable gradient noise in about [-1, 1]."""
    g = RNG.normal(size=(period, period, period, 3))
    g /= np.linalg.norm(g, axis=-1, keepdims=True)
    x, y, z = grid(n, period)
    i = [np.floor(a).astype(int) for a in (x, y, z)]
    f = [a - b for a, b in zip((x, y, z), i)]
    fade = [t * t * t * (t * (t * 6 - 15) + 10) for t in f]
    total = 0.0
    for cx in (0, 1):
        for cy in (0, 1):
            for cz in (0, 1):
                grad = g[(i[0] + cx) % period, (i[1] + cy) % period, (i[2] + cz) % period]
                dot = grad[..., 0] * (f[0] - cx) + grad[..., 1] * (f[1] - cy) + grad[..., 2] * (f[2] - cz)
                w = (fade[0] if cx else 1 - fade[0]) * (fade[1] if cy else 1 - fade[1]) * (fade[2] if cz else 1 - fade[2])
                total = total + w * dot
    return total * 1.4


def worley_fbm(n, cells):
    return worley(n, cells) * 0.625 + worley(n, cells * 2) * 0.25 + worley(n, cells * 4) * 0.125


def remap(x, a, b, c, d):
    return c + (x - a) / (b - a) * (d - c)


def normalize(v):
    lo, hi = np.percentile(v, 0.5), np.percentile(v, 99.5)
    return np.clip((v - lo) / (hi - lo), 0.0, 1.0)


def shape_volume(n=64):
    # Only low frequencies: the bodies of clouds, not their fine structure,
    # which the detail volume adds.
    p = perlin(n, 4) * 0.65 + perlin(n, 8) * 0.35
    p = np.clip(p * 0.5 + 0.5, 0.0, 1.0)
    w = worley(n, 4) * 0.7 + worley(n, 8) * 0.3
    # Perlin dilated by Worley: billowy cells with connected, irregular shapes.
    perlin_worley = normalize(remap(p, -(1.0 - w), 1.0, 0.0, 1.0))
    return np.stack([perlin_worley, normalize(worley_fbm(n, 2)), normalize(worley_fbm(n, 4)), normalize(worley_fbm(n, 8))], axis=-1)


def detail_volume(n=32):
    ones = np.ones((n, n, n))
    return np.stack([normalize(worley_fbm(n, 2)), normalize(worley_fbm(n, 4)), normalize(worley_fbm(n, 8)), ones], axis=-1)


def atlas(volume, columns):
    """Slices along the volume's second axis (height), each with a wrapped border."""
    n = volume.shape[0]
    rows = (n + columns - 1) // columns
    tile = n + 2
    out = np.zeros((rows * tile, columns * tile, 4))
    for s in range(n):
        slice_ = volume[:, s, :, :]                     # x, z
        padded = np.pad(slice_, ((1, 1), (1, 1), (0, 0)), mode='wrap')
        r, c = divmod(s, columns)
        # Image rows follow z, columns follow x.
        out[r * tile:(r + 1) * tile, c * tile:(c + 1) * tile] = padded.transpose(1, 0, 2)
    return out


def main():
    shape = atlas(shape_volume(), 8)      # 8 x 8 tiles of 66 = 528 x 528
    detail = atlas(detail_volume(), 8)    # 8 x 4 tiles of 34 = 272 x 136
    image = np.zeros((shape.shape[0], shape.shape[1] + detail.shape[1], 4))
    image[:, :shape.shape[1]] = shape
    image[:detail.shape[0], shape.shape[1]:] = detail
    Image.fromarray((image * 255.0 + 0.5).astype(np.uint8), 'RGBA').save(OUT, optimize=True)
    print('wrote', os.path.normpath(OUT), image.shape[1], 'x', image.shape[0])


main()
