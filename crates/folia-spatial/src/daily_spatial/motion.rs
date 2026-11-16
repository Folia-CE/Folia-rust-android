//! Bounded substep spring integrator. No scheduler or platform dependency.
#[derive(Clone, Copy, Default)]
pub(super) struct Spring {
 pub value:f32,pub target:f32,pub velocity:f32,
 // Keep sub-f32 motion between samples. Rounding the absolute camera position
 // every step can pin it short of its target with a permanent nonzero velocity.
 exact_value:f64,exact_velocity:f64,published:[u32;2],
}

#[cfg(test)]mod tests{
 use super::*;
 #[test]fn rounded_camera_plateaus_reach_the_existing_rest_threshold(){
  // Captured from production browsing replay BEFORE this fix: moving=1 forever,
  // unchanged scene revision, and a nonzero attracting equilibrium after f32 rounding.
  for(hz,value,target,velocity)in [
   (90,2091.2063,2091.2053,-0.004283778),
   (120,1045.6152,1045.6145,-0.0032337224),
   (240,522.80743,522.80676,-0.0029928305),
   (1000,149.3723,149.37296,0.0029459386),
  ]{
   let mut s=Spring::new(value);s.target=target;s.velocity=velocity;
   let mut moving=true;for _ in 0..hz*3{moving=s.step_exact(1./hz as f32,81.,18.);}
   assert!(!moving,"camera plateau at {hz} Hz");assert_eq!(s.value,target);assert_eq!(s.velocity,0.);
   for _ in 0..100{assert!(!s.step_exact(1./hz as f32,81.,18.));assert_eq!(s.value,target);}
  }
 }
 #[test]fn exact_response_and_midflight_retarget_do_not_depend_on_sampling(){
  for(k,d)in [(81.,18.),(120.,22.),(600.,35.),(100.,30.)]{
   let mut reference=Spring::new(1600.);reference.target=1605.;reference.velocity=-2.;
   for _ in 0..3{reference.step_exact(0.1,k,d);}reference.target=1598.;
   for _ in 0..3{reference.step_exact(0.1,k,d);}
   for hz in [30,60,90,120,240,1000]{
    let mut s=Spring::new(1600.);s.target=1605.;s.velocity=-2.;
    for _ in 0..hz*3/10{s.step_exact(1./hz as f32,k,d);}
    let before=(s.value,s.velocity);s.target=1598.;assert_eq!((s.value,s.velocity),before);
    for _ in 0..hz*3/10{s.step_exact(1./hz as f32,k,d);}
    assert!((s.value-reference.value).abs()<=0.00013);assert!((s.velocity-reference.velocity).abs()<0.0001);
   }
  }
 }
 #[test]fn explicit_position_and_velocity_edits_replace_retained_state(){
  let mut s=Spring::new(1000.);s.target=1002.;s.step_exact(0.02,81.,18.);
  s.value=30.;s.velocity=-3.;s.target=31.;let mut expected=Spring::new(30.);expected.velocity=-3.;expected.target=31.;
  s.step_exact(0.05,81.,18.);expected.step_exact(0.05,81.,18.);
  assert_eq!(s.value,expected.value);assert_eq!(s.velocity,expected.velocity);
  s.jump(2000.);assert!(!s.step_exact(0.,81.,18.));assert!(!s.step_exact(0.02,81.,18.));
 }
}
impl Spring {
 pub(super) fn new(v:f32)->Self{Self{value:v,target:v,velocity:0.0,exact_value:v as f64,exact_velocity:0.,published:[v.to_bits(),0]}}
 pub(super) fn jump(&mut self,v:f32){*self=Self::new(v);}
 /// Exact spring solution: sampling rate does not change the response or overshoot.
 pub(super) fn step_lift(&mut self,dt:f32)->bool{
  if dt<=0.{return (self.target-self.value).abs()>=0.0005||self.velocity.abs()>=0.005}
  let(stiffness,damping):(f32,f32)=if self.target>0.5{(600.,35.)}else{(500.,43.)};
  let decay=damping*0.5;let frequency=(stiffness-decay*decay).sqrt();
  let offset=self.value-self.target;let sine_coefficient=(self.velocity+decay*offset)/frequency;
  let(sin,cos)=(frequency*dt).sin_cos();let envelope=(-decay*dt).exp();
  self.value=self.target+envelope*(offset*cos+sine_coefficient*sin);
  self.velocity=envelope*(self.velocity*cos-(decay*sine_coefficient+frequency*offset)*sin);
  if (self.target-self.value).abs()<0.0005&&self.velocity.abs()<0.005{self.jump(self.target);false}else{true}
 }
 /// Exact damped response used by the portrait camera and album pose; cadence independent.
 pub(super) fn step_exact(&mut self,dt:f32,k:f32,d:f32)->bool{
  // Public state can be retargeted or repositioned by gestures/route handoff.
  // Target-only changes preserve the precise position and momentum.
  let bits=[self.value.to_bits(),self.velocity.to_bits()];
  if bits!=self.published{self.exact_value=self.value as f64;self.exact_velocity=self.velocity as f64;self.published=bits;}
  let target=self.target as f64;let y=self.exact_value-target;let v=self.exact_velocity;
  if y==0.&&v==0.{return false}
  if dt<=0.{return y.abs()>=0.0005||v.abs()>=0.005}
  let dt=dt as f64;let a=d as f64*0.5;let disc=k as f64-a*a;
  if disc>1e-6{let w=disc.sqrt();let e=(-a*dt).exp();let(sn,cs)=(w*dt).sin_cos();let b=(v+a*y)/w;self.exact_value=target+e*(y*cs+b*sn);self.exact_velocity=e*(v*cs-(a*b+w*y)*sn);}
  else if disc< -1e-6{let w=(-disc).sqrt();let r1=-a+w;let r2=-a-w;let c1=(v-r2*y)/(r1-r2);let c2=y-c1;let e1=(r1*dt).exp();let e2=(r2*dt).exp();self.exact_value=target+c1*e1+c2*e2;self.exact_velocity=r1*c1*e1+r2*c2*e2;}
  else{let e=(-a*dt).exp();let b=v+a*y;self.exact_value=target+e*(y+b*dt);self.exact_velocity=e*(v-a*b*dt);}
  if (target-self.exact_value).abs()<0.0005&&self.exact_velocity.abs()<0.005{self.jump(self.target);false}else{
   self.value=self.exact_value as f32;self.velocity=self.exact_velocity as f32;
   self.published=[self.value.to_bits(),self.velocity.to_bits()];true
  }
 }
 pub(super) fn step(&mut self,dt:f32)->bool{self.step_with(dt,150.,25.)}
 pub(super) fn step_with(&mut self,dt:f32,stiffness:f32,damping:f32)->bool{
  let steps=(dt/0.004).ceil().max(1.)as usize;
  for _ in 0..steps{let h=dt/steps as f32;self.velocity+=((self.target-self.value)*stiffness-self.velocity*damping)*h;self.value+=self.velocity*h;}
  if (self.target-self.value).abs()<0.0005&&self.velocity.abs()<0.005{self.jump(self.target);false}else{true}
 }
}
