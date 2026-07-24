package com.cu6.worldhistory.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Searchable timeline picker for historical captures. */
public final class HistoryScreen extends Screen {
    private final HistoryArchive archive;
    private List<SnapshotMetadata> snapshots = List.of();
    private List<Integer> results = List.of();
    private int selected;
    private int scroll;
    private EditBox search;
    private EditBox day;
    private EditBox ticks;
    private EditBox x;
    private EditBox y;
    private EditBox z;
    private int knownSnapshotCount;

    public HistoryScreen(HistoryArchive archive) {
        super(Component.translatable("screen.worldhistory.title"));
        this.archive = archive;
    }

    @Override protected void init() {
        snapshots = archive.snapshots();
        knownSnapshotCount = snapshots.size();
        selected = Math.max(0, snapshots.size() - 1);
        int left = Math.max(12, width / 2 - 290);
        int right = left + 250;
        search = addRenderableWidget(new EditBox(font, left, 42, 220, 20, Component.translatable("screen.worldhistory.search")));
        search.setResponder(value -> { scroll = 0; rebuildResults(); });
        day = addRenderableWidget(new EditBox(font, right, 74, 120, 20, Component.translatable("screen.worldhistory.day")));
        ticks = addRenderableWidget(new EditBox(font, right + 130, 74, 120, 20, Component.translatable("screen.worldhistory.ticks")));
        x = addRenderableWidget(new EditBox(font, right, 128, 76, 20, Component.literal("X")));
        y = addRenderableWidget(new EditBox(font, right + 87, 128, 76, 20, Component.literal("Y")));
        z = addRenderableWidget(new EditBox(font, right + 174, 128, 76, 20, Component.literal("Z")));
        if (!snapshots.isEmpty()) setFields(snapshots.get(selected));
        rebuildResults();
        addRenderableWidget(Button.builder(Component.translatable("gui.worldhistory.previous"), button -> selectRelative(-1))
                .bounds(right, 176, 120, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.worldhistory.next"), button -> selectRelative(1))
                .bounds(right + 130, 176, 120, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.worldhistory.view"), button -> {
            if (!snapshots.isEmpty()) {
                int target = nearestSnapshot();
                if (target >= 0) {
                    selected = target;
                    WorldHistoryClient.requestView(snapshots.get(selected), false);
                    onClose();
                }
            }
        }).bounds(right, 214, 120, 22).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.worldhistory.clear"), button -> {
            WorldHistoryClient.clearView(); onClose();
        }).bounds(right + 130, 214, 120, 22).build());
        Button preview = Button.builder(Component.translatable(WorldHistoryClient.isFreePreview()
                ? "gui.worldhistory.preview.off" : "gui.worldhistory.preview.on"), button -> {
            if (!snapshots.isEmpty()) {
                int target = nearestSnapshot();
                if (target >= 0) {
                    selected = target;
                    WorldHistoryClient.requestView(snapshots.get(selected), !WorldHistoryClient.isFreePreview());
                    onClose();
                }
            }
        }).bounds(right, 242, 250, 22).build();
        preview.setTooltip(Tooltip.create(Component.translatable("gui.worldhistory.preview.tooltip")));
        addRenderableWidget(preview);
    }

    private void rebuildResults() {
        String query = search == null ? "" : search.getValue().trim().toLowerCase(java.util.Locale.ROOT);
        List<Integer> next = new ArrayList<>();
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            SnapshotMetadata snapshot = snapshots.get(i);
            String text = Math.floorDiv(snapshot.gameTime(), 24000L) + " " + Math.floorMod(snapshot.gameTime(), 24000L)
                    + " " + snapshot.x() + " " + snapshot.y() + " " + snapshot.z();
            if (query.isEmpty() || text.contains(query)) next.add(i);
        }
        results = List.copyOf(next);
        if (scroll > Math.max(0, results.size() - 8)) scroll = Math.max(0, results.size() - 8);
    }

    @Override public void tick() {
        super.tick();
        List<SnapshotMetadata> current = archive.snapshots();
        if (current.size() != knownSnapshotCount) {
            snapshots = current;
            knownSnapshotCount = current.size();
            selected = Math.max(0, snapshots.size() - 1);
            rebuildResults();
        }
    }

    private void selectRelative(int delta) {
        if (results.isEmpty()) return;
        int position = results.indexOf(selected);
        if (position < 0) position = 0;
        position = Math.max(0, Math.min(results.size() - 1, position + delta));
        selected = results.get(position);
        setFields(snapshots.get(selected));
    }

    private void setFields(SnapshotMetadata snapshot) {
        day.setValue(Long.toString(Math.floorDiv(snapshot.gameTime(), 24000L)));
        ticks.setValue(Long.toString(Math.floorMod(snapshot.gameTime(), 24000L)));
        x.setValue(String.format("%.1f", snapshot.x()));
        y.setValue(String.format("%.1f", snapshot.y()));
        z.setValue(String.format("%.1f", snapshot.z()));
    }

    private int nearestSnapshot() {
        if (snapshots.isEmpty()) return -1;
        SnapshotMetadata fallback = snapshots.get(selected);
        long targetDay = parseLong(day.getValue(), Math.floorDiv(fallback.gameTime(), 24000L));
        long targetTicks = Math.max(0, Math.min(23999, parseLong(ticks.getValue(), Math.floorMod(fallback.gameTime(), 24000L))));
        long targetTime = targetDay * 24000L + targetTicks;
        double targetX = parseDouble(x.getValue(), fallback.x());
        double targetY = parseDouble(y.getValue(), fallback.y());
        double targetZ = parseDouble(z.getValue(), fallback.z());
        int nearest = selected;
        double score = Double.MAX_VALUE;
        for (int i = 0; i < snapshots.size(); i++) {
            SnapshotMetadata snapshot = snapshots.get(i);
            if (!snapshot.complete()) continue;
            double timeDistance = Math.abs(snapshot.gameTime() - targetTime) / 20.0;
            double positionDistance = Math.abs(snapshot.x() - targetX) + Math.abs(snapshot.y() - targetY) + Math.abs(snapshot.z() - targetZ);
            double candidateScore = timeDistance + positionDistance;
            if (candidateScore < score || (candidateScore == score && snapshot.chunkCount() > snapshots.get(nearest).chunkCount())) {
                score = candidateScore; nearest = i;
            }
        }
        return nearest;
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.flush();
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.flush();
        int left = Math.max(12, width / 2 - 290);
        int right = left + 250;
        graphics.drawCenteredString(font, title, width / 2, 14, 0xffffff);
        graphics.drawString(font, Component.translatable("screen.worldhistory.search_label"), left, 29, 0xb0dfff);
        graphics.drawString(font, Component.translatable("screen.worldhistory.results", results.size()), left, 70, 0xffffff);
        graphics.fill(left, 88, left + 230, Math.min(height - 18, 88 + 8 * 25 + 2), 0xaa101820);
        for (int row = 0; row < 8 && row + scroll < results.size(); row++) {
            int snapshotIndex = results.get(row + scroll);
            SnapshotMetadata snapshot = snapshots.get(snapshotIndex);
            int top = 90 + row * 25;
            int color = snapshotIndex == selected ? 0xff2f6688 : 0xff25303a;
            graphics.fill(left + 2, top, left + 228, top + 22, color);
            graphics.drawString(font, Component.translatable("screen.worldhistory.row", Math.floorDiv(snapshot.gameTime(), 24000L), Math.floorMod(snapshot.gameTime(), 24000L),
                    String.format("%.0f %.0f %.0f", snapshot.x(), snapshot.y(), snapshot.z())), left + 8, top + 7, 0xffffff);
        }
        graphics.drawString(font, Component.translatable("screen.worldhistory.filters"), right, 52, 0xb0dfff);
        graphics.drawString(font, Component.translatable("screen.worldhistory.select_time"), right, 64, 0xaaaaaa);
        graphics.drawString(font, Component.translatable("screen.worldhistory.select_position"), right, 118, 0xaaaaaa);
        if (!snapshots.isEmpty()) graphics.drawString(font, Component.translatable("screen.worldhistory.entities", snapshots.get(selected).entityCount()), right, 274, 0xb0dfff);
        if (!snapshots.isEmpty() && snapshots.stream().noneMatch(SnapshotMetadata::complete)) {
            graphics.drawString(font, Component.translatable("screen.worldhistory.incomplete"), right, 292, 0xffcc66);
        }
        graphics.flush();
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int left = Math.max(12, width / 2 - 290);
        if (button == 0 && mouseX >= left && mouseX <= left + 230 && mouseY >= 90 && mouseY < 290) {
            int row = (int) ((mouseY - 90) / 25) + scroll;
            if (row >= 0 && row < results.size()) { selected = results.get(row); setFields(snapshots.get(selected)); }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(Math.max(0, results.size() - 8), scroll - (int) Math.signum(scrollY)));
        return true;
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    private static double parseDouble(String value, double fallback) {
        try { return Double.parseDouble(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    @Override public boolean isPauseScreen() { return false; }
}
