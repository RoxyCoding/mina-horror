package chihalu.mina.horror.client.shader;

import chihalu.mina.horror.MinaHorror;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Copies the bundled Iris shader pack into the game's shaderpacks folder so it
 * can be picked from Iris' shader pack screen. Iris cannot read packs from
 * inside a mod jar.
 */
public final class BundledShaderPack {
	public static final String NAME = "MinaRealism";
	private static final String MARKER = ".mina-horror-pack";

	private BundledShaderPack() {}

	public static void install() {
		FabricLoader loader = FabricLoader.getInstance();
		if (!loader.isModLoaded("iris")) return;
		Optional<Path> source = loader.getModContainer(MinaHorror.MOD_ID).flatMap(mod -> mod.findPath("shaderpacks/" + NAME));
		if (source.isEmpty()) {
			MinaHorror.LOGGER.warn("Bundled shader pack {} is missing from the mod", NAME);
			return;
		}
		Path target = loader.getGameDir().resolve("shaderpacks").resolve(NAME);
		try {
			if (install(source.get(), target)) MinaHorror.LOGGER.info("Installed shader pack {} to {}", NAME, target);
		} catch (IOException e) {
			MinaHorror.LOGGER.warn("Could not install shader pack {}", NAME, e);
		}
	}

	/**
	 * Installs or refreshes the pack. A folder without our marker belongs to the
	 * player and is never touched. Returns true when files were written.
	 */
	public static boolean install(Path source, Path target) throws IOException {
		String fingerprint = fingerprint(source);
		Path marker = target.resolve(MARKER);
		if (Files.exists(target)) {
			if (!Files.isRegularFile(marker)) return false;
			if (Files.readString(marker, StandardCharsets.UTF_8).equals(fingerprint)) return false;
			deleteTree(target);
		}
		for (Path file : files(source)) {
			Path destination = target.resolve(source.relativize(file).toString());
			Files.createDirectories(destination.getParent());
			Files.copy(file, destination);
		}
		Files.writeString(marker, fingerprint, StandardCharsets.UTF_8);
		return true;
	}

	private static String fingerprint(Path source) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (Path file : files(source)) {
				digest.update(source.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
				digest.update((byte) 0);
				digest.update(Files.readAllBytes(file));
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static List<Path> files(Path root) throws IOException {
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList();
		}
	}

	private static void deleteTree(Path root) throws IOException {
		List<Path> paths;
		try (Stream<Path> walk = Files.walk(root)) {
			paths = walk.sorted(Comparator.reverseOrder()).toList();
		}
		for (Path path : paths) Files.delete(path);
	}
}
