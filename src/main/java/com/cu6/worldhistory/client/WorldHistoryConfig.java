package com.cu6.worldhistory.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persistent client settings for capture and historical rendering. */
public final class WorldHistoryConfig {
    public int renderDistanceChunks = 32;
    public int sampleIntervalTicks = 20;
    public int chunksPerTick = 1;
    public int maxSnapshots = 600;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static WorldHistoryConfig load(Path gameDirectory) {
        Path file = gameDirectory.resolve("config").resolve("worldhistory.json");
        if (!Files.exists(file)) {
            WorldHistoryConfig config = new WorldHistoryConfig();
            config.save(gameDirectory);
            return config;
        }
        try {
            WorldHistoryConfig config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), WorldHistoryConfig.class);
            if (config == null) throw new IllegalStateException("WorldHistory configuration is empty: " + file);
            if (config.chunksPerTick == 0) config.chunksPerTick = 1;
            config.validate();
            config.save(gameDirectory);
            return config;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read WorldHistory configuration: " + file, e);
        }
    }

    public void save(Path gameDirectory) {
        Path file = gameDirectory.resolve("config").resolve("worldhistory.json");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write WorldHistory configuration: " + file, e);
        }
    }

    private void validate() {
        if (renderDistanceChunks < 1 || renderDistanceChunks > 32) throw new IllegalStateException("renderDistanceChunks must be between 1 and 32");
        if (sampleIntervalTicks < 1) throw new IllegalStateException("sampleIntervalTicks must be positive");
        if (chunksPerTick < 1 || chunksPerTick > 8) throw new IllegalStateException("chunksPerTick must be between 1 and 8");
        if (maxSnapshots < 1) throw new IllegalStateException("maxSnapshots must be positive");
    }
}
