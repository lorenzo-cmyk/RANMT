## 5. Rust Client Integration (UniFFI)

The Android app calls the Rust QUIC client via UniFFI-generated Kotlin bindings and a native `.so`.

### 5.1 Build the native library

Use `cargo-ndk` to build the Rust `cdylib` for the desired Android ABI:

```bash
cd src/rust
cargo install cargo-ndk
rustup target add aarch64-linux-android x86_64-linux-android
cargo ndk -t arm64-v8a -o ../../android_client/app/src/main/jniLibs build -p ranmt-client --features ffi --release
```

This writes `libranmt_client.so` into:

```
android_client/app/src/main/jniLibs/arm64-v8a/
```

Optional emulator build:

```bash
cargo ndk -t x86_64 -o ../../android_client/app/src/main/jniLibs build -p ranmt-client --features ffi --release
```

### 5.2 Generate Kotlin bindings

Generate Kotlin sources from the built library:

```bash
cargo install uniffi-bindgen
uniffi-bindgen generate \
    --library target/aarch64-linux-android/release/libranmt_client.so \
    --language kotlin \
    --out-dir ../../android_client/app/src/main/java
```

This creates `uniffi/ranmt_client/*` and is loaded by the wrapper at:

```
android_client/app/src/main/java/dev/ranmt/rust/RustClient.kt
```

### 5.3 Android runtime dependency

The generated Kotlin bindings use JNA. The Android app includes the AAR so
`libjnidispatch.so` is packaged:

```
implementation("net.java.dev.jna:jna:5.14.0@aar")
```
