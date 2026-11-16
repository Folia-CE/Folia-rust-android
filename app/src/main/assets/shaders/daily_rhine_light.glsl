// Shared light response for solid sleeves, the physical backdrop and every edge fade.
// Scene units are unpadded dp; neither HDR headroom nor capture origin moves this lamp.
const vec3 ARCHIVE_WARM=vec3(1.,.84,.65);
const float ARCHIVE_KEY_RADIANCE=128.;
float rhineKeyFalloff(vec3 position,vec4 lamp){
 vec3 delta=lamp.xyz-position;float radius=max(lamp.w,1.);
 return radius*radius/(max(dot(delta,delta),1.)+radius*radius);
}
vec3 rhineDiffuse(vec3 pigment,vec3 normal,vec3 position,vec4 lamp,bool print){
 vec3 delta=lamp.xyz-position;float distance2=max(dot(delta,delta),1.);
 vec3 direction=delta*inversesqrt(distance2);float radius=max(lamp.w,1.);
 float softness=.18*radius*inversesqrt(distance2+radius*radius);
 float key=clamp((dot(normal,direction)+softness)/(1.+softness),0.,1.);
 // Exterior artwork is a print, not a luminous/dielectric coating. Its authored
 // white is the exposure ceiling; no additive specular or channel clipping.
 if(print)return pigment*sqrt(vec3(.84)+ARCHIVE_WARM*(.16*key));
 float attenuation=ARCHIVE_KEY_RADIANCE*rhineKeyFalloff(position,lamp);
 // Diffuse exposure retains artwork contrast while the clear dielectric receives
 // the full lamp radiance. Do not multiply the whole UI by HDR headroom.
 vec3 irradiance=vec3(print?.70:.22)+ARCHIVE_WARM*((print?.09:.1725)*key*attenuation);
 vec3 linear=pigment*pigment*irradiance;
 vec3 halfway=normalize(direction+vec3(0.,0.,-1.));
 linear+=ARCHIVE_WARM*pow(max(dot(normal,halfway),0.),12.)*attenuation*(print?.000525:.003375);
 return sqrt(clamp(linear/(vec3(1.)+linear*.22),vec3(0.),vec3(1.)));
}
vec3 rhineBackdropColor(vec2 point,vec4 lamp){
 // Matte neutral sweep behind the scene: same lamp, warm spectrum, attenuation,
 // exposure and transfer as the sleeves; no hard-coded white top/bottom bands.
 vec3 delta=lamp.xyz-vec3(point,0.);
 float facing=max(-normalize(delta).z,0.);
 // Broad warm sweep, shared by the background and edge fades. Keep the red
 // channel below its existing ceiling instead of adding an exposed white spot.
 return vec3(.86)*sqrt(vec3(.72)+ARCHIVE_WARM*(.28*facing));
}
