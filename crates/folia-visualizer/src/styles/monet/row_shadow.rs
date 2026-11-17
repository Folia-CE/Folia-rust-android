// Stable blurred rows are composited once, then sampled through the original moving rail mask.
// During changes of scale or opacity we draw the original passes until the new key repeats.
use super::{ink::Line, text::coverage};
use iced_core::{Color, Point, Rectangle, Vector, Font};
use iced_widget::{canvas::{Frame, Image}, image::Handle};
use std::{cell::RefCell, collections::VecDeque, rc::Rc};

#[derive(PartialEq)]
struct Key {
    text: String, size: f32, height: f32, font: Font, rgba: [u8;4], alpha: u32, offsets: Vec<(f32,f32)>,
}
pub(super) struct Stamp { image: Handle, origin: Vector, width: f32, height: f32, bytes: usize }
impl Stamp {
    pub fn draw(&self, frame: &mut Frame, at: Point, opacity: f32) {
        let rect = Rectangle::new(at + self.origin, iced_core::Size::new(self.width, self.height));
        frame.draw_image(rect, Image::new(self.image.clone()).opacity(opacity));
    }
}
#[derive(Default)]
struct Cache { entries: VecDeque<(Key, Option<Rc<Stamp>>)>, bytes: usize }
thread_local! { static CACHE: RefCell<Cache> = RefCell::default(); }

pub(super) fn lookup(line: Line<'_>, color: Color, offsets: &[(f32,f32)]) -> Option<Rc<Stamp>> {
    const LIMIT: usize = 12 * 1024 * 1024;
    CACHE.with_borrow_mut(|cache| {
        let rgba = color.into_rgba8();
        let index = cache.entries.iter().position(|(k,_)| k.text == line.content && k.size == line.px &&
            k.height == line.line_height && k.font == line.font && k.rgba == rgba && k.alpha == color.a.to_bits() && k.offsets == offsets);
        let entry = if let Some(i) = index {
            let (key, held) = cache.entries.remove(i)?;
            let image = held.or_else(|| {
                let stamp = Rc::new(bake(line, color, offsets)?);
                cache.bytes += stamp.bytes;
                Some(stamp)
            });
            (key,image)
        } else {
            (Key { text: line.content.to_owned(), size: line.px, height: line.line_height, font: line.font,
                rgba, alpha: color.a.to_bits(), offsets: offsets.to_vec() },None)
        };
        let result = entry.1.clone();
        cache.entries.push_back(entry);
        while cache.bytes > LIMIT || cache.entries.len() > 64 {
            if let Some((_, Some(old))) = cache.entries.pop_front() { cache.bytes -= old.bytes; }
        }
        result
    })
}

fn bake(line: Line<'_>, color: Color, offsets: &[(f32,f32)]) -> Option<Stamp> {
    // Only defocused passes use this atlas. Sharp lyrics and the moving highlight stay native text.
    const SCALE: f32 = 2.;
    let (mask,w,h,pad,_) = coverage(line.content,line.px,line.line_height,line.font,SCALE,None)?;
    let extent = offsets.iter().fold((0f32,0f32), |(x,y), &(dx,dy)| (x.max(dx.abs()),y.max(dy.abs())));
    let ex = (extent.0*SCALE).ceil() as usize;
    let ey = (extent.1*SCALE).ceil() as usize;
    let width = w as usize + 2*ex;
    let height = h as usize + 2*ey;
    let count = width.checked_mul(height)?;
    if count > 1024*1024 { return None; }
    let mut sum = vec![0f32; count];
    for &(dx,dy) in offsets {
        let x = (ex as isize + (dx*SCALE).round() as isize) as usize;
        let y = (ey as isize + (dy*SCALE).round() as isize) as usize;
        for (row, source) in mask.chunks_exact(w as usize).enumerate() {
            let start = (row+y)*width+x;
            for (dst, &v) in sum[start..start+w as usize].iter_mut().zip(source) {
                *dst += (1.-*dst) * (color.a/255.) * f32::from(v);
            }
        }
    }
    let [r,g,b,_] = color.into_rgba8();
    let pixels: Vec<_> = sum.into_iter().flat_map(|a| [r,g,b,(a*255.).round() as u8]).collect();
    Some(Stamp { image: Handle::from_rgba(width as u32,height as u32,pixels),
        origin: Vector::new(-(ex as f32+pad)/SCALE,-(ey as f32)/SCALE),width:width as f32/SCALE,
        height:height as f32/SCALE,bytes:count*4 })
}
