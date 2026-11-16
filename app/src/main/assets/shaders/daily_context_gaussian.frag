#version 300 es
precision highp float;
precision highp sampler2D;
uniform sampler2D u_image;
uniform vec2 u_step;
uniform vec2 u_kernel[7]; // Rust: contiguous texel offsets + normalized weights
in vec2 v_uv;
out vec4 color;
void main(){
 vec4 sum=texture(u_image,v_uv)*u_kernel[0].y;
 for(int i=1;i<7;i++){
  vec2 delta=u_step*u_kernel[i].x;
  sum+=(texture(u_image,v_uv+delta)+texture(u_image,v_uv-delta))*u_kernel[i].y;
 }
 color=sum;
}
