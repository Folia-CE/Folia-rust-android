#version 300 es
precision highp float;
precision highp sampler2D;
uniform sampler2D u_image;
uniform vec2 u_step;
in vec2 v_uv;
out vec4 color;
void main(){
 // Separable Gaussian on the actual rendered map, including inter-album gaps.
 vec4 c=texture(u_image,v_uv)*.2270270270;
 c+=(texture(u_image,v_uv+u_step*1.3846153846)+texture(u_image,v_uv-u_step*1.3846153846))*.3162162162;
 c+=(texture(u_image,v_uv+u_step*3.2307692308)+texture(u_image,v_uv-u_step*3.2307692308))*.0702702703;
 color=c;
}
