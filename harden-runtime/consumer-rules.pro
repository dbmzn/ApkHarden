# Default Application is referenced from the transformed manifest.
-keep class com.apkharden.runtime.HardenApplication {
    public <init>();
    protected void attachBaseContext(android.content.Context);
}

# HardenApplication loads this generated class and field by their exact names.
-keep class com.apkharden.generated.HardenVariantConfig {
    public static final com.apkharden.runtime.HardenConfig INSTANCE;
}
