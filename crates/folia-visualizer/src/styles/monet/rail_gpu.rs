// Android's lyric worker owns one Rows per song. Cache only glyph coverage: position, colour,
// opacity, spring scale, blur radius and timed-word clips are instance data and never invalidate it.
use super::{ink::Line, text::coverage};
use bytemuck::{Pod, Zeroable};
use iced_core::{Color, Font, Rectangle};
use iced_widget::shader;
use std::{
    cell::RefCell,
    collections::{HashMap, HashSet, VecDeque},
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
};

pub struct FilteredMask {
    pub pixels: Vec<u8>,
    pub width: u32,
    pub height: u32,
    pub left: i32,
    pub top: i32,
}
pub type MaskFilter = Arc<dyn Fn(Vec<u8>, u32, u32, f32) -> Option<FilteredMask> + Send + Sync>;
type BlurKey = (u64, u32, u32, u32);
type BlurJob = (BlurKey, Arc<Mask>);

struct Filters {
    jobs: std::sync::mpsc::SyncSender<BlurJob>,
    results: std::sync::mpsc::Receiver<(BlurKey, Option<FilteredMask>)>,
    pending: HashSet<BlurKey>,
    running: Arc<std::sync::atomic::AtomicBool>,
}
impl Filters {
    fn start(filter: MaskFilter) -> Option<Self> {
        let (jobs, requests) = std::sync::mpsc::sync_channel::<BlurJob>(16);
        let (replies, results) = std::sync::mpsc::channel();
        let running = Arc::new(std::sync::atomic::AtomicBool::new(true));
        let alive = running.clone();
        std::thread::Builder::new()
            .name("Monet glyph masks".into())
            .spawn(move || {
                while let Ok((key, mask)) = requests.recv() {
                    if !alive.load(Ordering::Relaxed) {
                        break;
                    }
                    let (_, x, end, radius) = key;
                    let pixels = mask
                        .pixels
                        .chunks_exact(mask.size[0] as usize)
                        .flat_map(|row| row[x as usize..end as usize].iter().copied())
                        .collect();
                    let blurred = filter(pixels, end - x, mask.size[1], f32::from_bits(radius));
                    if replies.send((key, blurred)).is_err() {
                        break;
                    }
                }
            })
            .ok()?;
        Some(Self { jobs, results, pending: HashSet::new(), running })
    }
}
impl Drop for Filters {
    fn drop(&mut self) {
        self.running.store(false, Ordering::Relaxed);
    }
}
static ID: AtomicU64 = AtomicU64::new(1);

#[derive(Debug)]
pub(super) struct Mask {
    key: u64,
    id: u64,
    pixels: Vec<u8>,
    size: [u32; 2],
    raster_scale: f32,
    pad: f32,
    ink: [f32; 4],
}
#[derive(Debug, Clone, Copy, Pod, Zeroable)]
#[repr(C)]
struct Instance {
    screen: [f32; 4],
    clip: [f32; 4],
    source: [f32; 4],
    color: [f32; 4],
    fade: [f32; 4],
    effect: [f32; 4],
    pending: [f32; 4],
    atlas: [f32; 4],
}
#[derive(Debug)]
struct Draw {
    mask: Arc<Mask>,
    instance: Instance,
}

#[derive(Default)]
pub(super) struct Rows {
    masks: VecDeque<CachedMask>,
    uploads: VecDeque<std::sync::Weak<Mask>>,
    pub(super) defer_upload: bool,
    bytes: usize,
    draws: Vec<Draw>,
    halos: Vec<Draw>,
    bars: Vec<Draw>,
    chorus: Vec<Draw>,
    density: f32,
    filters: Option<Filters>,
    invalid: VecDeque<BlurKey>,
    blurred: VecDeque<((u64, u32, u32, u32), Arc<Mask>, i32, i32)>,
    blur_bytes: usize,
}
type Shared = RefCell<Rows>;

pub(super) type CachedMask = (String, Font, f32, f32, Arc<Mask>);

pub(super) fn mask_key(s: &str, font: Font, px: f32, height: f32) -> u64 {
    use std::hash::{DefaultHasher, Hash, Hasher};
    let mut h = DefaultHasher::new();
    (s, font, px.to_bits(), height.to_bits()).hash(&mut h);
    h.finish()
}

pub(super) fn raster_mask(content: &str, font: Font, px: f32, height: f32, density: f32) -> Option<CachedMask> {
    let mut scale = density;
    let (pixels, w, h, pad) = loop {
        if let Some((pixels, w, h, pad, _)) = coverage(content, px, height, font, scale, None)
            && w <= 2048
            && h <= 2048
        {
            break (pixels, w, h, pad);
        }
        if scale <= 1. {
            return None;
        }
        scale = (scale / 2.).max(1.);
    };
    // Keep the full pen metrics for positioning, but rasterize fragments only around ink.
    // A one-texel guard includes the bilinear footprint on both sides.
    let mut ink = [w, h, 0, 0];
    for (y, row) in pixels.chunks_exact(w as usize).enumerate() {
        if let Some(left) = row.iter().position(|&a| a != 0) {
            ink[0] = ink[0].min(left.saturating_sub(1) as u32);
            ink[1] = ink[1].min(y.saturating_sub(1) as u32);
            ink[2] = ink[2].max((row.iter().rposition(|&a| a != 0).unwrap_or(left) as u32 + 2).min(w));
            ink[3] = (y as u32 + 2).min(h);
        }
    }
    let ink = [
        ink[0] as f32 / w as f32,
        ink[1] as f32 / h as f32,
        ink[2].saturating_sub(ink[0]) as f32 / w as f32,
        ink[3].saturating_sub(ink[1]) as f32 / h as f32,
    ];
    let mask = Arc::new(Mask {
        key: mask_key(content, font, px, height),
        id: ID.fetch_add(1, Ordering::Relaxed),
        pixels,
        size: [w, h],
        raster_scale: scale,
        pad,
        ink,
    });
    Some((content.to_owned(), font, px, height, mask))
}

impl Rows {
    pub fn begin(&mut self, density: f32, filter: MaskFilter) {
        if self.filters.is_none() {
            self.filters = Filters::start(filter);
        }
        self.finish_filters();
        let density = density.clamp(1., 4.);
        if self.density != density {
            self.masks.clear();
            self.uploads.clear();
            self.bytes = 0;
            self.blurred.clear();
            self.blur_bytes = 0;
            self.density = density;
        }
        self.draws.clear();
        self.halos.clear();
        self.bars.clear();
        self.chorus.clear();
    }

    fn mask(&mut self, pen: Line<'_>, px: f32, height: f32) -> Option<Arc<Mask>> {
        if let Some(i) =
            self.masks.iter().position(|(s, f, p, h, _)| s == pen.content && *f == pen.font && *p == px && *h == height)
        {
            let entry = self.masks.remove(i)?;
            let mask = entry.4.clone();
            self.masks.push_back(entry);
            return Some(mask);
        }
        let entry = raster_mask(pen.content, pen.font, px, height, self.density)?;
        let mask = entry.4.clone();
        self.insert_mask(entry);
        Some(mask)
    }

    pub fn density(&self) -> f32 {
        self.density
    }

    pub fn cached_keys(&self) -> HashSet<u64> {
        self.masks.iter().map(|row| row.4.key).collect()
    }

    pub fn preload(&mut self, entry: CachedMask) {
        if self.masks.iter().any(|r| r.0 == entry.0 && r.1 == entry.1 && r.2 == entry.2 && r.3 == entry.3) {
            return;
        }
        self.uploads.push_back(Arc::downgrade(&entry.4));
        self.insert_mask(entry);
    }

    fn insert_mask(&mut self, entry: CachedMask) {
        self.bytes += entry.4.pixels.len();
        self.masks.push_back(entry);
        while self.bytes > 12 * 1024 * 1024 || self.masks.len() > 96 {
            if let Some((_, _, _, _, old)) = self.masks.pop_front() {
                self.bytes -= old.pixels.len();
            }
        }
    }

    fn finish_filters(&mut self) {
        let Some(worker) = &mut self.filters else { return };
        while let Ok((key, result)) = worker.results.try_recv() {
            worker.pending.remove(&key);
            let Some(result) = result.filter(|r| {
                r.width > 0
                    && r.height > 0
                    && r.width <= 2048
                    && r.height <= 2048
                    && r.pixels.len() == r.width as usize * r.height as usize
            }) else {
                self.invalid.push_back(key);
                if self.invalid.len() > 96 {
                    self.invalid.pop_front();
                }
                continue;
            };
            let mask = Arc::new(Mask {
                key: 0,
                id: ID.fetch_add(1, Ordering::Relaxed),
                pixels: result.pixels,
                size: [result.width, result.height],
                raster_scale: 1.,
                pad: 0.,
                ink: [0., 0., 1., 1.],
            });
            self.blur_bytes += mask.pixels.len();
            self.blurred.push_back((key, mask, result.left, result.top));
            while self.blurred.len() > 96 || self.blur_bytes > 8 * 1024 * 1024 {
                if let Some((_, old, _, _)) = self.blurred.pop_front() {
                    self.blur_bytes -= old.pixels.len();
                }
            }
        }
    }

    fn filtered(&mut self, mask: &Arc<Mask>, x: u32, end: u32, radius: f32) -> Option<(Arc<Mask>, i32, i32)> {
        let key = (mask.id, x, end, radius.to_bits());
        if let Some(i) = self.blurred.iter().position(|entry| entry.0 == key) {
            let hit = self.blurred.remove(i)?;
            let result = (hit.1.clone(), hit.2, hit.3);
            self.blurred.push_back(hit);
            return Some(result);
        }
        let worker = self.filters.as_mut()?;
        if !worker.pending.contains(&key)
            && !self.invalid.contains(&key)
            && worker.jobs.try_send((key, mask.clone())).is_ok()
        {
            worker.pending.insert(key);
        }
        None
    }

    pub fn pending(&self) -> bool {
        !self.uploads.is_empty() || self.filters.as_ref().is_some_and(|f| !f.pending.is_empty())
    }

    fn white() -> Arc<Mask> {
        static WHITE: std::sync::OnceLock<Arc<Mask>> = std::sync::OnceLock::new();
        WHITE
            .get_or_init(|| {
                Arc::new(Mask {
                    key: 0,
                    id: 0,
                    pixels: vec![255],
                    size: [1, 1],
                    raster_scale: 1.,
                    pad: 0.,
                    ink: [0., 0., 1., 1.],
                })
            })
            .clone()
    }

    pub fn chorus(&mut self, area: Rectangle, color: Color, stops: &[(f32, f32)]) {
        let mut cumulative = 0.;
        let alpha = super::ink::radial_steps(color.a, stops).map(|(_, a)| {
            if a > 0.0005 {
                cumulative += (1. - cumulative) * a;
            }
            cumulative
        });
        if cumulative == 0. {
            return;
        }
        let rect = [area.x, area.y, area.width, area.height];
        self.chorus.push(Draw {
            mask: Self::white(),
            instance: Instance {
                atlas: [0.; 4],
                screen: [0.; 4],
                clip: rect,
                source: rect,
                color: [color.r, color.g, color.b, alpha[11]],
                fade: [alpha[0], alpha[1], alpha[2], alpha[3]],
                pending: [alpha[4], alpha[5], alpha[6], alpha[7]],
                effect: [5., alpha[8], alpha[9], alpha[10]],
            },
        });
    }

    pub fn spectrum_bar(&mut self, bar: Rectangle, strip: Rectangle, color: Color) {
        self.bars.push(Draw {
            mask: Self::white(),
            instance: Instance {
                atlas: [0.; 4],
                screen: [0.; 4],
                clip: [bar.x, bar.y, bar.width, bar.height],
                source: [strip.x, strip.y, strip.width, strip.height],
                color: [color.r, color.g, color.b, color.a],
                fade: [0.; 4],
                effect: [4., 0., 0., 0.],
                pending: [0.; 4],
            },
        });
    }

    pub fn take(&mut self) -> Option<Pass> {
        let warm = if self.defer_upload { None } else { self.uploads.pop_front().and_then(|w| w.upgrade()) };
        if self.draws.is_empty()
            && self.halos.is_empty()
            && self.bars.is_empty()
            && self.chorus.is_empty()
            && warm.is_none()
        {
            return None;
        }
        let mut draws = Vec::with_capacity(self.chorus.len() + self.bars.len() + self.halos.len() + self.draws.len());
        draws.append(&mut self.chorus);
        draws.append(&mut self.bars);
        draws.append(&mut self.halos);
        draws.append(&mut self.draws);
        Some(Pass(draws, warm))
    }
}

pub(super) struct Row<'a> {
    rows: &'a Shared,
    mask: Arc<Mask>,
    source: Rectangle,
    fade: [f32; 4],
    held_ranges: Vec<(f32, f32)>,
}

impl<'a> Row<'a> {
    pub fn new(
        rows: Option<&'a Shared>,
        pen: Line<'_>,
        base_px: f32,
        base_height: f32,
        rail: Rectangle,
        cut: Option<(f32, f32)>,
    ) -> Option<Self> {
        let rows = rows?;
        let mask = rows.borrow_mut().mask(pen, base_px, base_height)?;
        let zoom = pen.px / base_px / mask.raster_scale;
        let source = Rectangle {
            x: pen.at.x - mask.pad * zoom,
            y: pen.at.y,
            width: mask.size[0] as f32 * zoom,
            height: mask.size[1] as f32 * zoom,
        };
        let (end, inv) = cut.map_or((1e12, 1.), |(y, h)| (y, 1. / h.max(0.001)));
        Some(Self { rows, mask, source, held_ranges: Vec::new(), fade: [rail.y, rail.height.max(1.), end, inv] })
    }

    pub fn ink(&self, clip: Rectangle, color: Color, blur: f32, shift: f32) {
        let [x, y, w, h] = self.mask.ink;
        if w <= 0. || h <= 0. {
            return;
        }
        let shifted = Rectangle {
            x: self.source.x + x * self.source.width,
            y: self.source.y + shift + y * self.source.height,
            width: self.source.width * w,
            height: self.source.height * h,
        };
        let Some(bounds) = clip.intersection(&shifted.expand(blur * 1.5)) else { return };
        let mut start = bounds.x;
        let end = start + bounds.width;
        for &(left, right) in &self.held_ranges {
            if left >= end {
                break;
            }
            if right <= start {
                continue;
            }
            if start < left {
                self.ink_piece(bounds, start, left, color, blur, shift);
            }
            start = start.max(right);
        }
        if start < end {
            self.ink_piece(bounds, start, end, color, blur, shift);
        }
    }

    fn ink_piece(&self, bounds: Rectangle, start: f32, end: f32, color: Color, blur: f32, shift: f32) {
        self.push(
            false,
            Rectangle { x: start, width: end - start, ..bounds },
            self.source,
            color,
            [if blur > 0. { 1. } else { 0. }, blur, shift, 0.],
            color,
            &self.mask,
        );
    }

    pub fn density(&self) -> f32 {
        self.rows.borrow().density
    }

    pub fn held(
        &mut self,
        left: f32,
        right: f32,
        scale: f32,
        lift: f32,
        strength: f32,
        prepare_halo: bool,
        prewarm_only: bool,
        front: f32,
        feather: f32,
        pending: Color,
        revealed: Color,
        rail: Rectangle,
    ) {
        let zoom = self.source.width / self.mask.size[0] as f32;
        let texel = |x: f32| ((x - self.source.x) / zoom).round().clamp(0., self.mask.size[0] as f32) as u32;
        let (x, end) = (texel(left), texel(right));
        if x >= end {
            return;
        }
        let density = self.density();
        let effect = [3., front, feather, 0.];
        let (pending, revealed) = if front <= left {
            (pending, pending)
        } else if front >= right + feather {
            (revealed, revealed)
        } else {
            (pending, revealed)
        };
        if prepare_halo {
            for (radius, weight) in [(8.8, 0.40), (3.2, 0.55)] {
                let physical = ((radius * scale * density * 2.).round() * 0.5).max(0.5);
                // Cache on the half-pixel grid of the coverage mask, also when displayed downscaled.
                let kernel = ((physical / (zoom * density) * 2.).round() * 0.5).max(0.5);
                let result = self.rows.borrow_mut().filtered(&self.mask, x, end, kernel);
                if !prewarm_only
                    && weight * strength > 0.001
                    && let Some((blur, dx, dy)) = result
                {
                    let src = Rectangle {
                        x: self.source.x + (x as f32 + dx as f32) * zoom,
                        y: self.source.y + lift + dy as f32 * zoom,
                        width: blur.size[0] as f32 * zoom,
                        height: blur.size[1] as f32 * zoom,
                    };
                    if let Some(bounds) = src.intersection(&rail) {
                        let opacity = (strength * weight * 255.).round() / 255.;
                        self.push(
                            true,
                            bounds,
                            src,
                            Color { a: revealed.a * opacity, ..revealed },
                            effect,
                            Color { a: pending.a * opacity, ..pending },
                            &blur,
                        );
                    }
                }
            }
        }
        if prewarm_only {
            return;
        }
        self.held_ranges.push((left, right));
        let src = Rectangle { y: self.source.y + lift, ..self.source };
        if let Some(bounds) = rail.intersection(&Rectangle { x: left, width: right - left, ..src }) {
            self.push(false, bounds, src, revealed, effect, pending, &self.mask);
        }
    }

    fn push(
        &self,
        halo: bool,
        bounds: Rectangle,
        src: Rectangle,
        color: Color,
        effect: [f32; 4],
        pending: Color,
        mask: &Arc<Mask>,
    ) {
        if color.a <= 0.0005 {
            return;
        }
        let rect = |r: Rectangle| [r.x, r.y, r.width, r.height];
        let draw = Draw {
            mask: mask.clone(),
            instance: Instance {
                atlas: [0.; 4],
                screen: [0.; 4],
                clip: rect(bounds),
                source: rect(src),
                color: [color.r, color.g, color.b, color.a],
                fade: self.fade,
                effect,
                pending: [pending.r, pending.g, pending.b, pending.a],
            },
        };
        let mut rows = self.rows.borrow_mut();
        if halo { rows.halos.push(draw) } else { rows.draws.push(draw) }
    }
}

#[derive(Debug)]
pub struct Pass(Vec<Draw>, Option<Arc<Mask>>);
pub struct Pipeline {
    render: wgpu::RenderPipeline,
    layout: wgpu::BindGroupLayout,
    sampler: wgpu::Sampler,
    masks: HashMap<u64, Slot>,
    pages: Vec<Option<Page>>,
    batches: Vec<(usize, std::ops::Range<u32>)>,
    instances: wgpu::Buffer,
    staging: Vec<Instance>,
    srgb: bool,
}

// Every adapter used by the Android downlevel pipeline supports a 2048-square texture.
const ATLAS: u32 = 2048;
struct Slot {
    page: usize,
    alloc: etagere::AllocId,
    uv: [f32; 4],
    owner: std::sync::Weak<Mask>,
}
struct Page {
    allocator: etagere::AtlasAllocator,
    texture: wgpu::Texture,
    bind: wgpu::BindGroup,
}
impl Pipeline {
    fn page(&self, device: &wgpu::Device) -> Page {
        let texture = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("Monet R8 row atlas"),
            size: wgpu::Extent3d { width: ATLAS, height: ATLAS, depth_or_array_layers: 1 },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::R8Unorm,
            usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
            view_formats: &[],
        });
        let view = texture.create_view(&Default::default());
        let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("Monet row atlas"),
            layout: &self.layout,
            entries: &[
                wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(&view) },
                wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::Sampler(&self.sampler) },
            ],
        });
        Page { allocator: etagere::AtlasAllocator::new(etagere::size2(ATLAS as i32, ATLAS as i32)), texture, bind }
    }

    fn upload(&mut self, device: &wgpu::Device, queue: &wgpu::Queue, mask: &Arc<Mask>) {
        if self.masks.contains_key(&mask.id) {
            return;
        }
        let [w, h] = mask.size;
        let size = etagere::size2(w as i32, h as i32);
        let slot =
            self.pages.iter_mut().enumerate().find_map(|(i, page)| Some((i, page.as_mut()?.allocator.allocate(size)?)));
        let (index, alloc) = match slot {
            Some(slot) => slot,
            None => {
                let mut page = self.page(device);
                let Some(alloc) = page.allocator.allocate(size) else { return };
                let index = self.pages.iter().position(Option::is_none).unwrap_or(self.pages.len());
                if index == self.pages.len() {
                    self.pages.push(Some(page))
                } else {
                    self.pages[index] = Some(page)
                }
                (index, alloc)
            }
        };
        let origin = alloc.rectangle.min;
        let Some(page) = &self.pages[index] else { return };
        queue.write_texture(
            wgpu::TexelCopyTextureInfo {
                origin: wgpu::Origin3d { x: origin.x as u32, y: origin.y as u32, z: 0 },
                ..page.texture.as_image_copy()
            },
            &mask.pixels,
            wgpu::TexelCopyBufferLayout { offset: 0, bytes_per_row: Some(w), rows_per_image: None },
            wgpu::Extent3d { width: w, height: h, depth_or_array_layers: 1 },
        );
        let uv = [origin.x as f32, origin.y as f32, w as f32, h as f32].map(|n| n / ATLAS as f32);
        self.masks.insert(mask.id, Slot { page: index, alloc: alloc.id, uv, owner: Arc::downgrade(mask) });
    }

    fn reclaim(&mut self) {
        self.masks.retain(|_, slot| {
            if slot.owner.strong_count() > 0 {
                return true;
            }
            if let Some(page) = &mut self.pages[slot.page] {
                page.allocator.deallocate(slot.alloc);
            }
            false
        });
        for page in self.pages.iter_mut().skip(1) {
            if page.as_ref().is_some_and(|p| p.allocator.allocated_space() == 0) {
                *page = None;
            }
        }
    }
}

fn buffer(device: &wgpu::Device, size: u64) -> wgpu::Buffer {
    device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("Monet rail instances"),
        size,
        usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    })
}

impl shader::Pipeline for Pipeline {
    fn new(device: &wgpu::Device, _: &wgpu::Queue, format: wgpu::TextureFormat) -> Self {
        let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("Monet ink"),
            source: wgpu::ShaderSource::Wgsl(include_str!("rail.wgsl").into()),
        });
        let attributes = wgpu::vertex_attr_array![0 => Float32x4, 1 => Float32x4, 2 => Float32x4,
            3 => Float32x4, 4 => Float32x4, 5 => Float32x4,6 => Float32x4,7 => Float32x4];
        let render = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("Monet ink"),
            layout: None,
            vertex: wgpu::VertexState {
                module: &module,
                entry_point: Some("vs"),
                compilation_options: Default::default(),
                buffers: &[wgpu::VertexBufferLayout {
                    array_stride: size_of::<Instance>() as u64,
                    step_mode: wgpu::VertexStepMode::Instance,
                    attributes: &attributes,
                }],
            },
            fragment: Some(wgpu::FragmentState {
                module: &module,
                entry_point: Some("fs"),
                compilation_options: Default::default(),
                targets: &[Some(wgpu::ColorTargetState {
                    format,
                    blend: Some(wgpu::BlendState::ALPHA_BLENDING),
                    write_mask: wgpu::ColorWrites::ALL,
                })],
            }),
            primitive: Default::default(),
            depth_stencil: None,
            multisample: Default::default(),
            multiview: None,
            cache: None,
        });
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });
        Self {
            layout: render.get_bind_group_layout(0),
            staging: Vec::new(),
            render,
            sampler,
            masks: HashMap::new(),
            pages: Vec::new(),
            batches: Vec::new(),
            instances: buffer(device, 4096),
            srgb: format.is_srgb(),
        }
    }
}

impl shader::Primitive for Pass {
    type Pipeline = Pipeline;
    fn prepare(
        &self,
        gpu: &mut Pipeline,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        bounds: &Rectangle,
        _: &shader::Viewport,
    ) {
        gpu.staging.clear();
        gpu.batches.clear();
        gpu.reclaim();
        for mask in self.1.iter().chain(self.0.iter().map(|draw| &draw.mask)) {
            gpu.upload(device, queue, mask);
        }
        for draw in &self.0 {
            let Some(slot) = gpu.masks.get(&draw.mask.id) else { continue };
            let mut instance = draw.instance;
            instance.atlas = slot.uv;
            let at = gpu.staging.len() as u32;
            match gpu.batches.last_mut() {
                Some((page, range)) if *page == slot.page => range.end = at + 1,
                _ => gpu.batches.push((slot.page, at..at + 1)),
            }
            let [x, y, w, h] = instance.clip;
            instance.screen = [
                2. * x / bounds.width - 1.,
                1. - 2. * y / bounds.height,
                2. * w / bounds.width,
                -2. * h / bounds.height,
            ];
            if gpu.srgb {
                let [r, g, b, a] = instance.color;
                instance.color = Color::from_rgba(r, g, b, a).into_linear();
                let [r, g, b, a] = instance.pending;
                if instance.effect[0] != 5. {
                    instance.pending = Color::from_rgba(r, g, b, a).into_linear();
                }
            }
            gpu.staging.push(instance);
        }
        if gpu.staging.is_empty() {
            return;
        }
        let bytes = bytemuck::cast_slice(&gpu.staging);
        if gpu.instances.size() < bytes.len() as u64 {
            gpu.instances = buffer(device, bytes.len().next_power_of_two() as u64);
        }
        queue.write_buffer(&gpu.instances, 0, bytes);
    }
    fn draw(&self, gpu: &Pipeline, pass: &mut wgpu::RenderPass<'_>) -> bool {
        pass.set_pipeline(&gpu.render);
        pass.set_vertex_buffer(0, gpu.instances.slice(..));
        for (i, range) in &gpu.batches {
            if let Some(page) = &gpu.pages[*i] {
                pass.set_bind_group(0, &page.bind, &[]);
                pass.draw(0..6, range.clone());
            }
        }
        true
    }
}
