// The quad is cropped before rasterization. All distances are logical pixels.
struct Input {
    @location(0) screen: vec4<f32>,
    @location(1) clip: vec4<f32>,
    @location(2) source: vec4<f32>,
    @location(3) color: vec4<f32>,
    @location(4) fade: vec4<f32>,
    @location(5) effect: vec4<f32>,
    @location(6) pending: vec4<f32>,
    @location(7) atlas: vec4<f32>,
}
struct Out {
    @builtin(position) position: vec4<f32>,
    @location(0) point: vec2<f32>,
    @location(1) @interpolate(flat) source: vec4<f32>,
    @location(2) @interpolate(flat) color: vec4<f32>,
    @location(3) @interpolate(flat) fade: vec4<f32>,
    @location(4) @interpolate(flat) effect: vec4<f32>,
    @location(5) @interpolate(flat) pending: vec4<f32>,
    @location(6) uv: vec2<f32>,
    @location(7) @interpolate(flat) ink_weight: f32,
    @location(8) @interpolate(flat) atlas: vec4<f32>,
}
@group(0) @binding(0) var coverage: texture_2d<f32>;
@group(0) @binding(1) var filtering: sampler;

@vertex fn vs(@builtin(vertex_index) index: u32, item: Input) -> Out {
    let corners = array(vec2(0.,0.), vec2(1.,0.), vec2(0.,1.), vec2(0.,1.), vec2(1.,0.), vec2(1.,1.));
    let corner = corners[index];
    let p = item.clip.xy + corner * item.clip.zw;
    let inv = 1. / max(item.source.zw, vec2(.00001));
    let src = vec4(item.source.xy, select(inv * item.atlas.zw, inv, item.effect.x >= 4.));
    let shift = select(item.effect.z, 0., item.effect.x == 3.);
    let uv = item.atlas.xy + (p - vec2(0., shift) - src.xy) * inv * item.atlas.zw;
    let ink_weight = 1. + .7 * (1. - smoothstep(.1, .5, dot(item.color.rgb, vec3(.2126,.7152,.0722))));
    return Out(vec4(item.screen.xy + corner * item.screen.zw, 0., 1.),
        p, src, item.color, item.fade, item.effect, item.pending, uv, ink_weight, vec4(item.atlas.xy,item.atlas.xy+item.atlas.zw));
}

// Clamp linear filtering to this glyph rectangle, even beside another occupied slot.
const HALF_TEXEL = vec2(.5/2048.);
fn ink_at(uv:vec2<f32>, bounds:vec4<f32>) -> f32 {
    return textureSampleLevel(coverage, filtering, clamp(uv,bounds.xy+HALF_TEXEL,bounds.zw-HALF_TEXEL),0.).r;
}
fn sample_row(uv:vec2<f32>, bounds:vec4<f32>) -> f32 {
    return select(0.,ink_at(uv,bounds), all(uv>=bounds.xy) && all(uv<=bounds.zw));
}

const RINGS = array(
    vec2(0.52020138,0.46839142),
    vec2(-0.14553818,0.68470332),
    vec2(-0.66573956,0.21631190),
    vec2(-0.52020138,-0.46839142),
    vec2(0.14553818,-0.68470332),
    vec2(0.66573956,-0.21631190),
    vec2(0.00000000,1.50000000),
    vec2(-1.29903811,0.75000000),
    vec2(-1.29903811,-0.75000000),
    vec2(-0.00000000,-1.50000000),
    vec2(1.29903811,-0.75000000),
    vec2(1.29903811,0.75000000)
);

@fragment fn fs(input: Out) -> @location(0) vec4<f32> {
    let p = input.point;
    let src = input.source;
    let fx = input.effect;
    if fx.x == 5. {
        let radius = length((p - src.xy) * src.zw * 2. - 1.);
        let levels = array(input.fade.x,input.fade.y,input.fade.z,input.fade.w,
            input.pending.x,input.pending.y,input.pending.z,input.pending.w,fx.y,fx.z,fx.w,input.color.a);
        let t = clamp((1.-radius)*12., 0., 12.);
        let band = min(u32(t),11u);
        let prev = select(0., levels[max(1u,band)-1u], band>0u);
        let aa = max(fwidth(radius)*12., .001);
        let coverage = smoothstep(-aa*.5,aa*.5,(1.-radius)*12.-f32(band));
        return vec4(input.color.rgb, mix(prev,levels[band],coverage));
    }
    if fx.x == 4. {
        let at = clamp((p.x - src.x) * src.z, 0., 1.);
        return vec4(input.color.rgb, input.color.a * (.94 - .44 * abs(at - .5)));
    }
    var alpha: f32;
    var tint = input.color;
    if fx.x == 3. {
        tint = mix(input.pending, input.color, clamp((fx.y-p.x)/fx.z,0.,1.));
    }
    {
        let y = (p.y - input.fade.x) / input.fade.y;
        let fade = clamp(min(y / .11, (1.-y) / .12), 0., 1.) *
            clamp((input.fade.z - p.y) * input.fade.w, 0., 1.);
        let a = tint.a * fade;
        if a <= 0. { return vec4(0.); }
        if fx.x == 3. {
            alpha = a * ink_at(input.uv,input.atlas);
        } else if fx.x == 0. {
            alpha = a * pow(ink_at(input.uv,input.atlas), input.ink_weight);
        } else {
            let step = src.zw * fx.y;
            let guard = 1.5 * step + HALF_TEXEL;
            var trans: f32;
            // Inside the row all 13 bilinear footprints are guaranteed to stay in this slot.
            if all(input.uv >= input.atlas.xy + guard) && all(input.uv <= input.atlas.zw - guard) {
                trans = 1. - a * textureSampleLevel(coverage, filtering, input.uv, 0.).r;
                for (var i = 0u; i < 12u; i++) {
                    trans *= 1. - a * textureSampleLevel(coverage, filtering, input.uv - RINGS[i]*step, 0.).r;
                }
            } else {
                trans = 1. - a * sample_row(input.uv,input.atlas);
                for (var i = 0u; i < 12u; i++) {
                    trans *= 1. - a * sample_row(input.uv - RINGS[i]*step,input.atlas);
                }
            }
            alpha = 1.-trans;
        }
    }
    return vec4(tint.rgb, alpha);
}
