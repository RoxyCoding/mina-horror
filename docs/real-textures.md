# 写真の地面素材

MinaRealism の「写真の地面」は、[Poly Haven](https://polyhaven.com) の CC0（パブリックドメイン）素材を使っています。
`python3 tools/generate_real_textures.py` がダウンロードして、シェーダーパックの `tex/real_albedo.png`（色）と `tex/real_surface.png`（法線と粗さ）にまとめます。

| 番号 | 使う所 | 素材 | 実物の大きさ |
|---|---|---|---|
| 0 | 草ブロックの上面・側面のふち | [forrest_ground_01](https://polyhaven.com/a/forrest_ground_01) | 2.0 m |
| 1 | 土・根付いた土・ポドゾル・土の道・草ブロックの側面 | [forest_ground_04](https://polyhaven.com/a/forest_ground_04) | 3.15 m |
| 2 | 石・安山岩（灰色に調整） | [rock_boulder_dry](https://polyhaven.com/a/rock_boulder_dry) | 1.8 m |
| 3 | 砂・怪しげな砂 | [sand_01](https://polyhaven.com/a/sand_01) | 1.5 m |
| 4 | 砂利・怪しげな砂利 | [gravel_floor_02](https://polyhaven.com/a/gravel_floor_02) | 2.0 m |
| 5 | 雪ブロック・雪・粉雪・雪の積もった草ブロックのふち | [snow_02](https://polyhaven.com/a/snow_02) | 2.0 m |
| 6 | 深層岩（暗い灰色に調整） | [rock_face_04](https://polyhaven.com/a/rock_face_04) | 2.0 m |
| 7 | 泥・粗い土 | [brown_mud_02](https://polyhaven.com/a/brown_mud_02) | 1.3 m |

アトラスは素材ごとにミップマップ（0〜6段）を並べ、各段の縁に反対側の1テクセルを写してあります。配置は `lib/realtex.glsl` の定数と対応しています。
