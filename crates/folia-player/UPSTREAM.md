# Android output adaptation
Decoder, source, resampler, analyser and shared transport state originate from Folia-rust revision 45130275 (the prior locked dependency). This local crate replaces the old equalizer/output path so that Android owns an effect-compatible shared AAudio session.

native/virtual_bass.cpp is the VirtualBassState/helper block from the user's SalamPlayer app/src/main/cpp/hxcore_dsp_native.cpp (2026-11-15), unchanged internally. Rust bass.rs adapts VirtualBassDsp.Design; Kotlin audio/HeadroomEffect.kt adapts the session compensation wrapper. See ../../docs/corners-bass-20261115/HANDOFF.md for the exact pipeline, constraints and validation. No Java source files are carried into this crate/app.
