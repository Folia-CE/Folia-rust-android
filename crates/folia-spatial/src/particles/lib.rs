//! A cover blown away as powder, for com.lab.hv.CoverShatterView: the wind and the grains
//! (dust.rs), one instance per cover being blown away, all called on the UI thread.

mod dust;
mod jni;

use dust::Dust;
use jni::{array_length, Critical, Env, JObject};

unsafe fn dust<'a>(handle: i64) -> Option<&'a mut Dust> {
    (handle as *mut Dust).as_mut()
}

/// `pixels`: the cover drawn small (`sw` × `sh` ARGB, transparent outside its outline), over a
/// cover of `width` × `height` units, `unit` units to a dp; the wind blows along (`wind_x`, `wind_y`).
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialDust_nativeCreate(
    env: Env, _class: JObject, pixels: JObject, sw: i32, sh: i32, width: f32, height: f32, unit: f32,
    wind_x: f32, wind_y: f32, seed: i64,
) -> i64 {
    let sw = sw.max(0) as usize;
    let sh = sh.max(0) as usize;
    let copy: Vec<u32> = unsafe {
        let len = array_length(env, pixels);
        match Critical::<i32>::get(env, pixels, len) {
            Some(mut held) => held.slice().iter().map(|&p| p as u32).collect(),
            None => Vec::new(),
        }
    };
    let dust = Box::new(Dust::new(&copy, sw, sh, width, height, unit, wind_x, wind_y, seed as u64));
    Box::into_raw(dust) as i64
}

/// Cells (high 32 bits) and grains (low 32 bits): the quads the step arrays must hold.
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialDust_nativeCapacity(_env: Env, _class: JObject, handle: i64) -> i64 {
    match unsafe { dust(handle) } {
        Some(dust) => ((dust.cell_count() as i64) << 32) | dust.grain_count() as i64,
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialDust_nativeDestroy(_env: Env, _class: JObject, handle: i64) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Dust) });
    }
}

/// Moves on by `dt` and fills the quads; `counts` gets mask quads, dust quads, and 1 once the
/// whole picture has given way. Returns 0 once the last grain is gone.
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialDust_nativeStep(
    env: Env, _class: JObject, handle: i64, dt: f32, mask_xy: JObject, mask_argb: JObject,
    dust_xy: JObject, dust_argb: JObject, counts: JObject,
) -> u8 {
    let Some(dust) = (unsafe { dust(handle) }) else { return 0 };
    let alive = dust.step(dt);
    unsafe {
        // Every length first: no other JNI call is allowed while an array is held.
        let lens = [
            array_length(env, mask_xy),
            array_length(env, mask_argb),
            array_length(env, dust_xy),
            array_length(env, dust_argb),
            array_length(env, counts),
        ];
        if lens[4] < 3 {
            return alive as u8;
        }
        let (Some(mut mxy), Some(mut margb), Some(mut dxy), Some(mut dargb), Some(mut out)) = (
            Critical::<f32>::get(env, mask_xy, lens[0]),
            Critical::<i32>::get(env, mask_argb, lens[1]),
            Critical::<f32>::get(env, dust_xy, lens[2]),
            Critical::<i32>::get(env, dust_argb, lens[3]),
            Critical::<i32>::get(env, counts, lens[4]),
        ) else {
            return alive as u8;
        };
        let (m, d, whole) = dust.fill(mxy.slice(), margb.slice(), dxy.slice(), dargb.slice());
        let out = out.slice();
        out[0] = m as i32;
        out[1] = d as i32;
        out[2] = whole as i32;
    }
    alive as u8
}
