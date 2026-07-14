package com.apkharden.runtime.fixture;

import com.apkharden.runtime.HardenStringTable;

public final class TestGeneratedStringTable {
    public static final HardenStringTable INSTANCE = new HardenStringTable(
        new byte[16],
        new byte[16],
        new byte[0][],
        new byte[0][]
    );

    private TestGeneratedStringTable() {}
}
