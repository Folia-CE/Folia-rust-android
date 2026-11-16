//! GLES bottom-up RGBA readback -> Android top-down ARGB, on the GL worker.
pub fn pixels(width:i32,height:i32)->Option<usize>{
 if width<=0||height<=0{return None}
 let count=(width as usize).checked_mul(height as usize)?;
 if count>4_194_304{return None}Some(count)
}
pub fn convert(rgba:&[u8],width:i32,height:i32,argb:&mut[i32])->bool{
 let Some(count)=pixels(width,height)else{return false};
 if rgba.len()<count*4||argb.len()<count{return false}
 let(w,h)=(width as usize,height as usize);
 for y in 0..h{let row=(h-1-y)*w*4;
  for x in 0..w{let i=row+x*4;argb[y*w+x]=((rgba[i+3]as u32)<<24|(rgba[i]as u32)<<16|(rgba[i+1]as u32)<<8|rgba[i+2]as u32)as i32;}
 }true
}
#[cfg(test)]mod tests{
 use super::*;
 #[test]fn orientation_channels_and_alpha(){
  let input=[255,0,0,255,0,255,0,128,0,0,255,255,11,22,33,44];let mut out=[0;4];
  assert!(convert(&input,2,2,&mut out));assert_eq!(out.map(|v|v as u32),[0xff0000ff,0x2c0b1621,0xffff0000,0x8000ff00]);
 }
 #[test]fn rejects_invalid_or_short_buffers(){
  assert_eq!(pixels(-1,8),None);assert_eq!(pixels(i32::MAX,i32::MAX),None);
  assert!(!convert(&[0;15],2,2,&mut[0;4]));assert!(!convert(&[0;16],2,2,&mut[0;3]));
 }
}
