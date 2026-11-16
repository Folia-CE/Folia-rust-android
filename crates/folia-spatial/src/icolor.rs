//! IColor.extractClusters (com.lab.hv.effect.IColor) on raw pixels: three weighted colour
//! clusters of a cover / MV frame. Every expression keeps the Java one's f32 operations and
//! order, so the clusters (and the background drawn from them) come out bit for bit the same.

/// IColor.meanColor: the whole picture's average colour by area, rounded to 0..255.
fn mean_color(samples: &[Sample]) -> [i32; 3] {
    let mut sum_r = 0f32;
    let mut sum_g = 0f32;
    let mut sum_b = 0f32;
    let mut sum_w = 0f32;
    for s in samples {
        let w = s.weight;
        sum_r += red(s.color) as f32 * w;
        sum_g += green(s.color) as f32 * w;
        sum_b += blue(s.color) as f32 * w;
        sum_w += w;
    }
    if sum_w <= 0.0 {
        return [0x55, 0x55, 0x55];
    }
    [
        clamp_i((sum_r / sum_w + 0.5) as i32, 0, 255),
        clamp_i((sum_g / sum_w + 0.5) as i32, 0, 255),
        clamp_i((sum_b / sum_w + 0.5) as i32, 0, 255),
    ]
}

/// IColor.pickNearestToMean: the pixel closest to the average colour, first on ties.
fn pick_nearest_to_mean(samples: &[Sample]) -> usize {
    let mean = mean_color(samples);
    let mut best = i32::MAX;
    let mut best_idx = 0usize;
    for (i, s) in samples.iter().enumerate() {
        let dr = red(s.color) - mean[0];
        let dg = green(s.color) - mean[1];
        let db = blue(s.color) - mean[2];
        let d = dr * dr + dg * dg + db * db;
        if d < best {
            best = d;
            best_idx = i;
        }
    }
    best_idx
}

/// IColor.MEAN_SHARE_KEEP.
const MEAN_SHARE_KEEP: f32 = 4.0;

/// IColor.blendTowardMean: colours covering under a quarter of the picture lean to its average.
fn blend_toward_mean(samples: &[Sample], centers: &mut [Cluster; 3]) {
    let mean = mean_color(samples);
    let mut total = 0f32;
    for s in samples {
        total += s.weight;
    }
    if total <= 0.0 {
        return;
    }
    for c in centers.iter_mut() {
        let t = clamp_f(c.weight / total * MEAN_SHARE_KEEP, 0.0, 1.0);
        let mix = |m: i32, v: i32| clamp_i((m as f32 + (v - m) as f32 * t + 0.5) as i32, 0, 255);
        c.color = rgb(
            mix(mean[0], red(c.color)),
            mix(mean[1], green(c.color)),
            mix(mean[2], blue(c.color)),
        );
    }
}

#[inline]
fn red(c: u32) -> i32 {
    ((c >> 16) & 0xFF) as i32
}
#[inline]
fn green(c: u32) -> i32 {
    ((c >> 8) & 0xFF) as i32
}
#[inline]
fn blue(c: u32) -> i32 {
    (c & 0xFF) as i32
}
#[inline]
fn alpha(c: u32) -> i32 {
    ((c >> 24) & 0xFF) as i32
}

/// (float) Math.sqrt(double): the f32 sum widened, rooted in f64, rounded back.
#[inline]
fn sqrt_f(x: f32) -> f32 {
    (x as f64).sqrt() as f32
}

#[inline]
fn spatial_distance(ax: f32, ay: f32, bx: f32, by: f32) -> f32 {
    let dx = ax - bx;
    let dy = ay - by;
    sqrt_f(dx * dx + dy * dy)
}

#[inline]
fn color_distance01(a: u32, b: u32) -> f32 {
    let dr = (red(a) - red(b)) as f32 / 255.0;
    let dg = (green(a) - green(b)) as f32 / 255.0;
    let db = (blue(a) - blue(b)) as f32 / 255.0;
    sqrt_f(dr * dr + dg * dg + db * db)
}

#[derive(Clone, Copy)]
struct Sample {
    x: f32,
    y: f32,
    color: u32,
    weight: f32,
}

#[derive(Clone, Copy)]
pub struct Cluster {
    pub x: f32,
    pub y: f32,
    pub radius: f32,
    pub weight: f32,
    pub color: u32,
}

fn build_samples(pixels: &[u32], w: usize, h: usize) -> Vec<Sample> {
    let mut out = Vec::with_capacity(w * h);
    for y in 0..h {
        let ny = if h <= 1 { 0.5 } else { y as f32 / (h - 1) as i32 as f32 };
        for x in 0..w {
            let c = pixels[y * w + x];
            let a = alpha(c);
            if a < 20 {
                continue;
            }
            // By area (and opacity) alone, dark and grey pixels included: IColor.buildSamples.
            let weight = a as f32 / 255.0;
            let sx = if w <= 1 { 0.5 } else { x as f32 / (w - 1) as i32 as f32 };
            out.push(Sample { x: sx, y: ny, color: c, weight });
        }
    }
    if out.is_empty() {
        out.push(Sample { x: 0.5, y: 0.35, color: 0xFF555555, weight: 1.0 });
    }
    out
}

fn pick_strongest(samples: &[Sample], exclude_a: i32, exclude_b: i32) -> usize {
    let mut best = -1.0f32;
    let mut best_idx = 0usize;
    for (i, s) in samples.iter().enumerate() {
        let ii = i as i32;
        if ii == exclude_a || ii == exclude_b {
            continue;
        }
        let score = s.weight;
        if score > best {
            best = score;
            best_idx = i;
        }
    }
    best_idx
}

fn pick_farthest_weighted(samples: &[Sample], ref_a: i32, ref_b: i32) -> usize {
    let mut best = -1.0f32;
    let mut best_idx = pick_strongest(samples, ref_a, ref_b);
    let n = samples.len() as i32;
    let a = if ref_a >= 0 && ref_a < n { Some(samples[ref_a as usize]) } else { None };
    let b = if ref_b >= 0 && ref_b < n { Some(samples[ref_b as usize]) } else { None };
    for (i, s) in samples.iter().enumerate() {
        let ii = i as i32;
        if ii == ref_a || ii == ref_b {
            continue;
        }
        let mut dist = 1.0f32;
        if let Some(a) = a {
            dist *= 0.55 + spatial_distance(s.x, s.y, a.x, a.y) * 1.25;
        }
        if let Some(b) = b {
            dist *= 0.70 + spatial_distance(s.x, s.y, b.x, b.y) * 1.10;
        }
        let score = s.weight * dist;
        if score > best {
            best = score;
            best_idx = i;
        }
    }
    best_idx
}

fn nearest_center(s: &Sample, centers: &[Cluster; 3]) -> usize {
    let mut best = f32::MAX;
    let mut best_idx = 0usize;
    for (i, c) in centers.iter().enumerate() {
        let spatial = spatial_distance(s.x, s.y, c.x, c.y);
        let color = color_distance01(s.color, c.color);
        let score = color * 0.72 + spatial * 0.28;
        if score < best {
            best = score;
            best_idx = i;
        }
    }
    best_idx
}

#[inline]
fn clamp_i(v: i32, min: i32, max: i32) -> i32 {
    if v < min {
        return min;
    }
    if v > max {
        return max;
    }
    v
}

/// IColor.clamp(float): below min → min, above max → max, else v (NaN passes through).
#[inline]
fn clamp_f(v: f32, min: f32, max: f32) -> f32 {
    if v < min {
        return min;
    }
    if v > max {
        return max;
    }
    v
}

#[inline]
fn rgb(r: i32, g: i32, b: i32) -> u32 {
    0xFF00_0000 | ((r as u32) << 16) | ((g as u32) << 8) | (b as u32)
}

/// The clusters, sorted as IColor sorts them, with the empty-cluster stand-ins filled in.
pub fn extract_clusters(pixels: &[u32], w: usize, h: usize) -> [Cluster; 3] {
    let samples = build_samples(pixels, w, h);
    let first = pick_nearest_to_mean(&samples);
    let second = pick_farthest_weighted(&samples, first as i32, -1);
    let third = pick_farthest_weighted(&samples, first as i32, second as i32);
    let picks = [first, second, third];
    let mut centers = [Cluster { x: 0.0, y: 0.0, radius: 0.0, weight: 0.0, color: 0 }; 3];
    for i in 0..3 {
        let mut idx = picks[i];
        if idx >= samples.len() {
            idx = 0;
        }
        let s = samples[idx];
        centers[i] = Cluster { x: s.x, y: s.y, color: s.color, weight: s.weight, radius: 0.30 };
    }

    for _ in 0..5 {
        let mut sum_x = [0f32; 3];
        let mut sum_y = [0f32; 3];
        let mut sum_r = [0f32; 3];
        let mut sum_g = [0f32; 3];
        let mut sum_b = [0f32; 3];
        let mut sum_w = [0f32; 3];
        let mut sum_dist = [0f32; 3];
        for s in &samples {
            let idx = nearest_center(s, &centers);
            let w = s.weight;
            sum_x[idx] += s.x * w;
            sum_y[idx] += s.y * w;
            sum_r[idx] += red(s.color) as f32 * w;
            sum_g[idx] += green(s.color) as f32 * w;
            sum_b[idx] += blue(s.color) as f32 * w;
            sum_w[idx] += w;
        }
        for i in 0..3 {
            if sum_w[i] <= 0.0 {
                continue;
            }
            centers[i].x = sum_x[i] / sum_w[i];
            centers[i].y = sum_y[i] / sum_w[i];
            centers[i].color = rgb(
                clamp_i((sum_r[i] / sum_w[i] + 0.5) as i32, 0, 255),
                clamp_i((sum_g[i] / sum_w[i] + 0.5) as i32, 0, 255),
                clamp_i((sum_b[i] / sum_w[i] + 0.5) as i32, 0, 255),
            );
            centers[i].weight = sum_w[i];
        }
        for s in &samples {
            let idx = nearest_center(s, &centers);
            let dx = s.x - centers[idx].x;
            let dy = s.y - centers[idx].y;
            let d = sqrt_f(dx * dx + dy * dy);
            sum_dist[idx] += d * s.weight;
        }
        for i in 0..3 {
            if centers[i].weight <= 0.0 {
                centers[i].radius = 0.34;
            } else {
                centers[i].radius = clamp_f((sum_dist[i] / centers[i].weight) * 2.2 + 0.16, 0.18, 0.42);
            }
        }
    }

    // sortClusters: the same pairwise swaps in the same order.
    for i in 0..2 {
        for j in (i + 1)..3 {
            let wi = centers[i].weight;
            let wj = centers[j].weight;
            if wj > wi {
                centers.swap(i, j);
            }
        }
    }
    if centers[1].weight <= 0.0 {
        let src = centers[0];
        centers[1] = Cluster { x: 0.72, y: 0.26, radius: 0.28, weight: src.weight * 0.55, color: src.color };
    }
    if centers[2].weight <= 0.0 {
        let src = centers[0];
        centers[2] = Cluster { x: 0.34, y: 0.74, radius: 0.26, weight: src.weight * 0.55, color: src.color };
    }
    blend_toward_mean(&samples, &mut centers);
    centers
}

/// One bounded RGB histogram, independent of the area/mean-biased background clusters.
/// Keep the actual album hue; reject isolated pixels instead of inventing saturation.
pub fn cover_accent(pixels:&[i32])->u32 {
    let mut bins=[[0u64;4];512];let mut total=0u64;
    for &pixel in pixels{let c=pixel as u32;let a=(c>>24)as u64;if a<20{continue}
        let r=(c>>16)&255;let g=(c>>8)&255;let b=c&255;
        let at=((r>>5)*64+(g>>5)*8+(b>>5))as usize;
        let bin=&mut bins[at];bin[0]+=a;bin[1]+=r as u64*a;bin[2]+=g as u64*a;bin[3]+=b as u64*a;total+=a;
    }
    if total==0{return 0xff555555}
    let minimum=(total/200).max(1);let mut best=-1f32;let mut result=0xff555555;
    for bin in bins{if bin[0]<minimum{continue}
        let r=(bin[1]/bin[0])as u32;let g=(bin[2]/bin[0])as u32;let b=(bin[3]/bin[0])as u32;
        let hi=r.max(g).max(b)as f32/255.;let lo=r.min(g).min(b)as f32/255.;
        let chroma=hi-lo;let saturation=chroma/hi.max(0.001);let share=bin[0]as f32/total as f32;
        let score=chroma*saturation*(0.75+0.25*share.sqrt())+share*0.015;
        if score>best{best=score;result=0xff000000|(r<<16)|(g<<8)|b;}
    }
    result
}

#[cfg(test)]mod accent_tests{
    use super::*;
    #[test]fn small_vivid_region_survives_neutral_background_and_outliers(){
        let mut pixels=vec![0xff808080u32 as i32;4096];
        pixels[..256].fill(0xffdf4b28u32 as i32);pixels[256]=0xff00ff00u32 as i32;
        assert_eq!(cover_accent(&pixels),0xffdf4b28);
        pixels[257]=0x000000ff;assert_eq!(cover_accent(&pixels),0xffdf4b28);
    }
    #[test]fn neutral_and_empty_art_do_not_get_an_invented_hue(){
        assert_eq!(cover_accent(&[0xff969696u32 as i32;32]),0xff969696);
        assert_eq!(cover_accent(&[]),0xff555555);
    }
}
