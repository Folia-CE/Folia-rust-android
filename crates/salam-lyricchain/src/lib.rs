//! The scrolling lyrics' rows as a chain of springs, for com.lab.hv.LyricLagChain: the physics
//! (chain.rs), one instance per lyrics view, all called on the UI thread.

mod chain;
mod jni;

use chain::Chain;
use jni::{array_length, Critical, Env, JObject};

unsafe fn chain<'a>(handle: i64) -> Option<&'a mut Chain> {
    (handle as *mut Chain).as_mut()
}

#[no_mangle]
pub extern "system" fn Java_com_lab_hv_LyricLagChain_nativeCreate(_env: Env, _class: JObject, row_height: f32) -> i64 {
    Box::into_raw(Box::new(Chain::new(row_height))) as i64
}

#[no_mangle]
pub extern "system" fn Java_com_lab_hv_LyricLagChain_nativeDestroy(_env: Env, _class: JObject, handle: i64) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Chain) });
    }
}

/// `tops`: each row's place on the track (px, top to bottom); `sources`: the line it belongs to.
#[no_mangle]
pub extern "system" fn Java_com_lab_hv_LyricLagChain_nativeSetRows(
    env: Env, _class: JObject, handle: i64, tops: JObject, sources: JObject, row_height: f32,
) {
    let Some(chain) = (unsafe { chain(handle) }) else { return };
    unsafe {
        let tops_len = array_length(env, tops);
        let sources_len = array_length(env, sources);
        let tops_vec: Vec<f32> = match Critical::<f32>::get(env, tops, tops_len) {
            Some(mut held) => held.slice().to_vec(),
            None => return,
        };
        let sources_vec: Vec<i32> = match Critical::<i32>::get(env, sources, sources_len) {
            Some(mut held) => held.slice().to_vec(),
            None => return,
        };
        chain.set_rows(&tops_vec, &sources_vec, row_height);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_lab_hv_LyricLagChain_nativeSetHead(
    _env: Env, _class: JObject, handle: i64, head: i32, rigid: i32,
) {
    if let Some(chain) = unsafe { chain(handle) } {
        chain.set_head(head, rigid != 0);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_lab_hv_LyricLagChain_nativeSetGain(_env: Env, _class: JObject, handle: i64, gain: f32) {
    if let Some(chain) = unsafe { chain(handle) } {
        chain.set_gain(gain);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_lab_hv_LyricLagChain_nativeReset(_env: Env, _class: JObject, handle: i64, track: f32) {
    if let Some(chain) = unsafe { chain(handle) } {
        chain.reset(track);
    }
}

/// Moves on by `dt` with the track at `track`, writing each row's offset into `out`. 1 at rest.
#[no_mangle]
pub extern "system" fn Java_com_lab_hv_LyricLagChain_nativeStep(
    env: Env, _class: JObject, handle: i64, dt: f32, track: f32, out: JObject,
) -> i32 {
    let Some(chain) = (unsafe { chain(handle) }) else { return 1 };
    unsafe {
        let len = array_length(env, out);
        match Critical::<f32>::get(env, out, len) {
            Some(mut held) => chain.step(dt, track, held.slice()) as i32,
            None => 1,
        }
    }
}

mod motion;
pub use motion::*;
