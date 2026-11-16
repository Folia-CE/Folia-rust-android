//! Flat frame ABI. Units are logical display points; song and cell IDs are distinct.
pub const HEADER: usize = 32;
pub const STRIDE: usize = 16;
pub const VISIBLE: usize = 384;
// Pad landscape retains the full camera field and its optical/camera guard.
// This is capacity only: rendering, hits and uploads use the published count.
pub const SLOTS:usize=2048;
pub const PREFETCH: usize = 192;
pub const ACTION:usize=HEADER+STRIDE*SLOTS+PREFETCH;
pub const RHINE:usize=ACTION+192;
// Hologram face planes in the reserved Rhine header. Each retained slot is
// cell, song, origin x/y, local width/height, alpha, xx, interactive, xy, yx, yy.
pub const RHINE_HOLOGRAM:usize=25;
pub const HOLOGRAM_STRIDE:usize=12;
pub const HOLOGRAM_SLOTS:usize=3;
pub const RHINE_POSES:usize=RHINE+64;
pub const FLOATS: usize = RHINE_POSES+SLOTS*16;
pub const VERSION:u32=6;
