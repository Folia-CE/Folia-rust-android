fn main(){
 let mut build=cc::Build::new();build.cpp(true).std("c++17").opt_level(3).file("native/virtual_bass.cpp");
 if std::env::var("CARGO_CFG_TARGET_OS").as_deref()==Ok("android"){
  build.cpp_link_stdlib("c++_static");build.file("native/output.cpp");println!("cargo:rustc-link-lib=aaudio");println!("cargo:rustc-link-lib=dl");
 }
 build.compile("folia_bass");
 // NDK libc++_static does not include libc++abi. Rust links through clang (not
 // clang++), so the C++ driver will not supply exception/RTTI support for us.
 if std::env::var("CARGO_CFG_TARGET_OS").as_deref()==Ok("android"){
  // Let the final Android linker resolve this archive in its NDK sysroot;
  // rustc's rlib bundler does not inherit that sysroot search path.
  println!("cargo:rustc-link-lib=static:-bundle=c++abi");
 }
 println!("cargo:rerun-if-changed=native");
}
