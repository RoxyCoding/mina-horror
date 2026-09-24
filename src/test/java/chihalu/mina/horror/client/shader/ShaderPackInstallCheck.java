package chihalu.mina.horror.client.shader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Run with gradlew checkShaderPackInstall. No game window or test framework required. */
public final class ShaderPackInstallCheck {
	public static void main(String[] args) throws Exception {
		Path source = Path.of("src/client/resources/shaderpacks", BundledShaderPack.NAME);
		require(Files.isRegularFile(source.resolve("shaders/shaders.properties")), "Bundled pack is missing");
		Path temp = Files.createTempDirectory("mina-shaderpack");
		try {
			Path target = temp.resolve(BundledShaderPack.NAME);
			require(BundledShaderPack.install(source, target), "Fresh install should copy the pack");
			require(Files.isRegularFile(target.resolve("shaders/final.fsh")), "Programs were not copied");
			require(Files.isRegularFile(target.resolve("shaders/lib/atmosphere.glsl")), "Nested files were not copied");
			require(!BundledShaderPack.install(source, target), "Unchanged pack should not be rewritten");

			// An older copy we installed is replaced, and files that no longer exist go away.
			Files.writeString(target.resolve(".mina-horror-pack"), "old");
			Files.writeString(target.resolve("shaders/stale.fsh"), "stale");
			require(BundledShaderPack.install(source, target), "Outdated pack should be refreshed");
			require(!Files.exists(target.resolve("shaders/stale.fsh")), "Stale file survived a refresh");

			// A folder the player made with the same name is left alone.
			Path playerPack = temp.resolve("player").resolve(BundledShaderPack.NAME);
			Files.createDirectories(playerPack);
			Files.writeString(playerPack.resolve("mine.txt"), "keep");
			require(!BundledShaderPack.install(source, playerPack), "Player folder must not be overwritten");
			require(Files.readString(playerPack.resolve("mine.txt")).equals("keep"), "Player file changed");
		} finally {
			try (Stream<Path> walk = Files.walk(temp)) {
				for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
			}
		}
		System.out.println("Shader pack install checks passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
