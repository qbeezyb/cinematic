package me.cinematic.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.cinematic.Cinematic;
import me.cinematic.network.Payloads;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Server-wide selection with bounded pages and request-scoped responses. */
public final class ScreenPickerScreen extends Screen {
    private final UUID previousScreen;
    private final List<Button> rows = new ArrayList<>();
    private Payloads.ScreenList result;
    private UUID selecting;
    private long listRequest;
    private long selectRequest;
    private long requestedAt;
    private long queryChanged;
    private boolean loading;
    private String query = "";
    private String message;
    private int offset;
    private int rowCount;
    private int left;
    private int top;
    private int panelWidth;
    private Button previous;
    private Button next;
    private Button retry;

    public ScreenPickerScreen(UUID previousScreen) { this(previousScreen, ""); }
    public ScreenPickerScreen(UUID previousScreen, String message) {
        super(Component.literal("Switch screen")); this.previousScreen = previousScreen; this.message = message;
    }

    private boolean holdingRemote() {
        var player = minecraft.player;
        return player != null && (player.getMainHandItem().is(Cinematic.REMOTE.get()) || player.getOffhandItem().is(Cinematic.REMOTE.get()));
    }

    private Button button(String label, int x, int y, int w, Runnable action, String help) {
        return addRenderableWidget(Button.builder(Component.literal(label), pressed -> action.run()).bounds(x, y, w, 20)
                .tooltip(Tooltip.create(Component.literal(help))).build());
    }

    @Override protected void init() {
        rows.clear();
        panelWidth = Math.min(420, width - 24); left = (width - panelWidth) / 2; top = Math.max(6, (height - 230) / 2);
        var search = addRenderableWidget(new EditBox(font, panelWidth, 20, Component.literal("Search screens")));
        search.setX(left); search.setY(top + 24); search.setMaxLength(64); search.setValue(query);
        search.setTooltip(Tooltip.create(Component.literal("Search dimension, coordinates, screen size, or media filename")));
        search.setResponder(value -> {
            query = value; offset = 0; queryChanged = ClientPlayback.now(); result = null;
            listRequest = RemoteTarget.nextRequest(); selecting = null; loading = true; requestedAt = queryChanged;
            rows.forEach(row -> row.visible = false);
        });
        rowCount = Math.max(1, Math.min(5, (height - top - 112) / 22));
        for (int i = 0; i < rowCount; i++) {
            final int index = i;
            var row = button("", left, top + 52 + i * 22, panelWidth, () -> select(index), "Control this screen");
            row.visible = false; rows.add(row);
        }
        int bottom = top + 68 + rowCount * 22;
        previous = button("Previous", left, bottom, 76, () -> { offset = Math.max(0, offset - rowCount); request(); }, "Previous screens");
        retry = button("Retry", left + (panelWidth - 76) / 2, bottom, 76, this::request, "Reload screens");
        next = button("Next", left + panelWidth - 76, bottom, 76, () -> { offset += rowCount; request(); }, "Next screens");
        button(previousScreen == null ? "Done" : "Back", left + (panelWidth - 100) / 2, bottom + 24, 100, this::onClose, "Return to previous controls or the game");
        setInitialFocus(search);
        request();
    }

    private void request() {
        if (!holdingRemote()) { loading = false; return; }
        listRequest = RemoteTarget.nextRequest(); loading = true; selecting = null; result = null;
        requestedAt = ClientPlayback.now(); queryChanged = 0;
        rows.forEach(row -> row.visible = false);
        ClientPacketDistributor.sendToServer(new Payloads.Request(Payloads.RequestKind.SCREENS, Payloads.NO_SCREEN, offset, listRequest, query));
    }

    public void screens(Payloads.ScreenList packet) {
        if (!holdingRemote() || packet.requestId() != listRequest || packet.offset() != offset || selecting != null) { return; }
        result = packet; loading = false; message = packet.status();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            row.visible = i < packet.screens().size();
            if (row.visible) {
                var s = packet.screens().get(i);
                String label = details(s, " · ").replace("minecraft:", "");
                row.setMessage(Component.literal(font.plainSubstrByWidth(label, panelWidth - 16)));
                row.setTooltip(Tooltip.create(Component.literal(details(s, "\n") + "\nMedia ID: " + s.mediaId() + "\nScreen ID: " + s.id())));
            }
        }
    }

    private static String details(Payloads.Snapshot s, String separator) {
        return s.key().dimension() + separator + s.key().x() + ", " + s.key().y() + ", " + s.key().z()
                + separator + s.width() + " × " + s.height() + separator + (s.name().isEmpty() ? s.mediaId().isEmpty() ? "No media selected" : "Unavailable media" : s.name());
    }

    private void select(int index) {
        if (!holdingRemote() || loading || selecting != null || result == null || index >= result.screens().size()) { return; }
        selecting = result.screens().get(index).id(); selectRequest = RemoteTarget.nextRequest(); requestedAt = ClientPlayback.now();
        ClientPacketDistributor.sendToServer(new Payloads.Request(Payloads.RequestKind.TARGET, selecting, 0, selectRequest, ""));
    }

    public void selected(Payloads.ScreenState packet) {
        if (!holdingRemote() || selecting == null || !selecting.equals(packet.screen()) || packet.requestId() != selectRequest) { return; }
        selecting = null;
        if (packet.state() == null) { request(); message = packet.status(); return; }
        CinematicClient.PLAYBACK.remote.open(packet.state());
        minecraft.gui.setScreen(new CinemaScreen(packet.screen()));
    }

    @Override public void tick() {
        long now = ClientPlayback.now();
        if (!holdingRemote()) {
            listRequest = RemoteTarget.nextRequest(); selecting = null; loading = false; queryChanged = 0;
        }
        if (queryChanged > 0 && now - queryChanged > 300) { request(); }
        if ((loading || selecting != null) && now - requestedAt > 10_000) {
            loading = false; selecting = null; listRequest = RemoteTarget.nextRequest();
            message = "Screens did not respond. Choose Retry.";
        }
        boolean enabled = holdingRemote() && !loading && selecting == null;
        previous.active = enabled && offset > 0;
        next.active = enabled && result != null && offset + rowCount < result.total();
        retry.active = holdingRemote() && selecting == null;
        rows.forEach(row -> row.active = enabled);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        extractBackground(graphics, mouseX, mouseY, delta);
        graphics.fill(left - 8, top - 4, left + panelWidth + 8, top + 226, 0xF0212121);
        graphics.outline(left - 8, top - 4, panelWidth + 16, 230, 0xFF777777);
        graphics.text(font, title, left, top + 6, 0xFFFFFFFF);
        String status = !holdingRemote() ? "Hold a Remote to choose a screen" : selecting != null ? "Opening screen…" : loading ? "Loading screens…"
                : !message.isEmpty() ? message : result == null || result.total() == 0 ? "No screens found" : (offset + 1) + "–" + Math.min(offset + rowCount, result.total()) + " of " + result.total();
        graphics.text(font, font.plainSubstrByWidth(status, panelWidth), left, top + 54 + rowCount * 22, 0xFFFFD18A);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override public void onClose() {
        minecraft.gui.setScreen(previousScreen == null ? null : new CinemaScreen(previousScreen));
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
}
