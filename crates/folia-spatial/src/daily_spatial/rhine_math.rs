//! Camera-space rigid poses. Screen y points down; depth increases away from the viewer.
#[derive(Clone,Copy,Debug,Default)]
pub(super) struct V(pub f32,pub f32,pub f32);
impl V{
 pub fn add(self,b:V)->V{V(self.0+b.0,self.1+b.1,self.2+b.2)}
 pub fn sub(self,b:V)->V{V(self.0-b.0,self.1-b.1,self.2-b.2)}
 pub fn mul(self,s:f32)->V{V(self.0*s,self.1*s,self.2*s)}
 pub fn dot(self,b:V)->f32{self.0*b.0+self.1*b.1+self.2*b.2}
 pub fn norm(self)->f32{self.dot(self).sqrt()}
 pub fn unit(self)->V{self.mul(1./self.norm().max(1e-9))}
 pub fn mix(self,b:V,t:f32)->V{self.mul(1.-t).add(b.mul(t))}
}
#[derive(Clone,Copy,Debug)]pub(super) struct Q(pub f32,pub f32,pub f32,pub f32);
impl Default for Q{fn default()->Self{Q(0.,0.,0.,1.)}}
impl Q{
 pub fn axis(v:V,a:f32)->Q{let(s,c)=(a*0.5).sin_cos();let n=v.unit();Q(n.0*s,n.1*s,n.2*s,c)}
 pub fn mul(self,b:Q)->Q{Q(self.3*b.0+self.0*b.3+self.1*b.2-self.2*b.1,self.3*b.1-self.0*b.2+self.1*b.3+self.2*b.0,self.3*b.2+self.0*b.1-self.1*b.0+self.2*b.3,self.3*b.3-self.0*b.0-self.1*b.1-self.2*b.2)}
 pub fn inv(self)->Q{Q(-self.0,-self.1,-self.2,self.3)}
 pub fn unit(self)->Q{let n=(self.0*self.0+self.1*self.1+self.2*self.2+self.3*self.3).sqrt().max(1e-9);Q(self.0/n,self.1/n,self.2/n,self.3/n)}
 pub fn rotate(self,p:V)->V{let v=self.mul(Q(p.0,p.1,p.2,0.)).mul(self.inv());V(v.0,v.1,v.2)}
 pub fn mix(self,mut b:Q,t:f32)->Q{let mut dot=self.0*b.0+self.1*b.1+self.2*b.2+self.3*b.3;if dot<0.{b=Q(-b.0,-b.1,-b.2,-b.3);dot=-dot;}let(a,c)=if dot>0.9995{(1.-t,t)}else{let angle=dot.clamp(-1.,1.).acos();(((1.-t)*angle).sin()/angle.sin(),(t*angle).sin()/angle.sin())};Q(self.0*a+b.0*c,self.1*a+b.1*c,self.2*a+b.2*c,self.3*a+b.3*c).unit()}
}
#[derive(Clone,Copy,Debug)]pub(super) struct Pose{pub p:V,pub q:Q,pub size:f32}
impl Default for Pose{fn default()->Self{Self{p:V::default(),q:Q::default(),size:1.}}}
impl Pose{
 pub fn point(self,v:V)->V{self.p.add(self.q.rotate(v).mul(self.size))}
 pub fn matrix(self)->[f32;16]{let x=self.q.rotate(V(1.,0.,0.)).mul(self.size);let y=self.q.rotate(V(0.,1.,0.)).mul(self.size);let z=self.q.rotate(V(0.,0.,1.)).mul(self.size);[x.0,x.1,x.2,0.,y.0,y.1,y.2,0.,z.0,z.1,z.2,0.,self.p.0,self.p.1,self.p.2,1.]}
}
pub(super) fn ease(a:f32,b:f32,v:f32)->f32{let t=((v-a)/(b-a)).clamp(0.,1.);t*t*t*(10.+t*(-15.+6.*t))}

// Quintic Hermite: retain incoming velocity, end with zero velocity and acceleration.
pub(super) fn settle(a:V,b:V,v:V,time:f32,t:f32)->V{let t=t.clamp(0.,1.);let t2=t*t;let t3=t2*t;let t4=t3*t;let t5=t4*t;let blend=10.*t3-15.*t4+6.*t5;let tangent=t-6.*t3+8.*t4-3.*t5;a.mix(b,blend).add(v.mul(time*tangent))}
// Unit initial tangent with a soft stop, for spherical Bezier timing.
pub(super) fn soft_stop(t:f32)->f32{let t=t.clamp(0.,1.);let t3=t*t*t;t+4.*t3-7.*t3*t+3.*t3*t*t}

#[cfg(test)]mod tests{use super::*;
 #[test]fn settling_curve_retains_incoming_velocity_and_docks_softly(){
  let a=V(0.,0.,0.);let b=V(2.,1.,-1.);let v=V(0.4,-0.2,0.1);let duration=0.7;let epsilon=0.0001;
  assert!(settle(a,b,v,duration,0.).sub(a).norm()<1e-6);assert!(settle(a,b,v,duration,1.).sub(b).norm()<1e-6);
  let initial=settle(a,b,v,duration,epsilon).sub(a).mul(1./(duration*epsilon));assert!(initial.sub(v).norm()<0.001);
  assert!((soft_stop(epsilon)/epsilon-1.).abs()<0.001);assert!((soft_stop(1.)-1.).abs()<1e-6);
 }
}
