// One call advances a whole bubble. The Android layout reads the resulting outline and
// content transforms at its own measurement points; a reversal keeps the same state.
use crate::jni::{array_length, Critical, Env, JObject};
use std::f32::consts::TAU;

fn smooth(t: f32) -> f32 {
    let t = t.clamp(0., 1.);
    t * t * (3. - 2. * t)
}

fn spring(x: &mut f32, velocity: &mut f32, target: f32, omega: f32, damping: f32, h: f32) {
    *velocity += (omega * omega * (target - *x) - 2. * damping * omega * *velocity) * h;
    *x += *velocity * h;
}

#[repr(C)]
struct Bubble {
    x: f32,
    y: f32,
    vx: f32,
    vy: f32,
    blur: f32,
    stretch_x: f32,
    stretch_y: f32,
    stretch_vx: f32,
    stretch_vy: f32,
    resize: f32,
    resize_speed: f32,
    height: f32,
    edge: [f32; 4],
    edge_speed: [f32; 4],
    press: f32,
    half: f32,
    wobble: f32,
    wobble_speed: f32,
    direction: f32,
    last_speed: f32,
    acceleration: f32,
    scale_x: f32,
    scale_y: f32,
    morphing: f32,
    resizing: f32,
}
const _: () = assert!(std::mem::size_of::<Bubble>() == 31 * 4);

impl Bubble {
    fn press(&mut self, down: bool, seconds: f32) -> bool {
        let goal = if down { 1. } else { 0. };
        self.press = if down { (self.press + seconds / 0.11).min(1.) } else { (self.press - seconds / 0.24).max(0.) };
        let swell = 1. + 0.08 * smooth(self.press);
        let half = 25. * swell;
        let speed = (half - self.half) / seconds;
        self.half = half;
        let dt = seconds.min(1. / 30.);
        if speed.abs() > 24. {
            self.direction = speed.signum();
        }
        self.acceleration += ((speed - self.last_speed) / dt - self.acceleration) * (1. - (-dt / 0.025).exp());
        self.last_speed = speed;
        let target = 0.14 * (speed.abs() / 900.).tanh() + 0.1 * (self.acceleration * self.direction / 5000.).tanh();
        let n = (dt * 240.).ceil().max(1.) as usize;
        for _ in 0..n {
            spring(&mut self.wobble, &mut self.wobble_speed, target, 19.6, 0.42, dt / n as f32);
            if self.wobble < -0.16 {
                self.wobble = -0.16;
                self.wobble_speed = self.wobble_speed.max(0.);
            }
            if self.wobble > 0.32 {
                self.wobble = 0.32;
                self.wobble_speed = self.wobble_speed.min(0.);
            }
        }
        let rest =
            self.press == goal && target.abs() < 0.0015 && self.wobble.abs() < 0.0015 && self.wobble_speed.abs() < 0.02;
        if rest {
            self.wobble = 0.;
            self.wobble_speed = 0.;
        }
        self.scale_x = swell * (1. + self.wobble);
        self.scale_y = swell * (1. + self.wobble).powf(-0.6);
        !rest
    }

    fn stretch_rest(&self) -> bool {
        self.stretch_x.abs() < 0.0015
            && self.stretch_y.abs() < 0.0015
            && self.stretch_vx.abs() < 0.02
            && self.stretch_vy.abs() < 0.02
    }
    fn clear_stretch(&mut self) {
        self.stretch_x = 0.;
        self.stretch_y = 0.;
        self.stretch_vx = 0.;
        self.stretch_vy = 0.;
    }

    fn advance(
        &mut self,
        open: bool,
        pressed: bool,
        dt: f32,
        width: f32,
        dock: f32,
        side: f32,
        from: f32,
        to: f32,
        blur_start: f32,
        carry: f32,
    ) -> bool {
        let dt = dt.clamp(0., 0.048);
        if dt == 0. {
            return self.morphing != 0. || self.resizing != 0. || pressed || self.press != 0. || self.wobble != 0.;
        }
        let press = self.press(pressed, dt);
        let n = (dt / 0.004).ceil().max(1.) as usize;
        let h = dt / n as f32;
        let goal = if open { 1. } else { 0. };
        if self.resizing != 0. {
            for _ in 0..n {
                spring(&mut self.resize, &mut self.resize_speed, 1., TAU / 0.34, 0.62, h);
            }
            self.height = from + (to - from) * self.resize;
        }
        if self.morphing != 0. {
            let (wx, wy, damping) =
                if open { (TAU / 0.46, TAU / 0.50, 0.655) } else { (TAU / 0.40, TAU / 0.36, 0.695) };
            for _ in 0..n {
                spring(&mut self.x, &mut self.vx, goal, wx, damping, h);
                spring(&mut self.y, &mut self.vy, goal, wy, damping, h);
            }
        }
        if self.morphing != 0. || self.resizing != 0. || !self.stretch_rest() {
            let tx = 0.1 * ((self.vx * (width - 50.)).abs() / 1400.).tanh();
            let sy = if self.morphing != 0. {
                self.vy * (self.height - dock)
            } else {
                self.resize_speed * (self.height - from)
            };
            let ty = 0.1 * (sy.abs() / 1400.).tanh();
            for _ in 0..n {
                spring(&mut self.stretch_x, &mut self.stretch_vx, tx, 24., 0.92, h);
                spring(&mut self.stretch_y, &mut self.stretch_vy, ty, 24., 0.92, h);
            }
        }
        if self.morphing != 0. {
            let way = [(width - 50.) * (0.5 - side), (self.height - dock) * 0.5];
            let v = [self.vx * way[0], self.vy * way[1]];
            let speed = v[0].hypot(v[1]);
            let gain = if speed > 0.001 { 14.4 * 1.2 * (speed / 1400.).tanh() / speed } else { 0. };
            for i in 0..4 {
                let axis = i % 2;
                let dir = way[axis] * (if open { 1. } else { -1. }) * (if i < 2 { -1. } else { 1. });
                let (response, damping) = if dir > 0. {
                    (0.36, 0.585)
                } else if dir < 0. {
                    (0.58, 0.48)
                } else {
                    (0.46, 0.54)
                };
                for _ in 0..n {
                    spring(&mut self.edge[i], &mut self.edge_speed[i], gain * v[axis], TAU / response, damping, h);
                }
            }
            let span = goal - blur_start;
            let p = if span.abs() < 0.0001 { 1. } else { ((self.x + self.y) * 0.5 - blur_start) / span };
            self.blur = (smooth(p / 0.16) * (1. - smooth((p - 0.5) / 0.52))).max(carry * (1. - smooth(p / 0.32)));
            if (self.x - goal).abs() < 0.0015
                && (self.y - goal).abs() < 0.0015
                && self.vx.abs() < 0.02
                && self.vy.abs() < 0.02
                && self.stretch_rest()
                && self.edge.iter().all(|v| v.abs() < 0.25)
                && self.edge_speed.iter().all(|v| v.abs() < 4.)
            {
                self.x = goal;
                self.y = goal;
                self.vx = 0.;
                self.vy = 0.;
                self.blur = 0.;
                self.edge = [0.; 4];
                self.edge_speed = [0.; 4];
                self.clear_stretch();
                self.morphing = 0.;
            }
        }
        if self.resizing != 0.
            && (self.resize - 1.).abs() < 0.0015
            && self.resize_speed.abs() < 0.02
            && (self.morphing != 0. || self.stretch_rest())
        {
            self.height = to;
            self.resize = 1.;
            self.resize_speed = 0.;
            self.resizing = 0.;
            if self.morphing == 0. {
                self.clear_stretch();
            }
        }
        press || self.morphing != 0. || self.resizing != 0. || !self.stretch_rest()
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_MotionKernel_bubble(
    env: Env,
    _: JObject,
    array: JObject,
    open: u8,
    pressed: u8,
    dt: f32,
    width: f32,
    dock: f32,
    side: f32,
    from: f32,
    to: f32,
    start: f32,
    carry: f32,
) -> u8 {
    unsafe {
        if array_length(env, array) != 31 {
            return 0;
        }
        let Some(mut pin) = Critical::<f32>::get(env, array, 31) else { return 0 };
        // repr(C), only f32 fields; Java float arrays have matching size and alignment.
        let state = &mut *pin.slice().as_mut_ptr().cast::<Bubble>();
        state.advance(open != 0, pressed != 0, dt, width, dock, side, from, to, start, carry) as u8
    }
}

// Shared closed-form solution for the album camera, collection entry and hand-to-hand flight.
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_MotionKernel_travel(
    env: Env,
    _: JObject,
    array: JObject,
    target: f64,
    seconds: f64,
    frequency: f64,
    dock: u8,
) -> u8 {
    unsafe {
        if array_length(env, array) != 2 {
            return 0;
        }
        let Some(mut pin) = Critical::<f64>::get(env, array, 2) else {
            return 0;
        };
        let state = pin.slice();
        let dt = seconds.clamp(0., if dock != 0 { 0.064 } else { 0.05 });
        if dt == 0. {
            return ((state[0] - target).abs() > 0.0001 || state[1].abs() > 0.001) as u8;
        }
        let offset = state[0] - target;
        let tangent = state[1] + frequency * offset;
        let decay = (-frequency * dt).exp();
        let x = target + (offset + tangent * dt) * decay;
        let mut velocity = (state[1] - frequency * tangent * dt) * decay;
        if dock == 0 {
            if x < 0. {
                velocity = velocity.max(0.);
            }
            if x > 1. {
                velocity = velocity.min(0.);
            }
        }
        state[0] = if dock != 0 { x } else { x.clamp(0., 1.) };
        state[1] = velocity;
        let moving = (state[0] - target).abs() > (if dock != 0 { 0.00008 } else { 0.0001 }) || velocity.abs() > 0.001;
        if !moving {
            state[0] = target;
            state[1] = 0.;
        }
        moving as u8
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_MotionKernel_albumPose(
    env: Env,
    _: JObject,
    array: JObject,
    p: f32,
    cx: f32,
    cy: f32,
    width: f32,
    height: f32,
) {
    unsafe {
        if array_length(env, array) != 5 || width <= 0. || height <= 0. {
            return;
        }
        let Some(mut pin) = Critical::<f32>::get(env, array, 5) else {
            return;
        };
        let zoom = (1.15 / width.min(height)).max(1.5).powf(p);
        let pan = |center: f32| (center * (1. - zoom) + (0.5 - center) * p).clamp(1. - zoom, 0.);
        pin.slice().copy_from_slice(&[zoom, pan(cx), pan(cy), (p / 0.91).min(1.), smooth((p - 0.84) / 0.16)]);
    }
}

// Frame packet: one edge x per row, then (column, lane, inset, dx, dy) per live group.
// At most eight columns are in flight even at the last scan step.
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_MotionKernel_scan(
    env: Env,
    _: JObject,
    array: JObject,
    rows: i32,
    progress: f32,
) -> i32 {
    const COLUMNS: i32 = 32;
    if !(8..=80).contains(&rows) {
        return 0;
    }
    let len = rows as usize + 8 * 3 * 5;
    unsafe {
        if array_length(env, array) < len {
            return 0;
        }
        let Some(mut pin) = Critical::<f32>::get(env, array, len) else {
            return 0;
        };
        let out = pin.slice();
        let travel = progress * (31. + 0.9 + 5.5);
        let first = (31 - travel.floor() as i32).clamp(0, 31);
        for row in 0..rows {
            let key = (row * COLUMNS + first + 1) as u32;
            let hash = key.wrapping_mul(0x45d9f3b);
            let lane = ((hash ^ (hash >> 16)).wrapping_mul(0x45d9f3b) >> 8) % 3;
            let before = travel - (31 - first) as f32 <= lane as f32 * 0.45;
            out[row as usize] = (first + i32::from(before)) as f32;
        }
        let last = ((31. - travel + 5.5 + 0.9) as i32).min(31);
        let mut used = rows as usize;
        for column in first..=last {
            for lane in 0..3 {
                let t = (travel - (31 - column) as f32 - lane as f32 * 0.45) / 5.5;
                if t <= 0. || t >= 1. {
                    continue;
                }
                let drift = smooth(t);
                out[used..used + 5].copy_from_slice(&[
                    column as f32,
                    lane as f32,
                    0.5 * drift,
                    drift * (2.6 + lane as f32 * 0.75),
                    drift * ((lane as f32 - 1.) * 1.8 + t * 0.65),
                ]);
                used += 5;
            }
        }
        used as i32
    }
}

/// Controls register unshifted rectangles in root pixels. Propagate displacement
/// from the bottom upwards only between horizontally intersecting controls.
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_MotionKernel_clearance(
    env: Env, _: JObject, array: JObject, gap: f32, ceiling: f32,
) {
    unsafe {
        let len = array_length(env, array);
        if len < 4 || (len - 4) % 5 != 0 { return; }
        let Some(mut held) = Critical::<f32>::get(env, array, len) else { return };
        let (bar, controls) = held.slice().split_at_mut(4);
        let mut order: Vec<_> = (0..controls.len()).step_by(5).collect();
        order.sort_unstable_by(|&a,&b| controls[b+3].total_cmp(&controls[a+3]));
        for (index, &i) in order.iter().enumerate() {
            let (left, top, right, bottom) = (controls[i], controls[i+1], controls[i+2], controls[i+3]);
            if left >= right || top >= bottom { continue; }
            let mut limit = bottom;
            if left < bar[2] && right > bar[0] && top < bar[3] {
                limit = limit.min(bar[1] - gap);
            }
            for &j in &order[..index] {
                if controls[j+4] > 0. && left < controls[j+2] && right > controls[j]
                    && bottom <= controls[j+1]+1. {
                    limit = limit.min(controls[j+1] - controls[j+4] - gap.min((controls[j+1]-bottom).max(0.)));
                }
            }
            controls[i+4] = (bottom-limit).clamp(0.,(top-ceiling).max(0.));
        }
    }
}

/// Quarter turn around the avatar. State carries angular velocity through a navigation reversal.
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_MotionKernel_backDock(
    env: Env, _: JObject, array: JObject, horizontal: u8, seconds: f32,
) -> u8 {
    unsafe {
        if array_length(env,array) != 5 { return 0; }
        let Some(mut held) = Critical::<f32>::get(env,array,5) else { return 0 };
        let s = held.slice();
        let target = if horizontal != 0 { 1. } else { 0. };
        let dt = seconds.clamp(0.,0.064);
        let offset = s[0]-target;
        let tangent = s[1]+15.*offset;
        let decay = (-15.*dt).exp();
        s[0] = target+(offset+tangent*dt)*decay;
        s[1] = (s[1]-15.*tangent*dt)*decay;
        let moving = (s[0]-target).abs()>0.0001 || s[1].abs()>0.001;
        if !moving { s[0]=target; s[1]=0.; }
        let angle = s[0].clamp(0.,1.)*std::f32::consts::FRAC_PI_2;
        s[2]=angle.sin(); s[3]=angle.cos().max(0.); s[4]=90.-angle.to_degrees();
        moving as u8
    }
}
