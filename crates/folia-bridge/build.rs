fn main() {
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        // A successful shared-library link must also be loadable on Android.
        // Fail here for missing native runtime symbols instead of crashing in
        // Application.onCreate when System.loadLibrary first loads the bridge.
        println!("cargo:rustc-link-arg-cdylib=-Wl,--no-undefined");
    }
}
