Native libraries go here.

Place the built Rust CDYLIB outputs under ABI folders, for example:
- arm64-v8a/libranmt_client.so
- x86_64/libranmt_client.so (optional for emulator)

The Kotlin layer loads the library by name: ranmt_client.

Build steps (from repo root):

1) Export the Android NDK path:
	export ANDROID_NDK_HOME=/home/lorenzo/Android/Sdk/ndk/30.0.14904198

2) Build the Rust library:
	cargo ndk -t arm64-v8a -o src/kotlin/app/src/main/jniLibs \
	  build -p ranmt-client --features ffi --release

3) Install the UniFFI CLI and generate Kotlin bindings:
	cargo install uniffi --features cli
	uniffi-bindgen generate \
	  --library target/aarch64-linux-android/release/libranmt_client.so \
	  --language kotlin \
	  --out-dir src/kotlin/app/src/main/java

4) Ensure the Android app includes the JNA runtime dependency (AAR):
	implementation("net.java.dev.jna:jna:5.14.0@aar")
