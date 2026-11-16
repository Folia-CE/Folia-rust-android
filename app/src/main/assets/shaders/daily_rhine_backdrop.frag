#version 300 es
precision highp float;
uniform vec2 u_screen;
uniform vec2 u_projection_offset;
uniform vec4 u_key_light;
in vec2 v_uv;
out vec4 color;
// RHINE_SHARED_LIGHT
void main(){
 vec2 point=vec2(v_uv.x,1.-v_uv.y)*u_screen-u_projection_offset;
 color=vec4(rhineBackdropColor(point,u_key_light),1.);
}
