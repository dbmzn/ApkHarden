package com.example.fixture;

public final class StringFixture {
    public static final String PUBLIC_CONTRACT = "fixture-public-contract";
    private static final String PRIVATE_SECRET = "fixture-private-secret";

    private StringFixture() {}

    public static String businessSecret() {
        return "fixture-business-secret";
    }

    public static String explicitContract() {
        return "fixture-explicit-contract";
    }

    public static String reflectionContract() throws ClassNotFoundException {
        Class.forName("com.example.fixture.ReflectionTarget");
        return "fixture-reflection-companion";
    }

    public static String privateSecret() {
        return PRIVATE_SECRET;
    }
}
