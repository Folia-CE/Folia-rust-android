// Only the owning instance reaches this material. UVs span the square cover in both maps.
uniform sampler2D u_lyrics_mask;
uniform vec4 u_lyrics_rect;
vec3 printLyricsChip(vec2 uv, vec3 artwork, float sideDp) {
    vec2 p = (uv - u_lyrics_rect.xy) / u_lyrics_rect.zw;
    if (any(lessThan(p, vec2(0.))) || any(greaterThan(p, vec2(1.)))) return artwork;
    vec2 size = u_lyrics_rect.zw;
    float radius = size.y * .5;
    vec2 q = abs((p - .5) * size) - vec2(size.x * .5 - radius, 0.);
    float edge = length(max(q, 0.)) - radius;
    float aa = max(.0001, fwidth(edge));
    if (edge > aa) return artwork;
    // Gaussian taps on the sleeve itself. 20dp is converted through the projected cover size.
    float stepUv = min(1., 20. / max(sideDp, 1.));
    vec3 blur = vec3(0.);
    for (int y = -2; y <= 2; ++y) {
        for (int x = -2; x <= 2; ++x) {
            vec2 tap = vec2(float(x), float(y));
            blur += coverSample(clamp(uv + tap * stepUv, 0., 1.)) * exp(-.5 * dot(tap, tap));
        }
    }
    vec3 frost = mix(blur / 6.168924, mix(vec3(.98), vec3(.11), u_dark), .68);
    float ink = texture(u_lyrics_mask, p).a;
    frost = mix(frost, mix(vec3(.10), vec3(.97), u_dark), ink);
    frost = mix(frost, vec3(1.), smoothstep(-size.y*.036-aa, -size.y*.036+aa, edge) * mix(.40,.18,u_dark));
    return mix(artwork, frost, 1.-smoothstep(-aa, aa, edge));
}
