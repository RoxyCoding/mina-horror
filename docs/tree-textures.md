# 木の素材

組み込み `imagegen` ツールでこのプロジェクト用に生成した素材です。外部の写真素材や有料APIは使用していません。

- 保存先: `src/main/resources/assets/mina-horror/textures/block/tree_atlas.png`
- 実際の画像: 1024×1536、RGBA、4列×6行、1区画256×256ピクセル。
- 上半分: 樹皮11種と切断面。下半分: 葉・針葉・花・菌糸の透過素材12区画。
- PNGは生成結果のまま保存。上半分を不透明、下半分をカットアウトとして描画し、区画ごとのUVを設定します。

## 使用したプロンプト

追加素材 `src/main/resources/assets/mina-horror/textures/tree/poplar_atlas.png` は、ポプラの樹皮と黄・橙・赤の葉を収録した1254×1254、2×2のRGBAアトラスです。Minecraft標準の `unstitch` で各区画の内側624×624を読み込み、元画像を変更せずに4段階のミップマップに対応させています。

### ポプラ用プロンプト

Photorealistic game texture atlas exactly 2 columns and 2 rows of equal square tiles, total 1024x1024, no padding or lines between tiles. No labels or text. TOP LEFT: opaque edge-to-edge tileable macro texture of natural mature poplar bark, grey beige slightly greenish, vertical deep fissures with realistic irregular scales, flat diffuse lighting, seamless edges. TOP RIGHT: a delicate poplar twig with 8 individual triangular to heart shaped serrated golden yellow autumn leaves, detailed veins, photographic natural leaves, plenty of actual alpha transparent background around and between leaves, contained inside its 512x512 quadrant with 24 pixel clear margins. BOTTOM LEFT: same sort of natural poplar twig spray with 8 orange russet autumn leaves on genuine transparent background, fine branching visible, contained inside this quadrant with clear margins. BOTTOM RIGHT: same poplar twig spray with 8 deep red crimson autumn leaves, photographic veins and natural color variation, genuine alpha transparent background, contained entirely inside this quadrant. All leaf clusters evenly sized and naturally arranged, no fake checkerboard, no solid background, no shadows on backgrounds, no overlapping quadrant boundaries. Flat botanical photography for use on 3D foliage cards. Only bark tile is opaque. No stylization, no pixel art, no illustration.

### 基本樹種用プロンプト

Create a production photorealistic game texture atlas, exactly 4 equal columns by 6 equal rows, 24 square cells, portrait 2048x3072 or closest supported 2:3 high resolution. No gaps, borders, labels, letters, numbers, watermark, or perspective. Flat diffuse lighting; close realistic natural surface detail. First 3 rows are entirely opaque seamless square bark texture swatches with vertical grain. Row1 left to right: deeply fissured grey brown oak bark; flaky warm dark spruce bark; white birch bark with black horizontal scars; grey tan tropical jungle tree bark with subtle lichens. Row2: reddish grey acacia bark; very dark brown old oak bark; reddish brown mangrove bark; cherry dark purple brown bark with horizontal lenticels. Row3: pale grey weathered oak bark; realistic burgundy crimson fungal stem fibers; realistic blue teal warped fungal stem fibers; natural tan endgrain with subtle concentric annual rings. Last 3 rows contain isolated botanical small twig sprays centered INSIDE each respective square cell, with actual transparent background around foliage, generous empty margin, no clipping or cross-cell overlap, sharp natural silhouettes and visible leaf veins, each twig with 7-15 individual separated leaves and plenty of transparency between them, not a filled rectangle. Row4: lobed grey-green oak leaves spray; spruce needle branch; triangular serrated birch leaves spray; broad glossy elliptical jungle leaves spray. Row5: acacia fine pinnate leaf spray; dark green lobed oak spray; thick elliptical mangrove leaves spray; realistic pale pink cherry blossoms and small green leaves spray. Row6: silvery pale oak leaves spray; oval azalea leaves spray; purple-pink azalea flowers with leaves spray; small cluster of realistic crimson and teal fungal fronds. This atlas is applied directly to 3D trees: bark tiles need edge-to-edge detail, foliage tiles need true alpha cutout background. Photographic botanical realism, no pixel art, no Minecraft cubes, no stylization.
