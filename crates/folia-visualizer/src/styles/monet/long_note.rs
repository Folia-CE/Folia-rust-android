// NowPlayingLyricsTickerView.rebuildKaraokeGlyphLiftTimings and resolveKaraokeGlyphLongNoteGlowStrength.
// Times are integer milliseconds, including the source's floor when one token spans multiple code points.
use super::rail::{Span, Token};
use icu_properties::{
    CodePointMapData,
    props::{GeneralCategory, GeneralCategoryGroup, Script},
};
use std::ops::Range;

pub(super) struct Hold {
    pub bytes: Range<usize>,
    pub start: i64,
    pub gap: i64,
    pub letters: usize,
}

fn word(c: char) -> bool {
    let category = CodePointMapData::<GeneralCategory>::new().get(c);
    category == GeneralCategory::DecimalNumber
        || (GeneralCategoryGroup::Letter.contains(category)
            && matches!(CodePointMapData::<Script>::new().get(c), Script::Latin | Script::Greek | Script::Cyrillic))
}
fn continuation(c: char) -> bool {
    word(c) || GeneralCategoryGroup::Mark.contains(CodePointMapData::<GeneralCategory>::new().get(c))
}
fn java_whitespace(c: char) -> bool {
    matches!(c, '\u{1c}'..='\u{1f}')
        || (c.is_whitespace() && !matches!(c, '\u{85}' | '\u{a0}' | '\u{2007}' | '\u{202f}'))
}

pub(super) struct Letter {
    pub byte: usize,
    pub start: i64,
    pub end: i64,
}
pub(super) struct Timeline {
    pub holds: Vec<Hold>,
    pub letters: Vec<Letter>,
}

impl Timeline {
    pub fn new(text: &str, rows: &[Span], tokens: &[Token], timed: bool) -> Self {
        let mut letters = Vec::new();
        let mut holds = Vec::new();
        if !timed {
            return Self { holds, letters };
        }
        let timed: Vec<_> = tokens.iter().filter_map(|t| Some((t, (t.timing?.0 * 1000.).round() as i64))).collect();
        for (i, &(t, start)) in timed.iter().enumerate() {
            let next = timed.get(i + 1).map(|x| x.1 - start).filter(|x| *x > 0);
            let previous = i.checked_sub(1).map(|j| start - timed[j].1).filter(|x| *x > 0);
            let duration = next.or(previous).unwrap_or(180);
            let chars = || text[t.start..t.end].char_indices().filter(|(_, c)| !java_whitespace(*c));
            let n = chars().count().max(1) as i64;
            for (j, (b, _)) in chars().enumerate() {
                letters.push(Letter {
                    byte: t.start + b,
                    start: start + duration * j as i64 / n,
                    end: start + duration * (j + 1) as i64 / n,
                });
            }
        }
        for row in rows {
            let chars: Vec<_> = text[row.start..row.end].char_indices().collect();
            let at = |i: usize| chars.get(i).map_or(row.end, |(b, _)| row.start + b);
            let mut i = 0;
            while i < chars.len() {
                let first = i;
                let c = chars[i].1;
                i += 1;
                if word(c) {
                    while i < chars.len() {
                        let cp = chars[i].1;
                        let join = "'’ʼ-‐‑./&".contains(cp) && chars.get(i + 1).is_some_and(|x| continuation(x.1));
                        if !(continuation(cp) || join) {
                            break;
                        }
                        i += 1;
                    }
                    while i < chars.len() && "!\"%),.:;?]}¡¿’”…、。！，：；？｝］".contains(chars[i].1)
                    {
                        i += 1;
                    }
                    while i < chars.len() && (chars[i].1.is_whitespace() || java_whitespace(chars[i].1)) {
                        i += 1;
                    }
                }
                let bytes = at(first)..at(i);
                let a = letters.partition_point(|p| p.byte < bytes.start);
                let b = letters.partition_point(|p| p.byte < bytes.end);
                if a < b
                    && let Some(next) = letters.get(b)
                {
                    let start = letters[a].start;
                    let gap = next.start - start;
                    if gap > 900 {
                        holds.push(Hold { bytes, start, gap, letters: i - first });
                    }
                }
            }
        }
        Self { holds, letters }
    }
}

fn smoother(t: f32) -> f32 {
    let t = t.clamp(0., 1.);
    t * t * t * (t * (t * 6. - 15.) + 10.)
}

impl Hold {
    pub fn strength(&self, ms: i64) -> f32 {
        let elapsed = ms - self.start;
        if elapsed <= 0 || self.gap <= 900 {
            return 0.;
        }
        if elapsed <= self.gap {
            let rise = ((self.gap as f32 * 0.22).round()).clamp(180., 420.);
            let p = elapsed as f32 / self.gap as f32;
            let onset = smoother(elapsed as f32 / rise);
            let coupling = 0.76 + (0.94 - 0.76) * smoother(p).clamp(0., 1.);
            return (onset * coupling * (1. + 0.06 * (std::f64::consts::PI * f64::from(p)).sin() as f32)).clamp(0., 1.);
        }
        (0.94 * (1. - smoother((elapsed - self.gap) as f32 / 580.))).clamp(0., 1.)
    }

    pub fn offset(&self, ms: i64, room: f32) -> f32 {
        let usual = 4.5 * (self.gap as f32 / 900.).sqrt().min(2.5);
        let apex = if self.letters > 1 { usual.max((usual * 1.2).min(room)) } else { usual };
        let elapsed = ms - self.start;
        let release = elapsed - self.gap;
        let bounce = apex / usual;
        if release <= 0 {
            2. + (-apex - 2.) * smoother(elapsed as f32 / self.gap as f32)
        } else if release < 360 {
            -apex + (bounce + apex) * smoother(release as f32 / 360.)
        } else {
            bounce * (1. - smoother((release - 360) as f32 / 220.))
        }
    }
}
