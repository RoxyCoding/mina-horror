package chihalu.mina.horror.client.settings;

import chihalu.mina.horror.MinaHorror;
import chihalu.mina.horror.terrain.TerrainDetail;
import java.io.IOException;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class TerrainSettingsScreen extends Screen {
    private final Screen parent;
    private int render=TerrainDetail.renderCells, collision=TerrainSettings.collisionPreference;
    public TerrainSettingsScreen(Screen parent) {
        super(Component.translatable("mina.terrain.title")); this.parent=parent;
    }
    @Override protected void init() {
        int x=width/2-150, y=Math.max(35,height/2-65);
        addRenderableWidget(new StringWidget(width/2-150,12,300,20,title,font));
        addRenderableWidget(CycleButton.<Integer>builder(n -> Component.literal(n+" × "+n),render).withValues(2,4,8)
            .create(x,y,300,20,Component.translatable("mina.terrain.render"),(button,value)->render=value));
        addRenderableWidget(new StringWidget(x,y+24,300,20,Component.translatable("mina.terrain.render_hint"),font));
        var physics=addRenderableWidget(CycleButton.<Integer>builder(n -> Component.literal(n+" × "+n),collision).withValues(4,8,16)
            .create(x,y+50,300,20,Component.translatable("mina.terrain.collision"),(button,value)->collision=value));
        physics.active=minecraft.level==null || minecraft.hasSingleplayerServer() && !minecraft.getSingleplayerServer().isPublished();
        physics.setTooltip(Tooltip.create(Component.translatable("mina.terrain.collision_hint")));
        addRenderableWidget(new StringWidget(x,y+74,300,20,Component.translatable("mina.terrain.collision_hint"),font));
        addRenderableWidget(Button.builder(Component.translatable("mina.terrain.apply"),button -> {
            try {
                boolean redraw=render!=TerrainDetail.renderCells;
                TerrainSettings.save(render,collision);
                if(redraw && minecraft.level!=null) minecraft.levelExtractor.allChanged();
                onClose();
            } catch(IOException e) {
                MinaHorror.LOGGER.error("Could not save terrain settings",e);
                button.setMessage(Component.translatable("mina.terrain.save_failed"));
            }
        }).bounds(x,height-28,146,20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),button -> onClose()).bounds(x+154,height-28,146,20).build());
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
