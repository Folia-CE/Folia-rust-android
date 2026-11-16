//! SalamPlayer's native virtual bass plus its mandatory floating PCM headroom contract.
use std::sync::atomic::{AtomicU64,AtomicI32,Ordering};
use std::ffi::c_void;
pub const HEADROOM:f32=0.0017782794; // 10^(-55/20), never quantized before DP +55 dB.
static PARAMETERS:AtomicU64=AtomicU64::new(20f32.to_bits()as u64);
static CUTOFFS:AtomicU64=AtomicU64::new((110f32.to_bits()as u64)|((150f32.to_bits()as u64)<<32));
pub static SESSION:AtomicI32=AtomicI32::new(0);
pub static DEVICE:AtomicI32=AtomicI32::new(0);
pub static TOKEN:AtomicU64=AtomicU64::new(0);
pub static READY:AtomicU64=AtomicU64::new(0);
pub static ERROR:AtomicI32=AtomicI32::new(0);
static NEXT:AtomicU64=AtomicU64::new(1);
pub fn next_token()->u64{NEXT.fetch_add(1,Ordering::Relaxed)}
pub fn status()->(u64,i32,i32,bool,i32){let t=TOKEN.load(Ordering::Acquire);(t,SESSION.load(Ordering::Acquire),DEVICE.load(Ordering::Relaxed),t!=0&&READY.load(Ordering::Acquire)==t,ERROR.load(Ordering::Acquire))}
pub fn ready(token:u64,value:bool){if TOKEN.load(Ordering::Acquire)==token{if value{READY.store(token,Ordering::Release);}else{let _=READY.compare_exchange(token,0,Ordering::AcqRel,Ordering::Relaxed);}}}
pub fn configure(enabled:bool,strength:f32,quiet:f32,noisy:f32){
 let finite=|v:f32,min:f32,max:f32,default:f32|if v.is_finite(){v.clamp(min,max)}else{default};
 let quiet=finite(quiet,50.,200.,110.);let noisy=finite(noisy,quiet,300.,150.);
 CUTOFFS.store(quiet.to_bits()as u64|((noisy.to_bits()as u64)<<32),Ordering::Release);
 PARAMETERS.store(finite(strength,0.,30.,20.).to_bits()as u64|((enabled as u64)<<32),Ordering::Release);
}
/// Exact VirtualBassDsp.Design ordering (5 bands, 68 floats), no arbitrary EQ substitute.
fn design(quiet:f32,noisy:f32)->[f32;68]{
 let mut out=[0.;68];out[0]=5.;let ratio=(quiet.max(48.)as f64/40.).powf(1./3.);
 for edge in 0..4{out[1+edge]=(40.*ratio.powi(edge as i32))as f32;}
 out[5]=125f32.max(out[4]*1.15);out[6]=quiet*0.8;out[7]=quiet*2.6;
 out[8]=(20f32*40.).sqrt();out[9]=5.;for i in 0..5{out[10+i*2]=(2+i)as f32;}
 for band in 1..4{
  let center=(40.*ratio.powf(band as f64-0.5))as f32;let at=8+band*12;out[at]=center;
  let mut order=((quiet*0.92/center).ceil()as i32).max(2);let mut count=0;let mut above=0;let mut sum=0.;
  let weights=[1.,0.5,0.25];while count<3{
   let partial=order as f32*center;let ceiling=quiet*2.4;
   if partial>ceiling&&(above>0||(count>0&&partial>ceiling*1.25)){break}
   out[at+2+count*2]=order as f32;out[at+3+count*2]=weights[count];sum+=weights[count];
   if partial>=noisy{above+=1;}count+=1;order+=1;
  }
  out[at+1]=count as f32;for i in 0..count{out[at+3+i*2]/=sum;}
 }
 out[56]=(out[4]*out[5]).sqrt();out
}
unsafe extern "C" {
 fn folia_bass_create(rate:i32,channels:i32,design:*const f32,gain:f32,reference:f32)->*mut c_void;
 fn folia_bass_destroy(p:*mut c_void);
 fn folia_bass_reset(p:*mut c_void);
 fn folia_bass_parameters(p:*mut c_void,design:*const f32,gain:f32);
 fn folia_bass_process(p:*mut c_void,data:*mut f32,frames:i32,enabled:bool);
}
pub struct Bass{ptr:*mut c_void,parameters:u64,cutoffs:u64}
unsafe impl Send for Bass{} // Single audio callback owner; UI publishes only atomic parameters.
impl Bass{
 pub fn new(rate:u32,channels:usize,reference:f32)->Option<Self>{
  let parameters=PARAMETERS.load(Ordering::Acquire);let cutoffs=CUTOFFS.load(Ordering::Acquire);
  let plan=design(f32::from_bits(cutoffs as u32),f32::from_bits((cutoffs>>32)as u32));
  let ptr=unsafe{folia_bass_create(rate as i32,channels as i32,plan.as_ptr(),f32::from_bits(parameters as u32),reference)};
  if ptr.is_null(){None}else{Some(Self{ptr,parameters,cutoffs})}
 }
 pub fn reset(&mut self){unsafe{folia_bass_reset(self.ptr)}}
 pub fn process(&mut self,data:&mut[f32],channels:usize,allowed:bool){
  let p=PARAMETERS.load(Ordering::Acquire);let c=CUTOFFS.load(Ordering::Acquire);
  if p!=self.parameters||c!=self.cutoffs{
   let plan=design(f32::from_bits(c as u32),f32::from_bits((c>>32)as u32));
   unsafe{folia_bass_parameters(self.ptr,plan.as_ptr(),f32::from_bits(p as u32))};self.parameters=p;self.cutoffs=c;
  }
  unsafe{folia_bass_process(self.ptr,data.as_mut_ptr(),(data.len()/channels)as i32,allowed&&(p>>32)!=0)};
 }
}
impl Drop for Bass{fn drop(&mut self){unsafe{folia_bass_destroy(self.ptr)}}}
#[cfg(test)]mod tests{
 use super::*;
 #[test]fn headroom_is_inverse_of_55db(){assert!((HEADROOM*10f32.powf(55./20.)-1.).abs()<0.000001);}
 #[test]fn plan_matches_phone_and_pad_reference(){for(q,n)in[(110.,150.),(80.,105.)]{let p=design(q,n);assert_eq!(p[0],5.);assert_eq!(p[1],40.);assert!((p[4]-q).abs()<0.00001);assert_eq!(p[6],q*0.8);assert_eq!(p[7],q*2.6);assert_eq!(p[9],5.);for b in 1..4{let at=8+b*12;let sum:f32=(0..p[at+1]as usize).map(|i|p[at+3+2*i]).sum();assert!((sum-1.).abs()<0.000001);}}}
 #[test]fn native_bypass_preserves_attenuated_samples(){let mut b=Bass::new(48000,2,HEADROOM).unwrap();let mut data=vec![0.1*HEADROOM;480];let before=data.clone();b.process(&mut data,2,false);assert_eq!(data,before);}
}

#[cfg(test)]mod signal_tests {
 use super::*;
 #[test]fn native_bass_retains_finite_headroom_and_adds_note_harmonics(){
  configure(true,20.,110.,150.);let mut dsp=Bass::new(48000,2,HEADROOM).unwrap();
  let mut data=vec![0.;48000*2];for n in 0..48000{let v=(n as f32*std::f32::consts::TAU*70./48000.).sin()*0.03*HEADROOM;data[n*2]=v;data[n*2+1]=v;}
  dsp.process(&mut data,2,true);assert!(data.iter().all(|v|v.is_finite()&&v.abs()<0.01));
  let power=|hz:f64|{let(mut re,mut im)=(0.,0.);for n in 24000..48000{let phase=n as f64*std::f64::consts::TAU*hz/48000.;let v=data[n*2]as f64/HEADROOM as f64;re+=v*phase.cos();im+=v*phase.sin();}2.*re.hypot(im)/24000.};
  assert!(power(70.)>0.06,"real low band was not lifted");
  assert!(power(140.)>0.001,"phase-tracked second harmonic missing");
  assert!((power(141.)-power(140.)).abs()>0.0001);
  configure(false,20.,110.,150.);
 }
}
