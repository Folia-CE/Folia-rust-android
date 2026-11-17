// Host replay of the Android Monet passes. Each sample ends after GPU completion.
// Android's Skia callback and SurfaceFlinger are outside this measurement.
use folia_core::{AudioFrame, Line, Lyrics, Theme, Word};
use folia_visualizer::{MonetAppearance, Scene, SceneState, Stage, Style, Values, preview_lyrics, styles::monet};
use iced_core::{Font, Pixels, Rectangle, Renderer as _, Size, mouse};
use iced_wgpu::{
    graphics::{Shell, Viewport, geometry::Renderer as _},
    primitive::Renderer as _,
};
use iced_widget::canvas::Program as _;
use std::time::Instant;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    tokio::runtime::Builder::new_current_thread().build()?.block_on(replay())
}

// Consecutive unique bilingual rows expose work hidden by a cached four-line loop.
fn scrolling_lyrics() -> Lyrics {
    let lines = (0..108)
        .map(|i| {
            let start = f64::from(i);
            let words: Vec<_> = [
                "雨后的站台 ",
                "月光经过 ",
                "长街尽头 ",
                "你还记得 ",
                "那一班车 ",
                "和窗外的风 ",
                "第",
                &format!("{i}幕"),
            ]
            .into_iter()
            .enumerate()
            .map(|(j, text)| Word {
                text: text.to_owned(),
                start: start + j as f64 / 8.,
                end: start + (j + 1) as f64 / 8.,
            })
            .collect();
            Line {
                start,
                end: start + 1.,
                text: words.iter().map(|w| w.text.as_str()).collect(),
                words,
                translation: Some(format!("第{i}幕　雨上がりのホームで、振り返るたびに違う風景が見える。")),
                ..Default::default()
            }
        })
        .collect();
    Lyrics { lines, word_timed: true, ..Default::default() }
}

async fn replay() -> Result<(), Box<dyn std::error::Error>> {
    let instance = wgpu::Instance::new(&Default::default());
    let adapter = instance.request_adapter(&Default::default()).await?;
    let (dev, queue) = adapter.request_device(&Default::default()).await?;
    let format = wgpu::TextureFormat::Bgra8Unorm;
    let size = Size::new(1200, 2670);
    let viewport = Viewport::with_physical_size(size, 3.);
    let bounds = Rectangle::with_size(viewport.logical_size());
    let target = dev
        .create_texture(&wgpu::TextureDescriptor {
            label: None,
            size: wgpu::Extent3d { width: size.width, height: size.height, depth_or_array_layers: 1 },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format,
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
            view_formats: &[],
        })
        .create_view(&Default::default());
    let engine = iced_wgpu::Engine::new(&adapter, dev.clone(), queue, format, None, Shell::headless());
    let mut renderer = iced_wgpu::Renderer::new(engine, Font::DEFAULT, Pixels(16.));
    let scrolling = std::env::args().any(|arg| arg == "--scroll");
    let mut state = SceneState::default();
    let lyrics = if scrolling { scrolling_lyrics() } else { preview_lyrics() };
    let appearance = MonetAppearance { shell_capsule: true, ..Default::default() };
    let filter: monet::MaskFilter = std::sync::Arc::new(|_, _, _, _| None);
    let mut samples: [[Vec<f64>; 3]; 3] = Default::default();
    let tuning = Values::default();
    let manual_start = 60 * 32;
    for frame in 0..if scrolling { manual_start + 384 } else { 270 } {
        let manual = scrolling && frame >= manual_start;
        let time = if manual { 40.5 } else { 2.0 + f64::from(frame) / 60. };
        let start = Instant::now();
        let audio = AudioFrame::breath(time * 1000.);
        let stage = Stage::<monet::Event>::new(Scene {
            monet: &appearance,
            style: Style::Monet,
            lyrics: &lyrics,
            time,
            theme: &Theme::DAYLIGHT,
            font_scale: 1.,
            seed: "",
            stage: None,
            tuning: std::borrow::Cow::Borrowed(&tuning),
            backdrop: false,
            title: "神文之诗".into(),
            artist: "Folia".into(),
            album: "Album".into(),
            portrait: None,
            preview: false,
            audio: &audio,
        });
        if manual && frame % 8 == 0 {
            let direction = if (frame - manual_start) / 128 % 2 == 0 { -1. } else { 1. };
            let wheel = mouse::Event::WheelScrolled { delta: mouse::ScrollDelta::Pixels { x: 0., y: 76. * direction } };
            let _ = stage.update(
                &mut state,
                &iced_widget::canvas::Event::Mouse(wheel),
                bounds,
                mouse::Cursor::Available(iced_core::Point::new(190., 490.)),
            );
        }
        renderer.reset(bounds);
        state.begin_monet_rail(3., filter.clone());
        for geometry in stage.draw(&state, &renderer, &iced_core::Theme::Light, bounds, mouse::Cursor::Unavailable) {
            renderer.draw_geometry(geometry);
        }
        if let Some(rail) = state.take_monet_rail() {
            renderer.draw_primitive(bounds, rail);
        }
        let overlay = folia_render::Program::<()>::new(folia_render::Atmosphere {
            time: time as f32,
            grain: 0.04,
            vignette: 0.,
            glow_center: [0.; 2],
            glow_radius: [0.; 2],
            glow_rgb: [0.; 3],
            glow_alpha: 0.,
        });
        renderer.draw_primitive(
            bounds,
            iced_widget::shader::Program::draw(&overlay, &(), mouse::Cursor::Unavailable, bounds),
        );
        let draw = start.elapsed().as_secs_f64();
        let submission =
            renderer.present(Some(folia_visualizer::color(Theme::DAYLIGHT.background, 1.)), format, &target, &viewport);
        let submit = start.elapsed().as_secs_f64();
        dev.poll(wgpu::PollType::Wait { submission_index: Some(submission), timeout: None })?;
        if frame >= 60 && (!manual || frame > manual_start + 8) {
            let group = if manual && frame % 8 == 0 {
                2
            } else if scrolling && !manual && frame % 60 < 2 {
                1
            } else {
                0
            };
            for (list, value) in samples[group].iter_mut().zip([draw, submit - draw, start.elapsed().as_secs_f64()]) {
                list.push(value * 1000.);
            }
        }
    }
    for (phase, group) in ["other frames", "auto boundary", "wheel step"].into_iter().zip(samples) {
        for (name, mut times) in ["scene", "encode+submit", "complete"].into_iter().zip(group) {
            if times.is_empty() {
                continue;
            }
            times.sort_by(f64::total_cmp);
            println!(
                "{phase} {name}: median {:.2} ms, p95 {:.2} ms",
                times[times.len() / 2],
                times[times.len() * 95 / 100]
            );
        }
    }
    Ok(())
}
