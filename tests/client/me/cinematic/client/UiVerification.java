package me.cinematic.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import me.cinematic.Cinematic;
import me.cinematic.core.ScreenKey;
import me.cinematic.network.Payloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.gui.widget.ExtendedSlider;

/** Opt-in screenshots of production widgets with controlled metadata; no world or server required. */
@Mod(value = Cinematic.ID, dist = Dist.CLIENT)
public final class UiVerification {
    private static final UUID SCREEN = UUID.fromString("a3ce3b54-5984-4432-a979-a0b8f168a93c");
    private static final String[] CASES = {"scale-1", "scale-2", "minimum-gui", "keyboard-seek", "drag-seek"};
    private int fixture = -1;
    private int ticks;

    public UiVerification(IEventBus bus) { NeoForge.EVENT_BUS.addListener(this::tick); }

    private static Object field(Object object, String name) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private void open(Minecraft minecraft) {
        minecraft.options.guiScale().set(fixture < 2 ? fixture + 1 : 0);
        minecraft.resizeGui();
        var state = new Payloads.Snapshot(SCREEN, new ScreenKey("minecraft:the_nether", 9000, 80, -7000),
                1, 16, 9, "a".repeat(32), "Hour-long movie.mkv", 7_200_000, 1920, 1080, true, 4,
                3_665_000, 0, 1000, false, false, 9, 64, "");
        CinematicClient.PLAYBACK.remote.open(state);
        CinematicClient.PLAYBACK.snapshots(new Payloads.Snapshots(List.of()));
        if (CinematicClient.PLAYBACK.get(SCREEN) != null || CinematicClient.PLAYBACK.controlState(SCREEN) == null) {
            throw new IllegalStateException("Distant UI fixture created a playback entry or lost metadata");
        }
        minecraft.gui.setScreen(new CinemaScreen(SCREEN));
        var screen = minecraft.gui.screen();
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget widget && (widget.getX() < 0 || widget.getY() < 0
                    || widget.getX() + widget.getWidth() > screen.width || widget.getY() + widget.getHeight() > screen.height)) {
                throw new IllegalStateException("Widget outside " + screen.width + "×" + screen.height + ": " + widget.getMessage().getString());
            }
        }
        Cinematic.LOG.info("UI verification {} at {}×{}, scale {}", CASES[fixture], screen.width, screen.height, minecraft.getWindow().getGuiScale());
    }

    private void preview(CinemaScreen screen) {
        var slider = (ExtendedSlider) field(screen, "timeline");
        if (!slider.getMessage().getString().isEmpty()) { throw new IllegalStateException("Seek text overlays the slider"); }
        if (fixture == 3) {
            slider.active = true;
            slider.setValue(3_665_000);
            slider.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, InputConstants.KEYCODE_RIGHT, 0));
            if (slider.getValueLong() != 3_666_000 || (long) field(screen, "seekValue") != 3_666_000) {
                throw new IllegalStateException("Keyboard seek must advance exactly one second and preview it");
            }
        } else if (fixture == 4) {
            slider.active = true;
            slider.setValue(3_665_000);
            slider.onClick(new MouseButtonEvent(slider.getX() + 4 + (slider.getWidth() - 8) * .75, slider.getY() + 10,
                    new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
            if (slider.getValueLong() != 5_400_000 || (long) field(screen, "seekValue") != 5_400_000) {
                throw new IllegalStateException("Dragging must preview the proposed position");
            }
        }
    }

    private void tick(ClientTickEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        if (fixture < 0) {
            if (minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) {
                minecraft.options.onboardingAccessibilityFinished();
                minecraft.gui.setScreen(new TitleScreen());
            }
            if (!(minecraft.gui.screen() instanceof TitleScreen) || ++ticks < 60) { return; }
            minecraft.getWindow().setWindowed(640, 480);
            fixture = 0; ticks = 0; open(minecraft);
        }
        if (++ticks > 25) {
            if (++fixture == CASES.length) {
                Cinematic.LOG.info("UI verification complete: all widget bounds, empty slider text, seek previews and metadata isolation passed");
                minecraft.stop(); return;
            }
            ticks = 0; open(minecraft);
        }
        if (minecraft.gui.screen() instanceof CinemaScreen screen) { preview(screen); }
        if (ticks == 20) {
            Screenshot.grab(minecraft.gameDirectory, "remote-" + CASES[fixture] + ".png", minecraft.gameRenderer.mainRenderTarget(), 1,
                    message -> Cinematic.LOG.info("UI screenshot: {}", message.getString()));
        }
    }
}
