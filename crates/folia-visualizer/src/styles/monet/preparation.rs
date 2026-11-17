// Neighboring lines can be shaped and rasterized before the scroll that reveals them.
// Only the surface thread installs completed layouts and GPU textures.
use super::rail::{Ctx, Measured, Rail, measure};
use super::{
    rail_gpu::{CachedMask, Rows},
    text,
};
use crate::{MonetAppearance, Typography};
use folia_core::Line;
use std::{
    collections::{HashSet, VecDeque},
    sync::Arc,
    sync::mpsc::{self, Receiver, SyncSender, TrySendError},
};

struct Job {
    line: Line,
    measured: Option<Arc<Measured>>,
    cached: HashSet<u64>,
    active: bool,
    key: u64,
    generation: u64,
    density: f32,
    width: f32,
    lyric_px: f32,
    translation_px: f32,
    typography: Typography,
    colors: Vec<crate::monet_appearance::WordColor>,
    keywords: bool,
}
struct Ready {
    key: u64,
    generation: u64,
    measured: Arc<Measured>,
    masks: Vec<CachedMask>,
}

impl Job {
    fn run(self) -> Ready {
        let theme = MonetAppearance { word_colors: self.colors, typography: self.typography, ..Default::default() };
        let ctx = Ctx {
            theme: &theme,
            keywords: self.keywords,
            typography: self.typography,
            lines: std::slice::from_ref(&self.line),
            lyric_px: self.lyric_px,
            translation_px: self.translation_px,
            inactive_scale: 1.,
            width: self.width,
            height: 0.,
        };
        let measured = self.measured.unwrap_or_else(|| Arc::new(measure(&self.line, &ctx)));
        let masks = measured.masks(&self.line, self.typography, self.lyric_px, self.density, self.active, &self.cached);
        Ready { key: self.key, generation: self.generation, measured, masks }
    }
}

pub(super) struct Preparation {
    submit: SyncSender<Job>,
    receive: Receiver<Ready>,
    generation: u64,
    anchor: Option<(usize, Option<isize>)>,
    plan: VecDeque<(usize, u64)>,
    nearby: HashSet<u64>,
    prepared: HashSet<u64>,
    pending: HashSet<u64>,
}
impl Preparation {
    pub fn new() -> Option<Self> {
        let (submit, work) = mpsc::sync_channel::<Job>(1);
        let (complete, receive) = mpsc::channel();
        std::thread::Builder::new()
            .name("Monet upcoming lines".into())
            .spawn(move || {
                if !text::prepare_fonts() {
                    return;
                }
                while let Ok(job) = work.recv() {
                    if complete.send(job.run()).is_err() {
                        break;
                    }
                }
            })
            .ok()?;
        Some(Self {
            submit,
            receive,
            generation: 0,
            anchor: None,
            plan: Default::default(),
            nearby: Default::default(),
            prepared: Default::default(),
            pending: Default::default(),
        })
    }

    pub fn busy(&self) -> bool {
        !self.plan.is_empty() || !self.pending.is_empty()
    }

    pub fn update(
        &mut self,
        ctx: &Ctx<'_>,
        anchor: usize,
        direction: Option<isize>,
        rows: &mut Rows,
        layouts: &mut Vec<(u64, Arc<Measured>)>,
    ) {
        use std::hash::{DefaultHasher, Hash, Hasher};
        let mut hash = DefaultHasher::new();
        (
            direction.is_some(),
            ctx.lines.as_ptr(),
            ctx.lines.len(),
            ctx.typography.fingerprint(),
            ctx.keywords,
            &ctx.theme.word_colors,
            [rows.density(), ctx.width, ctx.lyric_px, ctx.translation_px].map(f32::to_bits),
        )
            .hash(&mut hash);
        let generation = hash.finish();
        if self.generation != generation {
            self.prepared.clear();
            self.pending.clear();
            self.anchor = None;
            self.generation = generation;
        }
        if self.anchor != Some((anchor, direction)) {
            self.anchor = Some((anchor, direction));
            self.plan.clear();
            self.nearby.clear();
            // An automatic window reaches two rows either side, manual browsing reaches four.
            let offsets: &[isize] =
                if direction.is_some() { &[3, 4, 5, 6, 2, 1, 0, -1, -2, -3, -4] } else { &[1, 2, 3, 4, 0, -1, -2] };
            for &step in offsets {
                let offset = step * direction.unwrap_or(1);
                if let Some(i) = anchor.checked_add_signed(offset).filter(|&i| i < ctx.lines.len()) {
                    let key = Rail::measure_key(ctx, i);
                    self.nearby.insert(key);
                    if !self.prepared.contains(&key) && !self.pending.contains(&key) {
                        self.plan.push_back((i, key));
                    }
                }
            }
            self.prepared.retain(|key| self.nearby.contains(key));
        }
        for ready in self.receive.try_iter() {
            if ready.generation != self.generation {
                continue;
            }
            self.pending.remove(&ready.key);
            if !self.nearby.contains(&ready.key) {
                continue;
            }
            self.prepared.insert(ready.key);
            if !layouts.iter().any(|r| r.0 == ready.key) {
                if layouts.len() >= 32 {
                    layouts.remove(0);
                }
                layouts.push((ready.key, ready.measured));
            }
            for mask in ready.masks {
                rows.preload(mask);
            }
        }
        if self.pending.len() >= 2 || rows.defer_upload {
            return;
        }
        if let Some(&(i, key)) = self.plan.front() {
            let job = Job {
                line: ctx.lines[i].clone(),
                active: direction.is_none(),
                measured: layouts.iter().find(|r| r.0 == key).map(|r| r.1.clone()),
                cached: rows.cached_keys(),
                key,
                generation,
                density: rows.density(),
                width: ctx.width,
                lyric_px: ctx.lyric_px,
                translation_px: ctx.translation_px,
                typography: ctx.typography,
                keywords: ctx.keywords,
                colors: ctx.theme.word_colors.clone(),
            };
            match self.submit.try_send(job) {
                Ok(()) => {
                    self.pending.insert(key);
                    self.plan.pop_front();
                }
                Err(TrySendError::Full(_)) => {}
                Err(TrySendError::Disconnected(_)) => {
                    self.plan.clear();
                }
            }
        }
    }
}
