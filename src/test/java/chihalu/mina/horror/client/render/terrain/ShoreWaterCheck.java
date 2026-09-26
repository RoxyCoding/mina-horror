package chihalu.mina.horror.client.render.terrain;

public final class ShoreWaterCheck {
	public static void main(String[] args) throws Exception {
		for (int detail : new int[]{2,4,8}) {
			chihalu.mina.horror.terrain.TerrainDetail.renderCells=detail;
			checkTerrainSurface();
		}
		chihalu.mina.horror.terrain.TerrainDetail.renderCells=4;
		checkMaterialPalette();
		checkTerrainLight();
		checkIrisVertexContract();
		if (ShoreWaterShaders.begin(new Object(), null, null)) throw new AssertionError("Non-Iris consumer must not acquire shader context");
		float water = 8f / 9f - 0.001f;
		if (ShoreWater.reaches(new float[] {0, 0, 0, 0}, water)) throw new AssertionError("Dry flat shore flooded");
		if (!ShoreWater.reaches(new float[] {0, -.5f, 0, -.5f}, water)) throw new AssertionError("Sloping shore gap left open");
		if (!ShoreWater.reaches(new float[] {0, 0, 0, -.25f}, water)) throw new AssertionError("Diagonal shore missed");
		if (ShoreWater.reaches(new float[] {0, -.05f, 0, -.05f}, water)) throw new AssertionError("Shore above water flooded");
		System.out.println("Shore water height checks passed");
	}

	private static void checkTerrainLight() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		Class<?> type = net.minecraft.client.renderer.block.BlockAndTintGetter.class;
		var level = (net.minecraft.client.renderer.block.BlockAndTintGetter) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
			if(method.getName().equals("getBlockState")) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
			if(method.getName().equals("getBrightness")) {
				if(args[0] == net.minecraft.world.level.LightLayer.SKY) return 15;
				return ((net.minecraft.core.BlockPos)args[1]).getX() < 16 ? 4 : 12;
			}
			throw new AssertionError(method);
		});
		int left=TerrainLight.at(level,15.999,64,0)&65535;
		int right=TerrainLight.at(level,16.001,64,0)&65535;
		if(Math.abs(left-right)>1) throw new AssertionError("Light jumps at chunk boundary");
		int middle=TerrainLight.at(level,16,64,0);
		if((middle&65535)!=128 || (middle>>>16)!=240) throw new AssertionError("Incorrect light interpolation");
		System.out.println("Terrain light interpolation and chunk-boundary continuity checks passed");
	}

	private static void checkTerrainSurface() {
		Class<?> view = net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView.class;
		var source = (net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView) java.lang.reflect.Proxy.newProxyInstance(view.getClassLoader(), new Class<?>[] {view}, (proxy, method, values) -> {
			int i = values != null && values.length > 0 ? (int) values[0] : 0;
			return switch (method.getName()) {
				case "x", "u" -> i >= 2 ? 1f : 0f;
				case "z", "v" -> i == 1 || i == 2 ? 1f : 0f;
				case "y" -> i == 2 ? 1f : 0f;
				case "color" -> -1;
				case "tag" -> 0;
				case "hasNormal" -> false;
				default -> throw new AssertionError(method);
			};
		});
		int[] counts = new int[2];
		Class<?> emitter = net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter.class;
		var output = (net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter) java.lang.reflect.Proxy.newProxyInstance(emitter.getClassLoader(), new Class<?>[] {emitter}, (proxy, method, values) -> {
			if (method.getName().equals("pos")) {
				float x = (float) values[1], y = (float) values[2], z = (float) values[3];
				if (Math.abs(y-x*z) > 0.00001f) throw new AssertionError("Vertex left bilinear surface");
				counts[0]++;
			}
			if (method.getName().equals("emit")) counts[1]++;
			return proxy;
		});
		TerrainSurface.emit(source, output);
		int cells=chihalu.mina.horror.terrain.TerrainDetail.renderCells;
		if (counts[0] != cells*cells*4 || counts[1] != cells*cells) throw new AssertionError("Wrong surface subdivision count");
		System.out.println("Terrain subdivision: " + cells*cells + " quads follow the bilinear surface");
	}

	private static void checkMaterialPalette() {
		for (int a=0; a<8; a++) for (int b=0; b<8; b++) for (int c=0; c<8; c++) {
			float[] wa=new float[8], wb=new float[8], wc=new float[8];
			wa[a]=1; wb[b]=1; wc[c]=1;
			int pa=TerrainMaterials.pack(wa), pb=TerrainMaterials.pack(wb), pc=TerrainMaterials.pack(wc);
			for (int i=0; i<=8; i++) {
				float t=i/8f;
				int left=TerrainMaterials.blend(pa,pb,pc,pa,1,t);
				int right=TerrainMaterials.blend(pa,pc,pb,pc,0,t);
				if (left!=right) throw new AssertionError("Shared material edge differs");
				int mixed=TerrainMaterials.blend(pa,pb,pc,pa,t,.5f);
				for(int m=0;m<8;m++) if(m!=a && m!=b && m!=c && TerrainMaterials.weight(mixed,m)!=0)
					throw new AssertionError("Interpolation invented material "+m);
			}
		}
		System.out.println("512 material combinations: shared edges agree and no phantom materials");
	}

	private static void checkIrisVertexContract() throws Exception {
		var jar = java.nio.file.Path.of("run/mods/iris-fabric-1.11.6+mc26.3.jar");
		if (!java.nio.file.Files.exists(jar)) throw new AssertionError("Iris integration check requires " + jar);
		try (var loader = new java.net.URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
			var extension = loader.loadClass("net.irisshaders.iris.vertices.sodium.terrain.ChunkVertexExtension");
			var data = extension.getMethod("iris$setData", byte.class, byte.class, int.class, int.class, int.class, int.class);
			Object[][] captured = new Object[4][];
			Object[] vertices = new Object[4];
			for (int i = 0; i < 4; i++) {
				final int corner = i;
				vertices[i] = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {extension}, (proxy, method, values) -> {
					if (!method.getName().equals("iris$setData")) throw new AssertionError(method);
					captured[corner] = values;
					return null;
				});
			}
			ShoreWaterShaders.write(vertices, data, (byte) 0, (byte) 1, 17, 23000, 62, 23184);
			for (Object[] values : captured) {
				if (!java.util.Arrays.equals(values, new Object[] {(byte) 0, (byte) 1, 17, 23000, 62, 23184})) throw new AssertionError("Water metadata missing");
			}
			ShoreWaterShaders.write(vertices, data, (byte) 0, (byte) 0, -1, 0, 0, 0);
			for (Object[] values : captured) if (!values[2].equals(-1)) throw new AssertionError("Water metadata leaked");
			System.out.println("Installed Iris vertex contract checks passed (four corners and reset)");
		}
	}
}
