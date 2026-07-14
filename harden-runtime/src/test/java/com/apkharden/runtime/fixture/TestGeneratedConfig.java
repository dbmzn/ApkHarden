package com.apkharden.runtime.fixture;

import com.apkharden.runtime.HardenConfig;

public final class TestGeneratedConfig {
    public static final HardenConfig INSTANCE = new HardenConfig(
        "com.example.app",
        "release",
        "build-id",
        "abababababababababababababababababababababababababababababababab"
    );

    private TestGeneratedConfig() {}
}
