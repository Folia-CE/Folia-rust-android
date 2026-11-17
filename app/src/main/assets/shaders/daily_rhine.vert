#version 300 es
precision highp float;
// Depth and colour are separate linked programs; exact equality requires the
// same clip-position arithmetic, including under driver shader optimization.
invariant gl_Position;
layout(location=0) in vec3 a_position;
layout(location=1) in vec3 a_normal;
layout(location=2) in vec4 a_m0;
layout(location=3) in vec4 a_m1;
layout(location=4) in vec4 a_m2;
layout(location=5) in vec4 a_m3;
layout(location=6) in vec4 a_art;
layout(location=7) in vec4 a_info;
layout(location=8) in vec4 a_material;
layout(location=9) in vec4 a_emitter;
layout(location=10) in vec4 a_emitter_normal;
layout(location=11) in vec4 a_emitter_color;
layout(location=12) in vec4 a_emitter2;
layout(location=13) in vec4 a_emitter_normal2;
layout(location=14) in vec4 a_emitter_color2;
uniform vec2 u_screen;
uniform vec2 u_projection_offset;
out vec3 v_local;
out vec3 v_normal;
out vec3 v_position;
out vec3 v_local_normal;
flat out vec3 v_incident;
flat out vec4 v_art;
flat out vec4 v_info;
flat out float v_lyrics;
flat out vec4 v_material;
flat out float v_scale;
flat out vec4 v_emitter;
flat out vec4 v_emitter_normal;
flat out vec4 v_emitter_color;
flat out vec4 v_emitter2;
flat out vec4 v_emitter_normal2;
flat out vec4 v_emitter_color2;
void main(){
 v_emitter2=a_emitter2;v_emitter_normal2=a_emitter_normal2;v_emitter_color2=a_emitter_color2;
 mat4 pose=mat4(a_m0,a_m1,a_m2,a_m3);vec3 p=(pose*vec4(a_position,1.)).xyz;v_position=p;p.xy+=u_projection_offset;
 gl_Position=vec4(p.x/u_screen.x*2.-1.,1.-p.y/u_screen.y*2.,clamp(p.z/4000.,-.98,.98),1.);
 v_scale=length(a_m0.xyz);v_local=a_position;v_local_normal=a_normal;v_normal=normalize(mat3(pose)*a_normal);
 v_emitter=a_emitter;v_emitter_normal=a_emitter_normal;v_emitter_color=a_emitter_color;
 v_incident=vec3(a_m0.z,a_m1.z,a_m2.z)/max(v_scale,.001);v_art=a_art;v_info=a_info;v_lyrics=step(1.5,a_info.x);v_info.x-=2.*v_lyrics;v_material=a_material;
}
