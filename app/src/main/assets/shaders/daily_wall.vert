#version 300 es
precision highp float;
layout(location=0) in vec2 a_uv;
layout(location=1) in vec4 a_rect;
layout(location=2) in vec4 a_info; // cover layer, lift owner, list index, image ready
layout(location=3) in vec4 a_preview;
layout(location=4) in vec4 a_material; // cached album RGB, Rust edge-detail visibility
uniform vec2 u_screen;
uniform vec2 u_center;
uniform vec2 u_radius;
uniform vec2 u_pan;
uniform float u_zoom;
uniform float u_mode;
uniform float u_header;
uniform float u_scroll;
uniform float u_layer;
uniform vec4 u_lift_rect;
uniform float u_lift_mix;
out vec2 v_uv;
out vec2 v_size;
out vec2 v_angles;
flat out vec4 v_info;
flat out vec4 v_preview;
flat out vec4 v_material;
out vec3 v_normal;
out float v_shade;
void main(){
 // Separate the selected card before filtering the retained background.
 if(abs(a_info.y-u_layer)>.5){gl_Position=vec4(2.,2.,2.,1.);return;}
 vec2 world=a_rect.xy+a_uv*a_rect.zw;
 vec2 angles=(world+u_pan)*u_zoom/u_radius;
 vec2 cs=cos(angles),sn=sin(angles);
 float depth=u_radius.x*(cs.x-1.0)+u_radius.y*(cs.y-1.0);
 float cameraW=(1500.0-depth)/1500.0;
 vec2 curved=u_center+u_radius*sn/cameraW;
 vec2 listPoint=vec2(42.0,u_header+8.0+a_info.z*72.0-u_scroll)+a_uv*54.0;
 vec2 screen=mix(listPoint,curved,u_mode);
 float w=mix(1.0,cameraW,u_mode);
 float lift=a_info.y*u_lift_mix;
 screen=mix(screen,u_lift_rect.xy+a_uv*u_lift_rect.zw,lift);
 w=mix(w,1.,clamp(lift,0.,1.));
 vec2 ndc=vec2(screen.x/u_screen.x*2.0-1.0,1.0-screen.y/u_screen.y*2.0);
 gl_Position=vec4(ndc*w,clamp(-depth/1800.0,0.0,.95)*u_mode*w,w);
 v_uv=a_uv;v_size=mix(vec2(54.0),a_rect.zw,u_mode);v_angles=angles;v_info=a_info;v_preview=a_preview;
 v_material=a_material;
 v_normal=normalize(mix(vec3(sn,cs.x*cs.y),vec3(0.,0.,1.),clamp(lift,0.,1.)));
 v_shade=.94+.06*max(0.0,dot(v_normal,vec3(-.1732,-.2117,.9622)));
}
