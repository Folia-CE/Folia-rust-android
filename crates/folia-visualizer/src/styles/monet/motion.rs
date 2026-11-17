// Monet's clocks: intro tweens, the rail springs, and the glow envelope from `monetLyricMotion.ts`.

use crate::anim::Ease;

pub const SCROLL_STIFFNESS: f64 = 142.0;
pub const SCROLL_DAMPING: f64 = 28.0;
pub const SCROLL_MASS: f64 = 0.82;
pub const SCALE_STIFFNESS: f64 = 150.0;
pub const SCALE_DAMPING: f64 = 30.0;
pub const SCALE_MASS: f64 = 0.78;
const INTRO: Ease = Ease::new(0.25, 1.0, 0.5, 1.0);
/// `[0.32, 0.72, 0, 1]`: the rail's opacity, blur, exit and subtitle-row tweens.
pub const SWAP: Ease = Ease::new(0.32, 0.72, 0.0, 1.0);

/// Both Monet rail springs are overdamped. Sample the two roots once for the whole
/// window. Retargeting carries the position and derivative of the presented frame.
pub struct Spring {
    position: f32,
    velocity: f32,
    d_position: f32,
    d_velocity: f32,
}

impl Spring {
    fn sample(t: f64, stiffness: f64, damping: f64, mass: f64) -> Self {
        let disc = (damping * damping - 4. * mass * stiffness).sqrt();
        let a = (-damping + disc) / (2. * mass);
        let b = (-damping - disc) / (2. * mass);
        let ea = (a * t.max(0.)).exp();
        let eb = (b * t.max(0.)).exp();
        Self {
            position: ((a * eb - b * ea) / (a - b)) as f32,
            velocity: ((ea - eb) / (a - b)) as f32,
            d_position: (a * b * (eb - ea) / (a - b)) as f32,
            d_velocity: ((a * ea - b * eb) / (a - b)) as f32,
        }
    }

    pub fn scroll(t: f64) -> Self {
        Self::sample(t, SCROLL_STIFFNESS, SCROLL_DAMPING, SCROLL_MASS)
    }
    pub fn scale(t: f64) -> Self {
        Self::sample(t, SCALE_STIFFNESS, SCALE_DAMPING, SCALE_MASS)
    }

    pub fn apply(&self, from: f32, to: f32, speed: f32) -> (f32, f32) {
        let delta = from - to;
        (to + delta * self.position + speed * self.velocity, delta * self.d_position + speed * self.d_velocity)
    }
}

pub fn intro(time: f64, delay: f64, duration: f64) -> f32 {
    let t = time - delay;
    if t <= 0.0 {
        0.0
    } else if duration <= 0.0 || t >= duration {
        1.0
    } else {
        INTRO.at(t / duration) as f32
    }
}

/// Chaotic portrait loop: 9s ease-in-out, delay 0.2s, as `(x, y)`. The ±1.2° turn is left out: the rounded cover
/// clip cannot rotate.
pub fn portrait_sway(time: f64) -> (f32, f32) {
    if time < 0.2 {
        return (0.0, 0.0);
    }
    let elapsed = (time - 0.2) % 9.0;
    let times = [0.0, 0.25, 0.5, 0.75, 1.0];
    let ease = Ease::IN_OUT;
    let sample = |values: &[f64]| crate::keyframes(values, &times, elapsed, 9.0, ease) as f32;
    (sample(&[0.0, -9.0, 0.0, 9.0, 0.0]), sample(&[0.0, -18.0, 0.0, 18.0, 0.0]))
}

/// `clamp(minRem, vw, maxRem)` in pixels, times the extra scales.
pub fn clamp_font(min_rem: f32, vw: f32, max_rem: f32, width: f32, extra: f32) -> f32 {
    let rem = 16.0;
    (vw / 100.0 * width).clamp(min_rem * rem, max_rem * rem) * extra
}

/// 1 below 1536px, rising to 1.16 at 2200px. `resolveMonetLargeScreenScale`.
pub fn large_screen(width: f32) -> f32 {
    if width <= 1536.0 { 1.0 } else { (1.0 + (width - 1536.0) / (2200.0 - 1536.0) * 0.16).min(1.16) }
}

pub fn glow(time: f64, start: f64, end: f64, line_end: f64) -> f32 {
    if time <= start {
        return 0.0;
    }
    let rise = (end - start).max(0.001) * 1.18;
    let peak = start + rise;
    let tail = line_end.max(end + 1.05);
    let smooth = |p: f64| {
        let p = p.clamp(0.0, 1.0);
        (p * p * (3.0 - 2.0 * p)) as f32
    };
    if time <= peak { smooth((time - start) / rise) } else { smooth(1.0 - (time - peak) / (tail - peak).max(0.18)) }
}
