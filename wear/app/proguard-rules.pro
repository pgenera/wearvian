# Keep BLE callback subclasses and Compose; defaults are fine for this app.
-keepclassmembers class * extends android.bluetooth.BluetoothGattCallback { *; }

# BouncyCastle (used only to mint a self-signed cert when importing a key into the Keystore).
# It resolves algorithms by reflection, so keep its classes and silence missing-optional warnings.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
