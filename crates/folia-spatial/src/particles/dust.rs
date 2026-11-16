//! A cover turning to powder and blown away by the wind.
//!
//! The cover is cut into small cells, each carrying a few grains of powder that take the colours
//! of the picture where they lie. The wind reaches the cover from one side: a ragged front sweeps
//! across it, and as it passes a cell the picture there gives way to its grains (a short cross-fade,
//! so the hand-over cannot be seen). Freed grains are taken up by the wind, each at its own pace
//! (lighter ones answer it sooner), lifted a little, and carried along swirling eddies; they shrink
//! to fine dust, pale toward ash, and fade out.
//!
//! Space: the cover's own pixels ("units"), `unit` being one dp in them. Time in seconds.

/// Grains per cell.
pub const PER_CELL: usize = 3;
/// Each grain is a quad of 4 vertices, indexed with 16-bit indices: at most 65 536 vertices.
pub const MAX_GRAINS: usize = 16_000;

/// A cell edge on screen, in dp.
const CELL_DP: f32 = 2.8;
const MAX_CELLS_ACROSS: f32 = 72.0;

/// The wind front: when it reaches the cover, how long it takes to cross it, and how ragged it is.
const START_S: f32 = 0.04;
const SWEEP_S: f32 = 0.5;
const RAG_S: f32 = 0.14;
/// The picture and the grains cross-fade over this long as a cell gives way.
const HANDOVER_S: f32 = 0.06;

/// The wind (dp/s): its steady speed, its eddies, the lift it gives, and the grains' own jitter.
const WIND_DP: f32 = 460.0;
const EDDY_DP: f32 = 150.0;
const LIFT_DP: f32 = 60.0;
const JITTER_DP: f32 = 34.0;
/// How long the wind takes to get a freed grain fully moving.
const TAKE_UP_S: f32 = 0.22;
/// A freed grain leaves already moving: this share of its wind, and of its lift.
const PUSH: f32 = 0.28;

/// The pale ash the dust turns toward as it thins out.
const ASH: [f32; 3] = [217.0, 211.0, 203.0];

struct Rng(u64);

impl Rng {
    fn new(seed: u64) -> Rng {
        Rng(seed ^ 0x9E37_79B9_7F4A_7C15 | 1)
    }

    fn next(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.0 = x;
        x.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    /// Uniform in [0, 1).
    fn f(&mut self) -> f32 {
        (self.next() >> 40) as f32 / (1u64 << 24) as f32
    }
}

struct Cell {
    x0: f32,
    y0: f32,
    x1: f32,
    y1: f32,
    release: f32,
}

struct Grain {
    x: f32,
    y: f32,
    vx: f32,
    vy: f32,
    release: f32,
    life: f32,
    /// How quickly it answers the wind (s).
    tau: f32,
    /// Its share of the wind's speed.
    gust: f32,
    /// Its size as it leaves the picture, and as fine dust.
    size0: f32,
    size1: f32,
    /// Straight colour, 0–255.
    r: f32,
    g: f32,
    b: f32,
    a: f32,
    alive: bool,
}

/// One term of the eddies' stream function: amp · sin(kx·x + ky·y + w·t + phase).
struct Eddy {
    kx: f32,
    ky: f32,
    w: f32,
    phase: f32,
    amp: f32,
}

pub struct Dust {
    cells: Vec<Cell>,
    grains: Vec<Grain>,
    eddies: [Eddy; 3],
    unit: f32,
    wind_x: f32,
    wind_y: f32,
    rng: Rng,
    time: f32,
    last_release: f32,
}

fn smooth(v: f32) -> f32 {
    let t = v.clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

fn hash(ix: i32, iy: i32, seed: u32) -> f32 {
    let mut h = (ix as u32).wrapping_mul(0x8DA6_B343) ^ (iy as u32).wrapping_mul(0xD816_3841) ^ seed.wrapping_mul(0xCB1A_B31F);
    h ^= h >> 13;
    h = h.wrapping_mul(0x5BD1_E995);
    h ^= h >> 15;
    (h & 0xFFFF) as f32 / 65535.0
}

/// Smooth value noise in [0, 1].
fn value_noise(x: f32, y: f32, seed: u32) -> f32 {
    let ix = x.floor();
    let iy = y.floor();
    let fx = smooth(x - ix);
    let fy = smooth(y - iy);
    let (ix, iy) = (ix as i32, iy as i32);
    let a = hash(ix, iy, seed);
    let b = hash(ix + 1, iy, seed);
    let c = hash(ix, iy + 1, seed);
    let d = hash(ix + 1, iy + 1, seed);
    let top = a + (b - a) * fx;
    let bottom = c + (d - c) * fx;
    top + (bottom - top) * fy
}

impl Dust {
    /// `pixels`: the cover drawn small (`sw` × `sh`, ARGB, transparent outside its outline),
    /// stretched over `width` × `height` units. `wind_x`, `wind_y`: where the wind blows (any length).
    pub fn new(pixels: &[u32], sw: usize, sh: usize, width: f32, height: f32, unit: f32,
               wind_x: f32, wind_y: f32, seed: u64) -> Dust {
        let mut rng = Rng::new(seed);
        let unit = unit.max(0.01);
        let norm = (wind_x * wind_x + wind_y * wind_y).sqrt().max(1e-4);
        let (wx, wy) = (wind_x / norm, wind_y / norm);
        let across = (width.max(height) / (CELL_DP * unit)).round().clamp(12.0, MAX_CELLS_ACROSS);
        let edge = width.max(height).max(1.0) / across;
        let cols = (width / edge).round().max(1.0) as usize;
        let rows = (height / edge).round().max(1.0) as usize;
        let cw = width / cols as f32;
        let ch = height / rows as f32;

        // The front's progress along the wind: 0 at the windward edge, 1 at the far one.
        let corners = [(0.0, 0.0), (width, 0.0), (0.0, height), (width, height)];
        let mut pmin = f32::MAX;
        let mut pmax = f32::MIN;
        for (x, y) in corners {
            let p = x * wx + y * wy;
            pmin = pmin.min(p);
            pmax = pmax.max(p);
        }
        let span = (pmax - pmin).max(1.0);
        let noise_seed = (seed as u32) ^ 0x51ED_270B;
        let ragged = 26.0 * unit;

        let sample = |x: f32, y: f32| -> u32 {
            if sw == 0 || sh == 0 || pixels.len() < sw * sh {
                return 0;
            }
            let px = ((x / width.max(1.0)) * sw as f32).floor().clamp(0.0, (sw - 1) as f32) as usize;
            let py = ((y / height.max(1.0)) * sh as f32).floor().clamp(0.0, (sh - 1) as f32) as usize;
            pixels[py * sw + px]
        };

        let mut cells = Vec::with_capacity(cols * rows);
        let mut grains = Vec::with_capacity((cols * rows * PER_CELL).min(MAX_GRAINS));
        let mut last_release = 0.0f32;
        for j in 0..rows {
            for i in 0..cols {
                if grains.len() + PER_CELL > MAX_GRAINS {
                    break;
                }
                let x0 = i as f32 * cw;
                let y0 = j as f32 * ch;
                let cx = x0 + cw * 0.5;
                let cy = y0 + ch * 0.5;
                let progress = ((cx * wx + cy * wy) - pmin) / span;
                // Ragged on a broad scale only: fine noise here would free lone cells ahead of the
                // front and leave pinholes in the picture.
                let rag = 0.85 * value_noise(cx / ragged, cy / ragged, noise_seed)
                    + 0.15 * value_noise(cx / (ragged * 0.45), cy / (ragged * 0.45), noise_seed ^ 0xA5A5);
                let release = (START_S + SWEEP_S * progress + RAG_S * (rag - 0.5) + 0.01 * rng.f()).max(START_S);
                let first = grains.len();
                for _ in 0..PER_CELL {
                    let x = x0 + cw * rng.f();
                    let y = y0 + ch * rng.f();
                    let argb = sample(x, y);
                    let a = (argb >> 24) as f32;
                    if a < 8.0 {
                        continue;
                    }
                    let light = rng.f();
                    let size0 = cw.max(ch) * 0.82 * (0.9 + 0.2 * rng.f());
                    let grain_release = release + 0.045 * rng.f();
                    last_release = last_release.max(grain_release);
                    grains.push(Grain {
                        x,
                        y,
                        vx: 0.0,
                        vy: 0.0,
                        release: grain_release,
                        life: 0.9 + 0.6 * rng.f(),
                        tau: 0.08 + 0.3 * light * light.sqrt(),
                        gust: 0.55 + 0.9 * rng.f(),
                        size0,
                        size1: size0 * (0.3 + 0.36 * rng.f()),
                        r: ((argb >> 16) & 0xFF) as f32,
                        g: ((argb >> 8) & 0xFF) as f32,
                        b: (argb & 0xFF) as f32,
                        a,
                        alive: true,
                    });
                }
                if grains.len() > first {
                    cells.push(Cell { x0, y0, x1: x0 + cw, y1: y0 + ch, release });
                }
            }
        }

        // Three eddies of different sizes, speeds and headings; together their swirl reaches about
        // EDDY_DP. The velocity of amp·sin(k·x) as a stream function is amp·|k|.
        let tau = std::f32::consts::TAU;
        let mut eddy = |wavelength_dp: f32, weight: f32, hz: f32| {
            let k = tau / (wavelength_dp * unit);
            let heading = tau * rng.f();
            Eddy {
                kx: k * heading.cos(),
                ky: k * heading.sin(),
                w: tau * hz * if rng.f() < 0.5 { -1.0 } else { 1.0 },
                phase: tau * rng.f(),
                amp: EDDY_DP * unit * weight / k,
            }
        };
        let eddies = [eddy(78.0, 0.55, 0.45), eddy(130.0, 0.35, 0.25), eddy(38.0, 0.22, 0.9)];
        // A freed grain leaves already moving along the wind, so the picture never seems to sit
        // there pixelated before it goes.
        let wind = WIND_DP * unit;
        for grain in &mut grains {
            grain.vx = wx * wind * grain.gust * PUSH;
            grain.vy = wy * wind * grain.gust * PUSH - LIFT_DP * unit * PUSH;
        }
        Dust { cells, grains, eddies, unit, wind_x: wx, wind_y: wy, rng, time: 0.0, last_release }
    }

    pub fn cell_count(&self) -> usize {
        self.cells.len()
    }

    pub fn grain_count(&self) -> usize {
        self.grains.len()
    }

    /// Moves the wind on by `dt` (capped, so a stalled frame never throws the dust). False once
    /// the last grain is gone.
    pub fn step(&mut self, dt: f32) -> bool {
        let dt = dt.clamp(0.0, 1.0 / 30.0);
        self.time += dt;
        let t = self.time;
        let unit = self.unit;
        let wind = WIND_DP * unit;
        let lift = LIFT_DP * unit;
        let jitter = JITTER_DP * unit * dt.sqrt();
        let mut alive = false;
        for i in 0..self.grains.len() {
            let grain = &mut self.grains[i];
            if !grain.alive {
                continue;
            }
            let age = t - grain.release;
            if age <= 0.0 {
                alive = true;
                continue;
            }
            if age >= grain.life {
                grain.alive = false;
                continue;
            }
            alive = true;
            // The eddies: the curl of their stream function, so they swirl without gathering.
            let (mut dpx, mut dpy) = (0.0f32, 0.0f32);
            for e in &self.eddies {
                let c = (e.kx * grain.x + e.ky * grain.y + e.w * t + e.phase).cos() * e.amp;
                dpx += e.kx * c;
                dpy += e.ky * c;
            }
            let taken = smooth(age / TAKE_UP_S);
            // The further it has been carried, the more the eddies have it.
            let swirl = 0.3 + 0.7 * taken + 0.6 * smooth(age / 0.9);
            let target_x = self.wind_x * wind * grain.gust * taken + dpy * swirl;
            let target_y = self.wind_y * wind * grain.gust * taken - dpx * swirl - lift * taken;
            let k = 1.0 - (-dt / grain.tau).exp();
            let jx = (self.rng.f() - 0.5) * jitter;
            let jy = (self.rng.f() - 0.5) * jitter;
            grain.vx += (target_x - grain.vx) * k + jx;
            grain.vy += (target_y - grain.vy) * k + jy;
            grain.x += grain.vx * dt;
            grain.y += grain.vy * dt;
        }
        alive || t < self.last_release
    }

    /// The quads for this moment. `mask`: the cells that have given way (black, their alpha how
    /// far), to cut out of the picture. `dust`: the grains. Each quad is 4 vertices (x, y), clockwise
    /// from top-left, with one ARGB colour per vertex. Returns (mask quads, dust quads, whether
    /// the whole picture has given way).
    pub fn fill(&self, mask_xy: &mut [f32], mask_argb: &mut [i32], dust_xy: &mut [f32],
                dust_argb: &mut [i32]) -> (usize, usize, bool) {
        let t = self.time;
        let seam = 0.6;
        let mut whole = true;
        let mut m = 0usize;
        for cell in &self.cells {
            let gone = smooth((t - (cell.release - HANDOVER_S)) / HANDOVER_S);
            if gone < 1.0 {
                whole = false;
            }
            if gone <= 0.0 {
                continue;
            }
            if (m + 1) * 8 > mask_xy.len() || (m + 1) * 4 > mask_argb.len() {
                whole = false;
                break;
            }
            let color = (((gone * 255.0).round() as u32) << 24) as i32;
            quad(&mut mask_xy[m * 8..m * 8 + 8], cell.x0 - seam, cell.y0 - seam, cell.x1 + seam, cell.y1 + seam);
            for v in 0..4 {
                mask_argb[m * 4 + v] = color;
            }
            m += 1;
        }
        let mut d = 0usize;
        for grain in &self.grains {
            if !grain.alive {
                continue;
            }
            let shown = smooth((t - (grain.release - HANDOVER_S)) / HANDOVER_S);
            if shown <= 0.0 {
                continue;
            }
            let age = (t - grain.release).max(0.0);
            let lived = age / grain.life;
            let fade = 1.0 - smooth((lived - 0.3) / 0.7);
            let alpha = grain.a * shown * fade;
            if alpha < 1.0 {
                continue;
            }
            if (d + 1) * 8 > dust_xy.len() || (d + 1) * 4 > dust_argb.len() {
                break;
            }
            let size = (grain.size0 + (grain.size1 - grain.size0) * smooth(age / 0.2)) * (1.0 - 0.3 * smooth(lived));
            let ash = 0.3 * smooth(lived);
            let r = grain.r + (ASH[0] - grain.r) * ash;
            let g = grain.g + (ASH[1] - grain.g) * ash;
            let b = grain.b + (ASH[2] - grain.b) * ash;
            let color = (((alpha.round() as u32).min(255) << 24)
                | ((r.round() as u32).min(255) << 16)
                | ((g.round() as u32).min(255) << 8)
                | (b.round() as u32).min(255)) as i32;
            let half = size * 0.5;
            quad(&mut dust_xy[d * 8..d * 8 + 8], grain.x - half, grain.y - half, grain.x + half, grain.y + half);
            for v in 0..4 {
                dust_argb[d * 4 + v] = color;
            }
            d += 1;
        }
        (m, d, whole)
    }
}

fn quad(out: &mut [f32], left: f32, top: f32, right: f32, bottom: f32) {
    out[0] = left;
    out[1] = top;
    out[2] = right;
    out[3] = top;
    out[4] = right;
    out[5] = bottom;
    out[6] = left;
    out[7] = bottom;
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cover(size: usize, round: bool) -> Vec<u32> {
        let mut pixels = vec![0u32; size * size];
        for y in 0..size {
            for x in 0..size {
                let dx = x as f32 + 0.5 - size as f32 * 0.5;
                let dy = y as f32 + 0.5 - size as f32 * 0.5;
                if round && dx * dx + dy * dy > (size as f32 * 0.5).powi(2) {
                    continue;
                }
                pixels[y * size + x] = 0xFF00_0000 | ((x as u32 * 2) << 16) | ((y as u32 * 2) << 8) | 0x40;
            }
        }
        pixels
    }

    #[test]
    fn blows_away_and_ends() {
        for &round in &[false, true] {
            // A 184 dp cover at 3× density.
            let pixels = cover(120, round);
            let mut dust = Dust::new(&pixels, 120, 120, 552.0, 552.0, 3.0, 1.0, -0.3, 7);
            assert!(dust.grain_count() <= MAX_GRAINS);
            assert!(dust.grain_count() > 8000, "grains {}", dust.grain_count());
            let mut mask_xy = vec![0f32; dust.cell_count() * 8];
            let mut mask_argb = vec![0i32; dust.cell_count() * 4];
            let mut dust_xy = vec![0f32; dust.grain_count() * 8];
            let mut dust_argb = vec![0i32; dust.grain_count() * 4];
            let mut frames = 0;
            let mut whole_at = None;
            let start = std::time::Instant::now();
            while dust.step(1.0 / 60.0) {
                let (_, _, whole) = dust.fill(&mut mask_xy, &mut mask_argb, &mut dust_xy, &mut dust_argb);
                if whole && whole_at.is_none() {
                    whole_at = Some(frames);
                }
                frames += 1;
                assert!(frames < 600, "never ends");
            }
            let per_frame = start.elapsed().as_secs_f64() * 1e6 / frames as f64;
            println!("round {} grains {} frames {} picture gone at {:?} {:.0} µs/frame",
                     round, dust.grain_count(), frames, whole_at, per_frame);
            assert!(whole_at.is_some());
            assert!(frames > 60 && frames < 150);
        }
    }
}
