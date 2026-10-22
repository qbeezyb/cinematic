package me.cinematic;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class CinemaConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<String> BIND_ADDRESS;
    public static final ModConfigSpec.IntValue PORT;
    public static final ModConfigSpec.ConfigValue<String> ADVERTISED_URL;
    public static final ModConfigSpec.IntValue AUDIO_RANGE;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("media");
        BIND_ADDRESS = builder.comment("Dedicated server HTTP bind address. Singleplayer always uses 127.0.0.1.")
                .define("bindAddress", "0.0.0.0");
        PORT = builder.comment("Dedicated server media port; forward this TCP port. Singleplayer uses an ephemeral port.")
                .defineInRange("port", 25566, 1, 65535);
        ADVERTISED_URL = builder.comment("Public HTTP(S) origin, e.g. https://media.example.com. Empty uses the Minecraft connection host and media port.")
                .define("advertisedUrl", "");
        AUDIO_RANGE = builder.comment("Linear audio fade distance in blocks from the center of each screen.")
                .defineInRange("audioRange", 64, 1, 256);
        builder.pop();
        SPEC = builder.build();
    }

    private CinemaConfig() {}
}
