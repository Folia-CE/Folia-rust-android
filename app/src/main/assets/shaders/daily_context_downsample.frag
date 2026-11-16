#version 300 es
precision highp float;
precision highp sampler2D;
uniform sampler2D u_image;
uniform vec2 u_texel;
in vec2 v_uv;
out vec4 color;
void main(){
 // Four bilinear samples integrate a footprint before every 2x reduction.
 // Never jump directly from sharp full-resolution content to sparse far-away taps.
 color=(texture(u_image,v_uv+u_texel*vec2(-1.,-1.))
       +texture(u_image,v_uv+u_texel*vec2( 1.,-1.))
       +texture(u_image,v_uv+u_texel*vec2(-1., 1.))
       +texture(u_image,v_uv+u_texel*vec2( 1., 1.)))*.25;
}
