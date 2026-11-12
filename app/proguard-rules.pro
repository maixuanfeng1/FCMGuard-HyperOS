# Shizuku loads the helper from another process using its class name and constructor.
# Binder transaction IDs and descriptors come from AIDL; methods may be optimized.
-keep class com.reed.fcmguard.SettingsUserService {
    public <init>();
    public <init>(android.content.Context);
}
