use super::jni::{array_length, Critical, Env, JObject};
const TRUE: u8 = 1;
const FALSE: u8 = 0;

fn lyric_preload_quota(period_ns:i64, elapsed_ns:i64, cost_ns:i64, remaining:i32)->i32 {
    if remaining<=0 || period_ns<=0 { return 0; }
    let reserve=period_ns/2;
    let available=(period_ns-elapsed_ns.max(0)-reserve).max(0);
    let budget=(period_ns/8).clamp(250_000,2_000_000).min(available);
    if budget<250_000 { return 0; }
    (budget/cost_ns.max(100_000)).clamp(1,4).min(remaining as i64) as i32
}

#[no_mangle]
pub extern "system" fn Java_com_lab_hv_util_NativeKernels_nativeMotionValue(
    _env: Env, _class: JObject, from: f32, to: f32, fraction: f32,
) -> f32 { from + (to - from) * fraction }

#[no_mangle]
pub extern "system" fn Java_com_lab_hv_util_NativeKernels_nativeMotionBatch(
    env: Env, _class: JObject, rows: JObject, count: i32,
) -> u8 {
    if count < 0 || count > 16384 { return FALSE; }
    let len = count as usize * 4;
    if len == 0 { return TRUE; }
    unsafe {
        if array_length(env, rows) < len { return FALSE; }
        let Some(mut held) = Critical::<f32>::get(env, rows, len) else { return FALSE };
        for row in held.slice().chunks_exact_mut(4) { row[3] = row[0] + (row[1] - row[0]) * row[2]; }
    }
    TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_lab_hv_util_NativeKernels_nativeMotionArgb(
    _env: Env, _class: JObject, from: i32, to: i32, t: f32,
) -> i32 {
    let mut result = 0u32;
    for shift in [24, 16, 8, 0] {
        let mut a = ((from as u32 >> shift) & 255) as f32 / 255.0;
        let mut b = ((to as u32 >> shift) & 255) as f32 / 255.0;
        if shift != 24 { a = (a as f64).powf(2.2) as f32; b = (b as f64).powf(2.2) as f32; }
        let mut c = a + (b - a) * t;
        if shift != 24 { c = (c as f64).powf(1.0 / 2.2) as f32; }
        result |= ((c * 255.0 + 0.5).floor() as i32 as u32) << shift;
    }
    result as i32
}

#[no_mangle]
pub extern "system" fn Java_com_lab_hv_util_NativeKernels_nativeLyricPreloadQuota(
    _env:Env,_class:JObject,period:i64,elapsed:i64,cost:i64,remaining:i32)->i32 {
    lyric_preload_quota(period,elapsed,cost,remaining)
}
