package me.cinematic.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.cinematic.Cinematic;
import me.cinematic.core.PanelGeometry;
import me.cinematic.network.Payloads;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.gui.widget.ExtendedSlider;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Vanilla widgets provide keyboard focus, narration, pressed/disabled states and tooltips. */
public final class CinemaScreen extends Screen {
    private enum Page { PLAYER, LIBRARY, AUDIO }
    private final UUID id;
    private Page page = Page.PLAYER;
    private final List<Button> controls = new ArrayList<>();
    private final List<Button> mediaRows = new ArrayList<>();
    private ExtendedSlider timeline;
    private Button play;
    private Button audioButton;
    private Payloads.AudioTracks tracks;
    private String audioMedia = "";
    private static long nextAudioRequest;
    private long audioRequestId;
    private long audioRequestedAt;
    private boolean audioLoading;
    private int audioOffset;
    private Button loop;
    private Button mute;
    private Button previous;
    private Button next;
    private Button dimensions;
    private Button statusButton;
    private long timelineDuration;
    private EditBox search;
    private Payloads.Library library;
    private String query = "";
    private String message = "";
    private long queryChanged;
    private long requestedAt;
    private long libraryRequestId;
    private boolean loading;
    private int offset;
    private int rowCount;
    private int left;
    private int top;
    private int panelWidth;
    private boolean initializing;
    private long seekAt;
    private long seekValue = -1;
    private long awaitingRevision = -1;
    private long controlSentAt;

    public CinemaScreen(UUID screen) { super(Component.translatable("screen.cinematic.title")); id = screen; }
    private ClientPlayback.Entry entry() { return CinematicClient.PLAYBACK.get(id); }

    @Override protected void init() {
        initializing = true;
        controls.clear(); mediaRows.clear();
        timeline = null; search = null;
        panelWidth = Math.min(420, width - 24);
        left = (width - panelWidth) / 2;
        top = Math.max(6, (height - 218) / 2);
        switch (page) {
            case PLAYER -> playerWidgets();
            case LIBRARY -> libraryWidgets();
            case AUDIO -> audioWidgets();
        }
        initializing = false;
    }

    private Button button(String label, int x, int y, int w, Runnable action, String tooltip, boolean control) {
        var button = addRenderableWidget(Button.builder(Component.literal(label), pressed -> action.run())
                .bounds(x, y, w, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build());
        if (control) { controls.add(button); }
        if (control) { button.active = canControl() && awaitingRevision < 0; }
        return button;
    }

    private void playerWidgets() {
        long duration = entry() == null ? 1 : Math.max(1, entry().state().durationMs());
        timelineDuration = duration;
        statusButton = button("Connecting…", left, top + 37, panelWidth, () -> CinematicClient.PLAYBACK.retry(id), "Check playback status or retry", false);
        timeline = addRenderableWidget(new ExtendedSlider(left, top + 62, panelWidth, 20, Component.literal("Seek "), Component.literal(" ms"),
                0, duration, entry() == null ? 0 : entry().position(), 1000, 0, false) {
            @Override protected void applyValue() {
                if (!initializing) { seekValue = getValueLong(); seekAt = ClientPlayback.now(); }
            }
        });
        timeline.setTooltip(Tooltip.create(Component.literal("Seek through the media. Arrow keys adjust by one second.")));
        int gap = 4;
        int w = (panelWidth - gap * 4) / 5;
        play = button("Play", left, top + 88, w, () -> send(Payloads.Action.PLAY_PAUSE, "", 0, 0, 0), "Play or pause for everyone", true);
        button("Stop", left + (w + gap), top + 88, w, () -> send(Payloads.Action.STOP, "", 0, 0, 0), "Stop and return to the beginning", true);
        button("−10 s", left + (w + gap) * 2, top + 88, w, () -> send(Payloads.Action.SKIP, "", -10_000, 0, 0), "Seek backward ten seconds", true);
        button("+10 s", left + (w + gap) * 3, top + 88, w, () -> send(Payloads.Action.SKIP, "", 10_000, 0, 0), "Seek forward ten seconds", true);
        loop = button("Loop: Off", left + (w + gap) * 4, top + 88, panelWidth - (w + gap) * 4,
                () -> { if (entry() != null) { send(Payloads.Action.LOOP, "", entry().state().looping() ? 0 : 1, 0, 0); } }, "Repeat this media for everyone", true);
        int third = (panelWidth - 8) / 3;
        button("Choose media…", left, top + 114, third, () -> change(Page.LIBRARY), "Choose from the server's media library", true);
        audioButton = button("Audio…", left + third + 4, top + 114, third, () -> change(Page.AUDIO),
                "Choose the audio language for everyone watching this screen", true);
        dimensions = button("Screen: 1 × 1", left + (third + 4) * 2, top + 114, panelWidth - (third + 4) * 2,
                () -> message = "Add or remove blocks to resize; maximum 40 × 40.",
                "Build a rectangle of touching Screen Blocks facing the same way. Maximum panel: 40 × 40 blocks.", false);
        var audio = CinematicClient.PLAYBACK.settings();
        addRenderableWidget(new ExtendedSlider(left, top + 150, panelWidth - 90, 20, Component.literal("Volume: "), Component.literal("%"),
                0, 100, audio.level(id) * 100, 1, 0, true) {
            @Override protected void applyValue() {
                if (!initializing) { audio.level(id, getValue() / 100); }
            }
        }).setTooltip(Tooltip.create(Component.literal("Your volume for this screen. Also respects Minecraft Master and Jukebox/Note Blocks settings.")));
        mute = button("Mute", left + panelWidth - 84, top + 150, 84, () -> audio.muted(id, !audio.muted(id)), "Mute this screen for yourself", false);
        button("Done", left + (panelWidth - 100) / 2, top + 194, 100, this::onClose, "Return to the game", false);
    }

    private void libraryWidgets() {
        search = addRenderableWidget(new EditBox(font, panelWidth, 20, Component.literal("Search media")));
        search.setX(left); search.setY(top + 24); search.setMaxLength(64); search.setValue(query);
        search.setResponder(value -> {
            query = value;
            offset = 0;
            queryChanged = ClientPlayback.now();
            libraryRequestId++;
            library = null;
            loading = true; requestedAt = queryChanged;
            mediaRows.forEach(row -> row.visible = false);
        });
        search.setTooltip(Tooltip.create(Component.literal("Search filenames in the server library")));
        rowCount = Math.max(1, Math.min(6, (height - top - 112) / 22));
        for (int i = 0; i < rowCount; i++) {
            final int index = i;
            var row = button("", left, top + 52 + i * 22, panelWidth, () -> select(index), "Select this media", true);
            row.visible = false;
            mediaRows.add(row);
        }
        int bottom = top + 56 + rowCount * 22;
        previous = button("Previous", left, bottom, 76, () -> { offset = Math.max(0, offset - rowCount); requestLibrary(); }, "Previous media page", true);
        next = button("Next", left + panelWidth - 76, bottom, 76, () -> { offset += rowCount; requestLibrary(); }, "Next media page", true);
        button("Back", left + (panelWidth - 80) / 2, bottom + 25, 80, () -> change(Page.PLAYER), "Back to playback controls", false);
        requestLibrary();
    }

    private void audioWidgets() {
        rowCount = Math.max(1, Math.min(6, (height - top - 104) / 22));
        for (int i = 0; i < rowCount; i++) {
            final int index = i;
            var row = button("", left, top + 40 + i * 22, panelWidth, () -> {
                int trackIndex = audioOffset + index;
                if (tracks != null && trackIndex < tracks.tracks().size()) {
                    send(Payloads.Action.AUDIO_TRACK, audioMedia, tracks.tracks().get(trackIndex).streamIndex(), 0, 0);
                }
            }, "Choose this audio track for everyone", true);
            row.visible = false; mediaRows.add(row);
        }
        int bottom = top + 50 + rowCount * 22;
        previous = button("Previous", left, bottom, 76, () -> { audioOffset = Math.max(0, audioOffset - rowCount); updateAudioRows(); }, "Previous audio tracks", false);
        next = button("Next", left + panelWidth - 76, bottom, 76, () -> { audioOffset += rowCount; updateAudioRows(); }, "Next audio tracks", false);
        button("Retry", left + (panelWidth - 76) / 2, bottom, 76, this::requestAudio, "Reload audio tracks", false);
        button("Back", left + (panelWidth - 80) / 2, bottom + 25, 80, () -> change(Page.PLAYER), "Back to playback controls", false);
        if (tracks == null && !audioLoading) { requestAudio(); }
        updateAudioRows();
    }

    private void requestAudio() {
        if (!canControl() || entry() == null || entry().state().mediaId().isEmpty()) { return; }
        audioMedia = entry().state().mediaId(); tracks = null; audioLoading = true; audioOffset = 0;
        audioRequestedAt = ClientPlayback.now(); audioRequestId = ++nextAudioRequest;
        ClientPacketDistributor.sendToServer(new Payloads.Request(Payloads.RequestKind.AUDIO_TRACKS, id, 0, audioRequestId, audioMedia));
    }

    public void audioTracks(Payloads.AudioTracks packet) {
        if (!packet.screen().equals(id) || !packet.mediaId().equals(audioMedia) || packet.requestId() != audioRequestId
                || entry() == null || !entry().state().mediaId().equals(packet.mediaId())) { return; }
        tracks = packet; audioLoading = false;
        if (page == Page.AUDIO) { updateAudioRows(); }
    }

    private void updateAudioRows() {
        if (page != Page.AUDIO) { return; }
        boolean permitted = canControl() && awaitingRevision < 0;
        for (int i = 0; i < mediaRows.size(); i++) {
            var row = mediaRows.get(i);
            int index = audioOffset + i;
            row.visible = !audioLoading && tracks != null && index < tracks.tracks().size();
            if (row.visible) {
                var track = tracks.tracks().get(index);
                boolean selected = entry() != null && track.streamIndex() == entry().state().audioStream();
                String label = (selected ? "Selected · " : "") + track.label(index, tracks.tracks());
                row.setMessage(Component.literal(font.plainSubstrByWidth(label, panelWidth - 16)));
                row.setTooltip(Tooltip.create(Component.literal(label + "\n" + track.title() + " · " + track.codec()
                        + " · " + track.channels() + " channels" + (track.defaultTrack() ? " · Default" : "") + "\nShared by everyone watching")));
                row.active = permitted && !selected && tracks.tracks().size() > 1;
            }
        }
        previous.active = !audioLoading && audioOffset > 0;
        next.active = !audioLoading && tracks != null && audioOffset + rowCount < tracks.tracks().size();
    }

    private void change(Page nextPage) { page = nextPage; message = ""; seekValue = -1; clearWidgets(); init(); }
    private void select(int index) {
        if (library != null && index < library.items().size()) {
            send(Payloads.Action.SELECT, library.items().get(index).id(), 0, 0, 0);
            change(Page.PLAYER);
        }
    }

    private void requestLibrary() {
        if (!canControl()) { return; }
        loading = true; requestedAt = ClientPlayback.now(); queryChanged = 0;
        libraryRequestId++;
        mediaRows.forEach(row -> row.visible = false);
        ClientPacketDistributor.sendToServer(new Payloads.Request(Payloads.RequestKind.LIBRARY, id, offset, libraryRequestId, query));
    }

    public void library(Payloads.Library packet) {
        if (!packet.screen().equals(id) || page != Page.LIBRARY || packet.offset() != offset || packet.requestId() != libraryRequestId) { return; }
        library = packet; loading = false;
        for (int i = 0; i < mediaRows.size(); i++) {
            var row = mediaRows.get(i);
            row.visible = i < packet.items().size();
            if (row.visible) {
                var item = packet.items().get(i);
                String duration = formatTime(item.durationMs());
                String name = font.plainSubstrByWidth(item.name(), panelWidth - font.width(duration) - 30);
                row.setMessage(Component.literal(name + "  " + duration));
                row.setTooltip(Tooltip.create(Component.literal(item.name() + " · " + (item.video() ? "Video" : "Audio") + " · " + duration)));
            }
        }
    }

    public void notice(Payloads.Notice packet) { if (packet.screen().equals(id)) { message = packet.message(); awaitingRevision = -1; loading = false; } }

    private boolean canControl() {
        var player = minecraft.player;
        var current = entry();
        return player != null && current != null && minecraft.level != null
                && current.state().key().dimension().equals(minecraft.level.dimension().identifier().toString())
                && PanelGeometry.distanceSquared(current.state().key(), current.state().facing(), current.state().width(), current.state().height(),
                        player.getX(), player.getY(), player.getZ()) <= 256
                && (player.getMainHandItem().is(Cinematic.REMOTE.get()) || player.getOffhandItem().is(Cinematic.REMOTE.get()));
    }

    private void send(Payloads.Action action, String media, long value, int w, int h) {
        if (!canControl() || entry() == null || awaitingRevision >= 0) { return; }
        awaitingRevision = entry().state().revision(); controlSentAt = ClientPlayback.now(); message = "";
        ClientPacketDistributor.sendToServer(new Payloads.Control(id, awaitingRevision, action, media, value, w, h));
    }

    @Override public void tick() {
        var current = entry();
        long now = ClientPlayback.now();
        if (awaitingRevision >= 0 && (current == null || current.state().revision() != awaitingRevision || now - controlSentAt > 2000)) { awaitingRevision = -1; }
        if (current == null && page == Page.PLAYER) {
            statusButton.setMessage(Component.literal("Screen structure changed"));
            statusButton.active = false;
            message = "Reopen a Screen Block to control the new panel.";
        }
        if (current != null && !current.state().mediaId().equals(audioMedia)) {
            tracks = null; audioLoading = false; audioMedia = current.state().mediaId(); audioOffset = 0;
            if (canControl() && current.state().audio()) { requestAudio(); }
        }
        if (audioLoading && now - audioRequestedAt > 10_000) {
            audioLoading = false;
            tracks = new Payloads.AudioTracks(id, audioMedia, audioRequestId, List.of(), "Audio tracks did not respond. Choose Retry.");
        }
        boolean permitted = canControl() && awaitingRevision < 0;
        controls.forEach(button -> button.active = permitted);
        if (page == Page.PLAYER && current != null) {
            if (Math.max(1, current.state().durationMs()) != timelineDuration) {
                clearWidgets(); init(); return;
            }
            statusButton.setMessage(Component.literal(font.plainSubstrByWidth(current.status(), panelWidth - 16)));
            statusButton.setTooltip(Tooltip.create(Component.literal(current.status() + "\nClick to retry playback.")));
            audioButton.active = permitted && current.state().audio() && !audioLoading
                    && (tracks == null || !tracks.status().isEmpty() || tracks.tracks().size() > 1);
            audioButton.setTooltip(Tooltip.create(Component.literal(audioLoading ? "Loading audio tracks…"
                    : !current.state().audio() ? "This media has no audio" : tracks != null && tracks.status().isEmpty() && tracks.tracks().size() < 2
                    ? "This media has no alternative audio track" : "Choose shared audio language. Your volume and mute stay personal.")));
            play.setMessage(Component.literal(current.state().playing() ? "Pause" : "Play"));
            dimensions.setMessage(Component.literal("Screen: " + current.state().width() + " × " + current.state().height()));
            loop.setMessage(Component.literal("Loop: " + (current.state().looping() ? "On" : "Off")));
            mute.setMessage(Component.literal("Mute: " + (CinematicClient.PLAYBACK.settings().muted(id) ? "On" : "Off")));
            timeline.active = permitted && current.state().durationMs() > 0;
            if (seekValue >= 0 && now - seekAt > 250 && permitted) {
                send(Payloads.Action.SEEK, "", seekValue, 0, 0); seekValue = -1;
            } else if (seekValue < 0) {
                initializing = true; timeline.setValue(current.position()); initializing = false;
            }
            timeline.setMessage(Component.literal("Seek: " + formatTime(current.position())));
        } else if (page == Page.LIBRARY) {
            previous.active = permitted && !loading && offset > 0;
            next.active = permitted && !loading && library != null && offset + rowCount < library.total();
            if (queryChanged > 0 && now - queryChanged > 300) { requestLibrary(); }
            if (loading && now - requestedAt > 10_000) { loading = false; message = "Media library did not respond. Go back and try again."; }
        }
        if (page == Page.AUDIO) { updateAudioRows(); }
        if (!canControl()) { seekValue = -1; }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        extractBackground(graphics, mouseX, mouseY, delta);
        graphics.fill(left - 8, top - 4, left + panelWidth + 8, Math.min(height - 4, top + 218), 0xF0212121);
        graphics.outline(left - 8, top - 4, panelWidth + 16, Math.min(height - top, 222), 0xFF777777);
        graphics.text(font, page == Page.PLAYER ? title : Component.literal(page == Page.AUDIO ? "Audio language" : "Choose media"), left, top + 6, 0xFFFFFFFF);
        var current = entry();
        if (page == Page.PLAYER) {
            String name = current == null || current.state().name().isEmpty() ? "No media selected" : current.state().name();
            graphics.text(font, font.plainSubstrByWidth(name, panelWidth), left, top + 24, 0xFFFFFFFF);
            graphics.text(font, Component.literal("Your audio"), left, top + 138, 0xFFCCCCCC);
            String help = !message.isEmpty() ? message : !canControl() ? "Hold a Remote within 16 blocks to edit playback" : "";
            graphics.text(font, font.plainSubstrByWidth(help, panelWidth), left, top + 180, 0xFFFFD18A);
        } else if (page == Page.LIBRARY) {
            String status = !canControl() ? "Hold a Remote within 16 blocks" : loading ? "Loading media…"
                    : !message.isEmpty() ? message : library != null && library.total() == 0 ? "No media found. Ask an operator to check the library." : "";
            if (!status.isEmpty()) { graphics.text(font, font.plainSubstrByWidth(status, panelWidth), left, top + 46 + rowCount * 22, 0xFFFFD18A); }
        }
        if (page == Page.AUDIO) {
            String status = !canControl() ? "Hold a Remote within 16 blocks" : audioLoading ? "Loading audio tracks…"
                    : !message.isEmpty() ? message : tracks == null ? "Choose Retry to load tracks" : !tracks.status().isEmpty() ? tracks.status()
                    : tracks.tracks().isEmpty() ? "This media has no audio" : "Shared by everyone watching";
            graphics.text(font, font.plainSubstrByWidth(status, panelWidth), left, top + 24, 0xFFCCCCCC);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (page == Page.PLAYER && current != null) {
            graphics.centeredText(font, formatTime(seekValue >= 0 ? seekValue : current.position()) + " / " + formatTime(current.state().durationMs()), width / 2, top + 68, 0xFFFFFFFF);
        }
    }

    public static String formatTime(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        return seconds >= 3600 ? "%d:%02d:%02d".formatted(seconds / 3600, seconds / 60 % 60, seconds % 60)
                : "%d:%02d".formatted(seconds / 60, seconds % 60);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
    @Override public void onClose() { if (page != Page.PLAYER) { change(Page.PLAYER); } else { super.onClose(); } }
    @Override public void removed() { CinematicClient.PLAYBACK.flush(); }
}
