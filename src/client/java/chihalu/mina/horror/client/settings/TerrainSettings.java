package chihalu.mina.horror.client.settings;

import chihalu.mina.horror.MinaHorror;
import chihalu.mina.horror.terrain.TerrainDetail;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;

public final class TerrainSettings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mina-horror-terrain.properties");
    public static int collisionPreference = 16;
    private TerrainSettings() { }

    public static void register() {
        if (Files.exists(FILE)) {
            try (var reader=Files.newBufferedReader(FILE)) {
                Properties p=new Properties(); p.load(reader);
                TerrainDetail.renderCells=valid(p.getProperty("renderCells"),4,2,4,8);
                collisionPreference=valid(p.getProperty("collisionCells"),16,4,8,16);
            } catch (IOException | IllegalArgumentException e) {
                MinaHorror.LOGGER.warn("Could not read terrain settings; keeping defaults",e);
            }
        }
        ClientTickEvents.START_CLIENT_TICK.register(client ->
            TerrainDetail.collisionCells=client.hasSingleplayerServer() && !client.getSingleplayerServer().isPublished()?collisionPreference:16);
        ScreenEvents.AFTER_INIT.register((client,screen,width,height) -> {
            if(screen instanceof OptionsScreen) Screens.getWidgets(screen).add(Button.builder(
                Component.translatable("mina.terrain.title"),button -> client.gui.setScreen(new TerrainSettingsScreen(screen)))
                .bounds(width-88,4,80,20).build());
        });
    }

    static int valid(String text,int fallback,int... allowed) {
        try { int n=Integer.parseInt(text); for(int value:allowed) if(n==value) return n; }
        catch(NumberFormatException ignored) { }
        return fallback;
    }

    public static void save(int render,int collision) throws IOException {
        if(valid(""+render,-1,2,4,8)<0 || valid(""+collision,-1,4,8,16)<0)
            throw new IllegalArgumentException("Unsupported terrain subdivision");
        Properties p=new Properties();
        p.setProperty("renderCells",""+render); p.setProperty("collisionCells",""+collision);
        Files.createDirectories(FILE.getParent());
        Path temp=Files.createTempFile(FILE.getParent(),"mina-terrain-",".tmp");
        try {
            try(var writer=Files.newBufferedWriter(temp)) { p.store(writer,"Mina Horror terrain detail"); }
            Files.move(temp,FILE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
        TerrainDetail.renderCells=render;
        collisionPreference=collision;
    }
}
