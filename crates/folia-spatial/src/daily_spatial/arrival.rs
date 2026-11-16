//! Collection-only admission. Metadata keeps its stable catalogue identity; artwork demand,
//! card geometry, captions and picking see the same outward-moving reveal frontier.
use super::*;
const SEED:usize=6;
const DURATION:f32=0.38;
struct Card { delay:f32, born:Option<f32>,eligible:bool }
#[derive(Default)]
pub(super) struct Arrival {
 pub enabled:bool,pub released:bool,finished:bool,clock:f32,wave:f32,next_admit:f32,
 cards:HashMap<usize,Card>,revision:u32,
}
impl Arrival {
 pub fn active(&self)->bool {self.enabled&&!self.finished}
 pub fn apply(&mut self,dt:f32,out:&mut[f32])->bool {
  if !self.active(){return false}
  let count=(out[16]as usize).min(SLOTS);
  out[ACTION+129]=1.;out[ACTION+130]=self.revision as f32;
  out[22]=0.; // The ordinary prefetch ring must not escape the admission frontier.
  if count==0{return false}
  let held=out[ACTION+26]>0.5;
  let dt=if held{0.}else{dt};
  self.clock+=dt;
  if self.released{self.wave+=dt;}
  let rhine=out[RHINE]>0.5;
  let distance=|n:usize| {
   let o=HEADER+n*STRIDE;
   let(x,y)=if rhine{let m=RHINE_POSES+n*16;(out[m+12],out[m+13])}else{
    let x=out[o+1]+out[o+3]*0.5;let y=out[o+2]+out[o+4]*0.5;
    let ax=(x+out[2])*out[4]/out[5];let ay=(y+out[3])*out[4]/out[6];
    let p=1500./(1500.-out[5]*(ax.cos()-1.)-out[6]*(ay.cos()-1.));
    (out[7]+out[5]*ax.sin()*p,out[8]+out[6]*ay.sin()*p)
   };
   (x-out[7]).hypot(y-out[8])
  };
  // Native slots are already nearest-first. Resolve the seed boundary once; using cell
  // identity instead of song/index prevents repeated covers or slot reorder from restarting.
  if self.cards.is_empty(){
   let seed_radius=(0..count.min(SEED)).map(distance).fold(0f32,f32::max);
   for n in 0..count {
    let delay=((distance(n)-seed_radius).max(0.)/750.).min(1.2)+0.045;
    self.cards.insert(out[HEADER+n*STRIDE+7]as usize,Card{eligible:false,delay,born:if n<SEED{Some(self.clock)}else{None}});
   }
   self.revision+=1;
  }
  // Adapt ring capacity to scene density; a dense Rhine viewport must not take
  // tens of seconds to populate. Cover workers and upload budgets remain bounded.
  let mut budget=if !held&&self.released&&self.clock>=self.next_admit{(count/32).max(4)}else{0};
  let mut admitted=false;let mut demand_changed=false;let mut moving=false;let mut pending=false;
  for n in 0..count {
   let o=HEADER+n*STRIDE;let cell=out[o+7]as usize;
   let card=self.cards.entry(cell).or_insert(Card{eligible:false,delay:self.wave+0.045,born:None});
   if card.born.is_none()&&budget>0&&self.wave>=card.delay {
    card.born=Some(self.clock);budget-=1;admitted=true;
   }
   let p=card.born.map(|t|((self.clock-t)/DURATION).clamp(0.,1.)).unwrap_or(0.);
   pending|=card.born.is_none();moving|=card.born.is_some()&&p<1.;
   let alpha=p*p*(3.-2.*p);out[o+9]*=alpha;
   if alpha>0.001&&!card.eligible{card.eligible=true;demand_changed=true;}
   let scale=0.88+0.12*alpha;
   if rhine {
    let m=RHINE_POSES+n*16;
    for k in 0..12{out[m+k]*=scale;}
    out[m+13]+=(1.-alpha)*10.;
   }else{
    let dx=out[o+3]*(1.-scale)*0.5;let dy=out[o+4]*(1.-scale)*0.5;
    out[o+1]+=dx;out[o+2]+=dy;out[o+3]*=scale;out[o+4]*=scale;
    // Caption placement remains native; hidden/new cards use the same alpha as their face.
   }
  }
  if admitted{self.next_admit=self.clock+1./30.;}
  if admitted||demand_changed{self.revision+=1;}
  // Holograms also belong to their originating card; no floating labels ahead of the wave.
  if rhine {for i in 0..HOLOGRAM_SLOTS {
   let at=RHINE+RHINE_HOLOGRAM+i*HOLOGRAM_STRIDE;let cell=out[at]as usize;
   let p=self.cards.get(&cell).and_then(|c|c.born).map(|t|((self.clock-t)/DURATION).clamp(0.,1.)).unwrap_or(0.);
   out[at+6]*=p*p*(3.-2.*p);if p<1.{out[at+8]=0.;}
  }}
  out[ACTION+130]=self.revision as f32;
  if self.released&&!pending&&!moving {
   self.finished=true;self.cards.clear();
   // Publish one clean native frame next, restoring its ordinary prefetch hints.
   out[0]=1.;return true
  }
  !held&&(moving||admitted||(self.released&&pending))
 }
}

#[cfg(test)]mod arrival_tests {
 use super::*;
 fn scene()->Vec<f32>{let mut f=vec![0.;FLOATS];f[16]=30.;f[4]=1.;f[5]=900.;f[6]=1100.;f[7]=200.;f[8]=445.;f[9]=400.;f[10]=890.;f[22]=100.;for n in 0..30{let o=HEADER+n*STRIDE;f[o+1]=n as f32*24.-50.;f[o+2]=-50.;f[o+3]=100.;f[o+4]=100.;f[o+7]=n as f32;f[o+9]=1.;}f}
 #[test]fn arrival_seed_holds_then_diffuses_without_restarting(){
  let mut a=Arrival{enabled:true,..Arrival::default()};let base=scene();let mut f=base.clone();
  for _ in 0..80{f.copy_from_slice(&base);a.apply(1./120.,&mut f);}
  assert_eq!(f[22],0.);assert_eq!((0..30).filter(|&n|f[HEADER+n*STRIDE+9]>0.).count(),SEED);
  assert_eq!(f[HEADER+9],1.);
  a.released=true;for _ in 0..12{f.copy_from_slice(&base);a.apply(1./120.,&mut f);}
  let born=a.cards.values().filter(|c|c.born.is_some()).count();assert!(born>SEED&&born<30);
  assert!(a.cards[&6].born.is_some());assert!(a.cards[&29].born.is_none());
  a.released=false;let wave=a.wave;for _ in 0..60{f.copy_from_slice(&base);a.apply(1./120.,&mut f);}
  assert_eq!(a.wave,wave);assert_eq!(a.cards.values().filter(|c|c.born.is_some()).count(),born);
  a.released=true;for _ in 0..300{f.copy_from_slice(&base);a.apply(1./120.,&mut f);}
  assert!(!a.active());assert!((0..30).all(|n|f[HEADER+n*STRIDE+9]==1.));
  a.released=false;a.released=true;f.copy_from_slice(&base);assert!(!a.apply(0.,&mut f));assert_eq!(f,base);
 }
 #[test]fn arrival_empty_does_not_consume_wave_or_finish(){let mut a=Arrival{enabled:true,released:true,..Arrival::default()};let mut f=scene();f[16]=0.;for _ in 0..200{a.apply(0.1,&mut f);}assert!(a.active());assert_eq!(a.wave,0.);}
}

#[cfg(test)]mod arrival_runtime_tests {
 use super::*;
 #[test]fn arrival_public_mailbox_limits_both_layouts_and_preserves_positions(){
  for style in [1,2]{
   let id=create();let mut f=vec![0.;FLOATS];
   assert!(command(id,30,style,0.,0.));assert!(command(id,33,0,0.,0.));
   assert!(command(id,0,120,0.,0.));assert!(command(id,3,1,0.,0.));
   for _ in 0..90{assert!(step(id,1./120.,400.,890.,&mut f));}
   let count=f[16]as usize;assert!(count>SEED);
   assert_eq!((0..count).filter(|&n|f[HEADER+n*STRIDE+9]>0.).count(),SEED);
   assert_eq!(f[22],0.);assert!(f[ACTION+130]>1.,"first eligible pixels must invalidate artwork bindings");
   let seed=f[HEADER+7];let before=[f[HEADER+1],f[HEADER+2],f[HEADER+3],f[HEADER+4]];
   assert!(command(id,33,1,0.,0.));
   for _ in 0..900{assert!(step(id,1./120.,400.,890.,&mut f));}
   assert_eq!(f[ACTION+129],0.);let n=(0..f[16]as usize).find(|&n|f[HEADER+n*STRIDE+7]==seed).unwrap();let o=HEADER+n*STRIDE;
   assert_eq!(before,[f[o+1],f[o+2],f[o+3],f[o+4]]);
   assert!(command(id,33,0,0.,0.));assert!(command(id,33,1,0.,0.));step(id,0.,400.,890.,&mut f);
   assert_eq!(f[ACTION+129],0.,"cached reentry must not restart admission");release(id);
  }
 }
}
