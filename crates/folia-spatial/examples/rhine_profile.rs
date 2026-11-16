//! Host-only A/B workload for the actual scene and retained light packer.
//! Run with the same toolchain/input before and after changing the crate profile.
//! Timings exclude compilation, initialization and all GPU/UI work.
use folia_spatial::daily_spatial::{self as scene, rhine_gpu, *};
use std::{hint::black_box, time::Instant};

fn main() {
    for dark in [false, true] {
        let id = scene::create();
        assert_ne!(id, 0);
        let mut frame = vec![0.; FLOATS];
        let mut bindings = vec![0; SLOTS * 5];
        let mut packed = vec![0.; SLOTS * rhine_gpu::INSTANCE];
        for n in 0..SLOTS {
            bindings[n * 5] = (n % 48 + 1) as i32;
            bindings[n * 5 + 1] = 1;
            bindings[n * 5 + 4] = 0xffcc4422u32 as i32;
        }
        assert!(scene::command(id, 10, 1024, 0., 0.));
        assert!(scene::command(id, 3, 1, 0., 0.));
        for _ in 0..120 { assert!(scene::step(id, 1. / 90., 400., 890., &mut frame)); }
        let mut previous = usize::MAX;
        let mut times = Vec::new();
        let mut checksum = 0u64;
        let mut max_visible = 0;
        for tick in 0..110 {
            let started = Instant::now();
            assert!(scene::command(id, 4, 0, 1.5, -0.75));
            assert!(scene::step(id, 1. / 90., 400., 890., &mut frame));
            frame[RHINE + 18] = if dark { 0. } else { -1. };
            frame[RHINE + 24] = 1.; // SDR isolates the theme-specific CPU work.
            frame[RHINE + 19..RHINE + 19 + 4].copy_from_slice(&[-32., -32., 432., 922.]);
            frame[RHINE + 23] = 1.;
            let (count, _) = rhine_gpu::pack_changes(black_box(&frame), black_box(&bindings), &mut packed, previous).unwrap();
            previous = count;
            let elapsed = started.elapsed().as_secs_f64() * 1_000.;
            if tick >= 20 { times.push(elapsed); }
            max_visible = max_visible.max(count);
            for value in black_box(&packed[..count * rhine_gpu::INSTANCE]) {
                checksum = checksum.wrapping_mul(31).wrapping_add(value.to_bits() as u64);
            }
        }
        scene::release(id);
        times.sort_by(f64::total_cmp);
        println!("theme={} frames={} max_drawn={} median_ms={:.3} p95_ms={:.3} checksum={:016x}",
            if dark { "dark" } else { "light" }, times.len(), max_visible,
            times[times.len()/2], times[times.len()*95/100], checksum);
    }
}
