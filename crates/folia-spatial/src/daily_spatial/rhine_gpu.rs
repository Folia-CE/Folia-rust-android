//! Bounded GPU packing and orthographic volume picking from the same published pose.
use super::*;
use super::rhine_mesh::{LEFT,RIGHT,TOP,BOTTOM,PRINT_Z};
pub const INSTANCE:usize=52;
// A shared, bounded light list is rebuilt only for changed published inputs.
// Two area lights per receiver fit GLES 3's minimum vertex-attribute budget.
const LIGHTS:usize=2;
const CANDIDATES:usize=4;
const REACH:f32=5.;
// GL-owned display hint, not an animation command. Old/zero-filled frames remain SDR.
const HDR_HEADROOM:usize=24;
fn hdr_headroom(frame:&[f32])->f32{let h=frame[RHINE+HDR_HEADROOM];if h.is_finite(){h.clamp(1.,10000.)}else{1.}}
// Finite source footprint, then a broad 40..70 degree feathered forward beam.
// Match areaGlassLight: distance expands the footprint, never a parallel tube.
fn beam_weight(distance:f32,facing:f32,radius:f32)->f32{
 let axial=(distance*facing).max(0.);let lateral=distance*(1.-facing*facing).max(0.).sqrt();
 let inner=radius+axial*0.84;let outer=radius+axial*2.75;
 let t=((lateral-inner)/(outer-inner).max(0.001)).clamp(0.,1.);
 1.-t*t*(3.-2.*t)
}
// The original four area-light rays, in their original order. Project a card's
// endpoints lazily once per pose and reuse them across candidates/receivers.
const SOURCE_POINTS:[[f32;3];4]=[[-0.3,0.3,PRINT_Z],[0.3,0.3,PRINT_Z],[-0.3,-0.3,PRINT_Z],[0.3,-0.3,PRINT_Z]];
// Local bottom-left/right, top-left/right. Shader interpolation uses these same
// extents; visibility at an exposed corner must not light a blocked whole sleeve.
const RECEIVER_POINTS:[[f32;3];4]=[[LEFT+0.025,BOTTOM+0.025,0.],[RIGHT-0.025,BOTTOM+0.025,0.],[LEFT+0.025,TOP-0.025,0.],[RIGHT-0.025,TOP-0.025,0.]];
#[derive(Clone,Copy)]struct Bounds{lo:[f32;3],hi:[f32;3]}
impl Bounds{
 fn empty()->Self{Self{lo:[f32::INFINITY;3],hi:[f32::NEG_INFINITY;3]}}
 fn include(&mut self,b:Self){for a in 0..3{self.lo[a]=self.lo[a].min(b.lo[a]);self.hi[a]=self.hi[a].max(b.hi[a]);}}
 fn around(p:[f32;3],r:f32)->Self{Self{lo:p.map(|x|x-r),hi:p.map(|x|x+r)}}
 fn overlaps(self,b:Self)->bool{(0..3).all(|a|self.lo[a]<=b.hi[a]&&self.hi[a]>=b.lo[a])}
 fn segment(self,start:[f32;3],end:[f32;3])->bool{
  let(mut near,mut far)=(0.0001_f32,0.9999_f32);
  for a in 0..3{let d=end[a]-start[a];if d.abs()<1e-7{if start[a]<self.lo[a]||start[a]>self.hi[a]{return false}}else{let x=(self.lo[a]-start[a])/d;let y=(self.hi[a]-start[a])/d;near=near.max(x.min(y));far=far.min(x.max(y));if near>far{return false}}}true
 }
}
#[derive(Clone,Copy,PartialEq)]struct LampCard{matrix:[f32;16],center:[f32;3],normal:[f32;3],size:f32,energy:f32,tint:[f32;3],alpha:f32}
impl LampCard{
 fn point(&self,p:[f32;3])->[f32;3]{std::array::from_fn(|a|self.matrix[12+a]+self.matrix[a]*p[0]+self.matrix[4+a]*p[1]+self.matrix[8+a]*p[2])}
 fn core(&self)->Bounds{let p=self.point([0.;3]);let ext:[f32;3]=std::array::from_fn(|a|self.matrix[a].abs()*0.5+self.matrix[4+a].abs()*0.5+self.matrix[8+a].abs()*PRINT_Z);Bounds{lo:std::array::from_fn(|a|p[a]-ext[a]),hi:std::array::from_fn(|a|p[a]+ext[a])}}
 fn blocks(&self,start:[f32;3],end:[f32;3])->bool{
  if self.alpha<0.999{return false} // A disappearing core must not retain a full black shadow.
  let scale2=self.size*self.size;
  let local=|p:[f32;3]|std::array::from_fn(|a|{let i=a*4;(0..3).map(|k|(p[k]-self.matrix[12+k])*self.matrix[i+k]).sum::<f32>()/scale2});
  Bounds{lo:[-0.5,-0.5,-PRINT_Z],hi:[0.5,0.5,PRINT_Z]}.segment(local(start),local(end))
 }
}
#[derive(Clone,Copy)]struct LightNode{core:Bounds,reach:Bounds,left:usize,right:usize,card:usize}
#[derive(Default)]struct LightField{
 cards:Vec<LampCard>,next:Vec<LampCard>,order:Vec<usize>,nodes:Vec<LightNode>,topology:u32,
 headroom:f32,reach_scale:f32,
 packed:Vec<[[f32;12];LIGHTS]>,rays:Vec<Bounds>,dirty:Vec<bool>,
 source_points:Vec<Option<[[f32;3];4]>>,receiver_points:Vec<Option<[[f32;3];4]>>,
 #[cfg(test)] receiver_updates:usize,
 #[cfg(test)] tree_builds:usize,
}
thread_local!{static LIGHT_FIELD:std::cell::RefCell<LightField>=std::cell::RefCell::new(LightField::default());}
impl LightField{
 fn build(&mut self,start:usize,end:usize)->usize{
  let index=self.nodes.len();let mut core=Bounds::empty();let mut reach=Bounds::empty();
  for &i in &self.order[start..end]{let c=self.cards[i];core.include(c.core());if c.energy>0.0001{reach.include(Bounds::around(c.center,c.size*0.48*REACH*self.reach_scale));}}
  self.nodes.push(LightNode{core,reach,left:0,right:0,card:usize::MAX});
  if end-start==1{self.nodes[index].card=self.order[start];return index}
  let axis=(0..3).max_by(|&a,&b|(core.hi[a]-core.lo[a]).total_cmp(&(core.hi[b]-core.lo[b]))).unwrap();
  // Only the median partition is consumed by the children. Sorting both whole
  // subtrees at every level repeats work without changing the spatial split.
  let cards=&self.cards;let mid=(start+end)/2;
  self.order[start..end].select_nth_unstable_by(mid-start,|&a,&b|cards[a].center[axis].total_cmp(&cards[b].center[axis]).then(a.cmp(&b)));
  let left=self.build(start,mid);let right=self.build(mid,end);self.nodes[index].left=left;self.nodes[index].right=right;index
 }
 // Stable slot identity does not require a new spatial sort. Refit leaf bounds and
 // merge upwards; traversal and the four exact area rays retain the same answers.
 fn refit(&mut self){
  for i in (0..self.nodes.len()).rev(){let n=self.nodes[i];
   let(core,reach)=if n.card!=usize::MAX{let c=self.cards[n.card];
    (c.core(),if c.energy>0.0001{Bounds::around(c.center,c.size*0.48*REACH*self.reach_scale)}else{Bounds::empty()})
   }else{let l=self.nodes[n.left];let r=self.nodes[n.right];let mut core=l.core;core.include(r.core);let mut reach=l.reach;reach.include(r.reach);(core,reach)};
   self.nodes[i].core=core;self.nodes[i].reach=reach;
  }
 }
 fn candidates(&self,node:usize,receiver:usize,bounds:Bounds,best:&mut[(f32,usize);CANDIDATES]){
  let n=self.nodes[node];if !n.reach.overlaps(bounds){return}
  if n.card==usize::MAX{self.candidates(n.left,receiver,bounds,best);self.candidates(n.right,receiver,bounds,best);return}
  if n.card==receiver{return}let c=self.cards[n.card];if c.energy<=0.0001{return}let r=self.cards[receiver];
  // Sample the exposed upper glass, not the album centre hidden in its slot.
  let p=r.point([0.,TOP-0.025,0.]);let delta:[f32;3]=std::array::from_fn(|a|p[a]-c.center[a]);let d2=delta.iter().map(|x|x*x).sum::<f32>();let d=d2.sqrt().max(0.001);let radius=c.size*0.48;
  let facing=(0..3).map(|a|delta[a]*c.normal[a]).sum::<f32>()/d;
  if facing<=0.||d>=radius*REACH*self.reach_scale{return}
  let t=((d/(radius*self.reach_scale)-3.)/(REACH-3.)).clamp(0.,1.);let fade=1.-t*t*(3.-2.*t);
  let score=c.energy*radius*radius/(d2+radius*radius)*facing*fade*beam_weight(d,facing,radius);
  for k in 0..CANDIDATES{if score>best[k].0||(score>0.&&score==best[k].0&&n.card<best[k].1){for j in(k+1..CANDIDATES).rev(){best[j]=best[j-1]}best[k]=(score,n.card);break}}
 }
 fn blocked(&self,node:usize,source:usize,receiver:usize,start:[f32;3],end:[f32;3])->bool{
  let n=self.nodes[node];if !n.core.segment(start,end){return false}
  if n.card==usize::MAX{return self.blocked(n.left,source,receiver,start,end)||self.blocked(n.right,source,receiver,start,end)}
  n.card!=source&&n.card!=receiver&&self.cards[n.card].blocks(start,end)
 }
 fn visibility(&mut self,source:usize,receiver:usize,rays:&mut Bounds)->[f32;4]{
  let starts=*self.source_points[source].get_or_insert_with(||SOURCE_POINTS.map(|p|self.cards[source].point(p)));
  let ends=*self.receiver_points[receiver].get_or_insert_with(||RECEIVER_POINTS.map(|p|self.cards[receiver].point(p)));
  let mut visible=[0.;4];
  // Integrate every emitter sample at every receiver corner. Zipping the two
  // lists missed clear upper-emitter -> upper-rim paths and produced black gaps.
  for (i,end) in ends.into_iter().enumerate(){for start in starts{
   rays.include(Bounds{lo:std::array::from_fn(|a|start[a].min(end[a])),hi:std::array::from_fn(|a|start[a].max(end[a]))});
   if !self.blocked(0,source,receiver,start,end){visible[i]+=0.25;}
  }}visible
 }
 fn update(&mut self,frame:&[f32],bindings:&[i32],count:usize){
  self.next.clear();
  self.source_points.resize(count,None);self.receiver_points.resize(count,None);
  for n in 0..count{let mut matrix=[0.;16];matrix.copy_from_slice(&frame[RHINE_POSES+n*16..RHINE_POSES+(n+1)*16]);let b=n*5;let f=HEADER+n*STRIDE;let rgb=bindings[b+4]as u32;
   let (size,center,normal)=if let Some(old)=self.cards.get(n).filter(|old|old.matrix.iter().zip(matrix).all(|(a,b)|a.to_bits()==b.to_bits())){
    (old.size,old.center,old.normal)
   }else{
    // Slot reuse, movement, rotation and viewport changes invalidate geometry.
    // Readiness, alpha and palette changes keep the exact same ray endpoints.
    self.source_points[n]=None;self.receiver_points[n]=None;
    let size=(matrix[0]*matrix[0]+matrix[1]*matrix[1]+matrix[2]*matrix[2]).sqrt().max(0.001);
    (size,std::array::from_fn(|a|matrix[12+a]+matrix[8+a]*PRINT_Z),std::array::from_fn(|a|matrix[8+a]/size))
   };
   self.next.push(LampCard{matrix,size,center,normal,energy:if bindings[b+1]>0||bindings[b+3]>0{frame[f+5].clamp(0.,1.)*frame[f+9].clamp(0.,1.)}else{0.},alpha:frame[f+9],tint:[((rgb>>16)&255)as f32/255.,((rgb>>8)&255)as f32/255.,(rgb&255)as f32/255.]});
  }
  let headroom=hdr_headroom(frame);let hdr_changed=headroom!=self.headroom;
  if self.cards==self.next&&!hdr_changed{return}
  // Inverse-square illumination reaches sqrt(H) farther at the same perceptual
  // cutoff. Keep the emitter's physical radius/roughness and occlusion rays intact.
  self.headroom=headroom;self.reach_scale=headroom.sqrt();
  if hdr_changed{self.dirty.fill(true);}
  let topology=frame[RHINE+7].to_bits();
  let rebuild=self.cards.len()!=count||self.topology!=topology;let mut light_changes=Bounds::empty();let mut core_changes=Bounds::empty();
  if rebuild{self.dirty.resize(count,true);self.dirty.fill(true);}
  if !rebuild{for n in 0..count{let old=self.cards[n];let new=self.next[n];if old==new{continue}
   self.dirty[n]|=old.matrix!=new.matrix;
   if old.energy>0.0001{light_changes.include(Bounds::around(old.center,old.size*0.48*REACH*self.reach_scale));}
   if new.energy>0.0001{light_changes.include(Bounds::around(new.center,new.size*0.48*REACH*self.reach_scale));}
   if old.matrix!=new.matrix||old.alpha!=new.alpha{
    if old.alpha>=0.999{core_changes.include(old.core());}if new.alpha>=0.999{core_changes.include(new.core());}
   }
  }}
  std::mem::swap(&mut self.cards,&mut self.next);
  if rebuild{self.topology=topology;self.order.clear();self.order.extend(0..count);self.nodes.clear();self.packed.resize(count,[[0.;12];LIGHTS]);self.rays.resize(count,Bounds::empty());
   if count>0{self.build(0,count);}#[cfg(test)]{self.tree_builds+=1;}
  }else{self.refit();}
  for receiver in 0..count{let r=self.cards[receiver];
   // Invalidations persist while a receiver is off screen. All sources/blockers
   // stay in the BVH; only evaluating an unused receiver's result is deferred.
   self.dirty[receiver]|=light_changes.overlaps(Bounds::around(r.center,r.size))||core_changes.overlaps(self.rays[receiver]);
  }
 }
 fn resolve(&mut self,receiver:usize){
  if !self.dirty[receiver]{return}self.dirty[receiver]=false;
  let r=self.cards[receiver];
  #[cfg(test)]{self.receiver_updates+=1;}
  self.packed[receiver]=[[0.;12];LIGHTS];let mut rays=Bounds::empty();
  let mut candidates=[(0.,usize::MAX);CANDIDATES];self.candidates(0,receiver,Bounds::around(r.center,r.size),&mut candidates);let mut best=[(0.,usize::MAX,[0.;4]);LIGHTS];
  for(score,source)in candidates{if source==usize::MAX{continue}let visible=self.visibility(source,receiver,&mut rays);let value=score*visible.iter().copied().fold(0.,f32::max);for k in 0..LIGHTS{if value>best[k].0{for j in(k+1..LIGHTS).rev(){best[j]=best[j-1]}best[k]=(value,source,visible);break}}}
  self.rays[receiver]=rays;
  for(k,(_,source,visible))in best.into_iter().enumerate(){if source==usize::MAX{continue}let c=self.cards[source];let p=&mut self.packed[receiver][k];p[..3].copy_from_slice(&c.center);p[3]=c.size*0.48;p[4..7].copy_from_slice(&c.normal);p[7]=c.energy;p[8..11].copy_from_slice(&c.tint);
   // Four 0..4 counts in 3-bit lanes fit exactly in the spare float attribute.
   p[11]=visible.iter().enumerate().map(|(i,v)|((*v*4.).round()as u32)<<(i*3)).sum::<u32>()as f32;
  }
 }
 #[cfg(test)]fn resolve_all(&mut self){for receiver in 0..self.cards.len(){self.resolve(receiver);}}

}

// Render-only clip supplied by the GL owner, relative to RHINE. It includes the
// real offscreen refraction/blur border; catalogue/hit/prefetch/light state is not culled.
const CLIP_LEFT:usize=19;const CLIP_ENABLED:usize=23;
fn outside_view(frame:&[f32],slot:usize)->bool{
 if frame[RHINE+CLIP_ENABLED]!=1.{return false}
 let clip=&frame[RHINE+CLIP_LEFT..RHINE+CLIP_LEFT+4];
 if !clip.iter().all(|x|x.is_finite())||clip[0]>=clip[2]||clip[1]>=clip[3]{return false}
 let m=&frame[RHINE_POSES+slot*16..RHINE_POSES+(slot+1)*16];
 if !m.iter().all(|x|x.is_finite()){return false}
 let cy=(TOP as f64+BOTTOM as f64)*0.5;let hy=(TOP as f64-BOTTOM as f64)*0.5;
 for axis in 0..2{
  let base=m[12+axis]as f64;let y=m[4+axis]as f64;
  let center=base+y*cy;
  let extent=(m[axis]as f64).abs()*RIGHT.max(-LEFT)as f64+y.abs()*hy+(m[8+axis]as f64).abs()*PRINT_Z as f64;
  // Enclose CPU/GPU f32 transform rounding as well as the geometric boundary.
  let guard=1.+(base.abs()+(y*cy).abs()+extent)*f32::EPSILON as f64*8.;
  if center+extent+guard<(clip[axis]as f64)||center-extent-guard>(clip[axis+2]as f64){return true}
 }false
}

pub fn pack(frame:&[f32],bindings:&[i32],out:&mut[f32],foreground:bool)->Option<usize>{
 pack_inner(frame,bindings,out,foreground,usize::MAX).map(|(count,_)|count)
}
/// Compare actual submission bits with this caller's retained output, not a hash
/// or scene revision. The caller must keep the buffer and previous count together.
pub fn pack_changed(frame:&[f32],bindings:&[i32],out:&mut[f32],previous_count:usize)->Option<(usize,bool)>{
 pack_changes(frame,bindings,out,previous_count).map(|(count,flags)|(count,flags!=0))
}
/// Bit 0: any instance changed; bit 1: core/print source inputs changed.
pub fn pack_changes(frame:&[f32],bindings:&[i32],out:&mut[f32],previous_count:usize)->Option<(usize,u32)>{
 pack_inner(frame,bindings,out,false,previous_count)
}
fn pack_inner(frame:&[f32],bindings:&[i32],out:&mut[f32],foreground:bool,previous_count:usize)->Option<(usize,u32)>{
 if frame.len()<FLOATS||frame[RHINE]<0.5{return None}let count=frame[16]as usize;if count>SLOTS||bindings.len()<count*5{return None}
 let owner=if frame[ACTION+27]>0.5{frame[ACTION+23]as i32}else{-1};let mut order=[0usize;SLOTS];let mut len=0;
 for n in 0..count{if (n as i32==owner)==foreground&&frame[HEADER+n*STRIDE+9]>0.001&& (foreground||!outside_view(frame,n)){order[len]=n;len+=1;}}
 if out.len()<len*INSTANCE{return None}
 let mut changed=if previous_count!=len{3}else{0};
 if len==0{return Some((0,changed))}
 // Fades retain painter ordering. Offscreen contributors remain in LightField,
 // so an invisible lamp or blocker still changes visible receivers correctly.
 let material=frame[RHINE+5];
 let opaque_depth=(material>0.5&&material<1.5||material>=2.5)
  &&order[..len].iter().all(|&n|frame[HEADER+n*STRIDE+9]>=1.);
 if !foreground{order[..len].sort_unstable_by(|a,b|{
  let z=frame[RHINE_POSES+a*16+14].total_cmp(&frame[RHINE_POSES+b*16+14]);
  (if opaque_depth{z}else{z.reverse()}).then(a.cmp(b))
 });}
 let lighting=frame[RHINE+18]>=0.&&frame[RHINE+5]>3.5;
 LIGHT_FIELD.with(|field|{let mut field=field.borrow_mut();if lighting{field.update(frame,bindings,count);}
 for(dst,&n)in order[..len].iter().enumerate(){
  let o=dst*INSTANCE;let b=n*5;let f=HEADER+n*STRIDE;let mut next=[0.;INSTANCE];
  next[..16].copy_from_slice(&frame[RHINE_POSES+n*16..RHINE_POSES+(n+1)*16]);let small=bindings[b+2];
  next[16]=bindings[b]as f32;next[17]=bindings[b+1]as f32;next[18]=if small>=0{(small%32*64)as f32}else{0.};next[19]=if small>=0{(small/32*64)as f32}else{0.};
  next[20]=bindings[b+3]as f32 + lyrics_chip_flag(frame, frame[f+7]);next[21]=frame[f+9];let size=(next[0]*next[0]+next[1]*next[1]+next[2]*next[2]).sqrt();
  next[22]=crate::render_policy::card_highlight(size);next[23]=if foreground{1.}else if opaque_depth{2.}else{0.};
  let rgb=bindings[b+4]as u32;next[24]=((rgb>>16)&255)as f32/255.;next[25]=((rgb>>8)&255)as f32/255.;next[26]=(rgb&255)as f32/255.;next[27]=if bindings[b+1]>0||bindings[b+3]>0{frame[f+5].clamp(0.,1.)}else{0.};
  if lighting{field.resolve(n);for k in 0..LIGHTS{next[28+k*12..40+k*12].copy_from_slice(&field.packed[n][k]);}}
  let target=&mut out[o..o+INSTANCE];
  // A changed source already invalidates both caches. Preserve early exit on
  // moving poses instead of scanning all 52 floats after the first difference.
  let differs=|a:&[f32],b:&[f32]|a.iter().zip(b).any(|(a,b)|a.to_bits()!=b.to_bits());
  let flags=if previous_count==usize::MAX||differs(&target[..28],&next[..28]){3}
   else if differs(&target[28..],&next[28..]){1}else{0};
  if flags!=0{target.copy_from_slice(&next);changed|=flags;}
 }
 });
 Some((len,changed))
}
#[derive(Clone)]pub(super) struct HitRecord{pub cell:usize,pub matrix:[f32;16],pub alpha:f32,pub owner:bool}
pub(super) fn hit(records:&[HitRecord],x:f32,y:f32,center:(f32,f32),background:f32)->i32{
 let mut nearest=f32::INFINITY;let mut picked=-1;
 for r in records{if r.alpha<=0.01{continue}let m=&r.matrix;let(px,py)=if r.owner{(x,y)}else{(center.0+(x-center.0)/background,center.1+(y-center.1)/background)};let dx=px-m[12];let dy=py-m[13];let dz=-4000.-m[14];let scale2=m[0]*m[0]+m[1]*m[1]+m[2]*m[2];if scale2<1e-8{continue}
  let mut near=0_f32;let mut far=f32::INFINITY;let limits=[(LEFT,RIGHT),(BOTTOM,TOP),(-PRINT_Z,PRINT_Z)];let mut valid=true;
  for axis in 0..3{let at=axis*4;let origin=(m[at]*dx+m[at+1]*dy+m[at+2]*dz)/scale2;let dir=m[at+2]/scale2;let(lo,hi)=limits[axis];if dir.abs()<1e-9{if origin<lo||origin>hi{valid=false;break}}else{let a=(lo-origin)/dir;let b=(hi-origin)/dir;near=near.max(a.min(b));far=far.min(a.max(b));if near>far{valid=false;break}}}
  if valid&&far>=0.{if r.owner{return r.cell as i32}if near<nearest{nearest=near;picked=r.cell as i32;}}
 }picked
}

#[cfg(test)]mod tests{
 use super::*;
 fn scene(positions:&[[f32;3]])->(Vec<f32>,Vec<i32>,Vec<f32>){
  let mut f=vec![0.;FLOATS];let mut b=vec![0;positions.len()*5];f[RHINE]=1.;f[RHINE+5]=4.;f[16]=positions.len()as f32;
  for(n,p)in positions.iter().enumerate(){let m=RHINE_POSES+n*16;f[m]=100.;f[m+5]=100.;f[m+10]=100.;f[m+15]=1.;f[m+12..m+15].copy_from_slice(p);f[HEADER+n*STRIDE+7]=(100+n*17)as f32;f[HEADER+n*STRIDE+9]=1.;b[n*5+1]=1;b[n*5+4]=0xffcc4422u32 as i32;}
  f[HEADER+5]=1.;(f,b,vec![0.;positions.len()*INSTANCE])
 }
 fn instance(out:&[f32],position:[f32;3])->&[f32]{out.chunks_exact(INSTANCE).find(|v|v[12..15]==position).unwrap()}
 fn clip(f:&mut[f32],bounds:[f32;4]){f[RHINE+CLIP_LEFT..RHINE+CLIP_LEFT+4].copy_from_slice(&bounds);f[RHINE+CLIP_ENABLED]=1.;}
 #[test]fn forward_beam_expands_with_distance_and_has_a_soft_edge(){
  let radius=48.;
  let sample=|axial:f32,lateral:f32|{let d=(axial*axial+lateral*lateral).sqrt();beam_weight(d,axial/d,radius)};
  assert_eq!(sample(100.,0.),1.);
  let edge=sample(100.,210.);assert!(edge>0.&&edge<1.,"penumbra must not be a hard cone");
  assert_eq!(sample(100.,400.),0.);assert!(sample(250.,400.)>0.,"same lateral point enters the expanding beam farther forward");
  assert!((sample(100.,210.1)-edge).abs()<0.005,"continuous feather, no angular steps");
 }
 #[test]fn hdr_range_changes_reach_without_moving_or_rebuilding_geometry(){
  let positions=[[0.,0.,0.],[0.,0.,330.],[0.,0.,-330.]];
  let(mut f,b,mut out)=scene(&positions);
  pack(&f,&b,&mut out,false).unwrap();assert_eq!(instance(&out,positions[1])[35],0.);
  f[RHINE+HDR_HEADROOM]=4.;
  let(_,flags)=pack_changes(&f,&b,&mut out,3).unwrap();
  assert_eq!(flags,1,"only lighting changes; source mesh/print data is identical");
  assert!(instance(&out,positions[1])[35]>0.,"HDR reach must include a receiver beyond the old cutoff");
  assert_eq!(instance(&out,positions[1])[31],48.,"physical emitter radius must not grow");
  assert_eq!(instance(&out,positions[2])[35],0.,"HDR must not emit backwards");
  let stable=out.clone();assert_eq!(pack_changes(&f,&b,&mut out,3).unwrap().1,0);assert_eq!(out,stable);
  f[RHINE+HDR_HEADROOM]=1.;pack(&f,&b,&mut out,false).unwrap();assert_eq!(instance(&out,positions[1])[35],0.,"headroom drop must retract the cached field");
  f[RHINE+HDR_HEADROOM]=f32::NAN;pack(&f,&b,&mut out,false).unwrap();assert_eq!(instance(&out,positions[1])[35],0.);
 }
 #[test]fn expanded_hdr_reach_keeps_real_opaque_occlusion(){
  let positions=[[0.,0.,0.],[0.,0.,330.],[0.,0.,160.]];
  let(mut f,b,mut out)=scene(&positions);f[RHINE+HDR_HEADROOM]=4.;
  pack(&f,&b,&mut out,false).unwrap();assert_eq!(instance(&out,positions[1])[35],0.,"blocker remains between source and distant glass");
  f[RHINE_POSES+32+12]=1000.;pack(&f,&b,&mut out,false).unwrap();assert!(instance(&out,positions[1])[35]>0.);
 }
 #[test]fn area_endpoints_follow_pose_but_survive_readiness_palette_and_topology_changes(){
  let(mut f,mut b,_)=scene(&[[-70.,0.,0.],[0.,0.,100.],[70.,0.,0.]]);
  f[HEADER+STRIDE*2+5]=0.6;
  let mut field=LightField::default();field.update(&f,&b,3);field.resolve_all();
  assert!(field.source_points[0].is_some());assert!(field.receiver_points[1].is_some());
  let sources=field.source_points.clone();let receivers=field.receiver_points.clone();
  b[4]^=0x1100;f[HEADER+5]=0.5;f[RHINE+7]+=1.;field.update(&f,&b,3);
  assert_eq!(field.source_points,sources);assert_eq!(field.receiver_points,receivers);
  f[RHINE_POSES+16+12]+=11.;field.update(&f,&b,3);
  assert!(field.source_points[1].is_none()&&field.receiver_points[1].is_none());
  assert_eq!(field.source_points[0],sources[0]);field.resolve_all();
  let mut fresh=LightField::default();fresh.update(&f,&b,3);fresh.resolve_all();
  assert_eq!(field.packed,fresh.packed);assert_eq!(field.receiver_points,fresh.receiver_points);
  // Shrink then reuse the removed slot at a different pose.
  field.update(&f,&b,2);f[RHINE_POSES+32+13]=17.;field.update(&f,&b,3);
  assert!(field.source_points[2].is_none()&&field.receiver_points[2].is_none());
  field.resolve_all();let mut fresh=LightField::default();fresh.update(&f,&b,3);fresh.resolve_all();assert_eq!(field.packed,fresh.packed);
 }
 #[test]fn refraction_source_flags_distinguish_incoming_light_from_source_pixels(){
  let(mut f,mut b,mut out)=scene(&[[0.,0.,0.],[0.,0.,100.]]);
  f[ACTION+27]=1.;f[ACTION+23]=0.; // Moving lifted lamp is excluded from the background source.
  assert_eq!(pack_changes(&f,&b,&mut out,usize::MAX),Some((1,3)));
  let source=out[..28].to_vec();let light=out[28..INSTANCE].to_vec();
  f[RHINE_POSES+12]+=5.;
  assert_eq!(pack_changes(&f,&b,&mut out,1),Some((1,1)));
  assert_eq!(&out[..28],&source);assert_ne!(&out[28..INSTANCE],&light);
  b[4]^=0x00ff0000;assert_eq!(pack_changes(&f,&b,&mut out,1),Some((1,1)));
  f[HEADER+5]=0.5;assert_eq!(pack_changes(&f,&b,&mut out,1),Some((1,1)));
  assert_eq!(pack_changes(&f,&b,&mut out,1),Some((1,0)));
  b[5]+=1;assert_eq!(pack_changes(&f,&b,&mut out,1),Some((1,3)),"artwork layer changes source");
  b[9]^=0x00110000;assert_eq!(pack_changes(&f,&b,&mut out,1),Some((1,3)),"own pigment changes source");
  f[HEADER+STRIDE+5]=0.2;assert_eq!(pack_changes(&f,&b,&mut out,1),Some((1,3)),"own emission changes source");
  f[RHINE_POSES+16+13]+=2.;assert_eq!(pack_changes(&f,&b,&mut out,1),Some((1,3)),"pose changes source");
  f[HEADER+STRIDE+9]=0.9;assert_eq!(pack_changes(&f,&b,&mut out,1),Some((1,3)),"alpha/order changes source");
  f[HEADER+STRIDE+9]=0.;assert_eq!(pack_changes(&f,&b,&mut out,1),Some((0,3)));
  assert_eq!(pack_changes(&f,&b,&mut out,0),Some((0,0)));
 }
 #[test]fn deferred_receivers_keep_invalidation_until_reentering_view(){
  let(mut f,mut b,mut out)=scene(&[[-80.,0.,0.],[0.,0.,100.],[200.,0.,100.]]);
  LIGHT_FIELD.with(|v|*v.borrow_mut()=LightField::default());
  clip(&mut f,[-10.,-10.,10.,10.]);assert_eq!(pack(&f,&b,&mut out,false),Some(1));
  LIGHT_FIELD.with(|v|{let field=v.borrow();assert_eq!(field.cards.len(),3);assert_eq!(field.receiver_updates,1);assert!(field.dirty[0]&&field.dirty[2]);});
  // Warm every receiver once, then hide them while source/blocker state changes.
  f[RHINE+CLIP_ENABLED]=0.;pack(&f,&b,&mut out,false).unwrap();let before=instance(&out,[200.,0.,100.]).to_vec();
  clip(&mut f,[-10.,-10.,10.,10.]);
  f[RHINE_POSES+12]+=200.;b[4]^=0x00770022;pack(&f,&b,&mut out,false).unwrap();
  f[HEADER+STRIDE+9]=0.5;pack(&f,&b,&mut out,false).unwrap();
  f[HEADER+5]=0.4;pack(&f,&b,&mut out,false).unwrap();
  LIGHT_FIELD.with(|v|assert!(v.borrow().dirty[2]));
  // Only the viewport changes. LightField.update takes its unchanged-input early return.
  clip(&mut f,[190.,-10.,210.,10.]);assert_eq!(pack(&f,&b,&mut out,false),Some(1));
  let deferred=out[..INSTANCE].to_vec();assert_ne!(&deferred[28..],&before[28..]);
  LIGHT_FIELD.with(|v|{let field=v.borrow();assert!(!field.dirty[2]);});
  LIGHT_FIELD.with(|v|*v.borrow_mut()=LightField::default());pack(&f,&b,&mut out,false).unwrap();
  assert_eq!(deferred.iter().map(|v|v.to_bits()).collect::<Vec<_>>(),out[..INSTANCE].iter().map(|v|v.to_bits()).collect::<Vec<_>>(),"reappearing receiver must match a full rebuild bit for bit");
 }
 #[test]fn retained_submission_tracks_actual_bits_bindings_and_count(){
  let(mut f,mut b,mut out)=scene(&[[0.,0.,0.],[0.,0.,100.]]);
  assert_eq!(pack_changed(&f,&b,&mut out,usize::MAX),Some((2,true)));
  f[23]+=1.;f[ACTION+124]+=1.;assert_eq!(pack_changed(&f,&b,&mut out,2),Some((2,false)));
  f[RHINE_POSES+1]=-0.;assert_eq!(pack_changed(&f,&b,&mut out,2),Some((2,true)),"do not approximate or hash submission equality");
  assert_eq!(pack_changed(&f,&b,&mut out,2),Some((2,false)));
  b[0]+=1;assert_eq!(pack_changed(&f,&b,&mut out,2),Some((2,true)));
  b[4]^=0x1100;assert_eq!(pack_changed(&f,&b,&mut out,2),Some((2,true)));
  f[HEADER+9]=0.;assert_eq!(pack_changed(&f,&b,&mut out,2),Some((1,true)));
  assert_eq!(pack_changed(&f,&b,&mut out,1),Some((1,false)));
  f[HEADER+STRIDE+9]=0.;assert_eq!(pack_changed(&f,&b,&mut out,1),Some((0,true)));
  assert_eq!(pack_changed(&f,&b,&mut out,0),Some((0,false)));
  f[HEADER+9]=1.;assert_eq!(pack_changed(&f,&b,&mut out,0),Some((1,true)));
 }
 #[test]fn viewport_culling_retains_border_and_never_culls_foreground(){
  let(mut f,b,mut out)=scene(&[[-1000.,0.,0.],[0.,0.,0.],[474.5,0.,0.],[477.,0.,0.],[0.,5000.,0.]]);
  clip(&mut f,[-14.,-32.,414.,922.]);let n=pack(&f,&b,&mut out,false).unwrap();assert_eq!(n,2);
  assert_eq!(instance(&out[..n*INSTANCE],[474.5,0.,0.])[12],474.5,"retain rounding/edge guard");
  f[ACTION+27]=1.;f[ACTION+23]=0.;assert_eq!(pack(&f,&b,&mut out,true),Some(1));assert_eq!(out[12],-1000.);
  f[ACTION+27]=0.;f[RHINE+CLIP_LEFT]=f32::NAN;assert_eq!(pack(&f,&b,&mut out,false),Some(5));
 }
 #[test]fn culled_lamp_still_illuminates_visible_receiver(){
  let(mut f,b,mut out)=scene(&[[-80.,0.,0.],[0.,0.,100.]]);
  pack(&f,&b,&mut out,false).unwrap();let prior=instance(&out,[0.,0.,100.]).to_vec();assert!(prior[35]>0.);
  clip(&mut f,[-10.,-10.,10.,10.]);assert_eq!(pack_changed(&f,&b,&mut out,2),Some((1,true)));
  assert_eq!(&out[..INSTANCE],&prior,"offscreen lights/occluders remain in the full light field");
  f[HEADER+5]=0.5;assert_eq!(pack_changed(&f,&b,&mut out,1),Some((1,true)));assert!((out[35]-prior[35]*0.5).abs()<1e-6);
 }
 #[test]fn conservative_clip_encloses_all_production_mesh_vertices(){
  let(mut f,_,_)=scene(&[[0.,0.,0.]]);clip(&mut f,[-14.,-32.,414.,922.]);
  let mut vertices=vec![0.;super::super::rhine_mesh::MAX_FLOATS];let mut seed=71u32;
  for case in 0..500{for i in 0..16{seed=seed.wrapping_mul(1664525).wrapping_add(1013904223);f[RHINE_POSES+i]=(seed as f64/u32::MAX as f64*2.-1.)as f32*if i>=12{3000.}else{350.};}
   if !outside_view(&f,0){continue}
   for kind in 0..=6{let n=super::super::rhine_mesh::mesh(kind,&mut vertices).unwrap();let mut all=[true;4];let m=&f[RHINE_POSES..RHINE_POSES+16];
    for p in vertices[..n*6].chunks_exact(6){let x=m[0]*p[0]+m[4]*p[1]+m[8]*p[2]+m[12];let y=m[1]*p[0]+m[5]*p[1]+m[9]*p[2]+m[13];
     for(k,b)in[x< -14.,y< -32.,x>414.,y>922.].into_iter().enumerate(){all[k]&=b;}
    }assert!(all.into_iter().any(|v|v),"case {case}, mesh {kind} intersects rejected viewport");
   }
  }
 }
 #[test]fn opaque_depth_order_and_fading_paths_are_distinct(){
  let(mut f,b,mut out)=scene(&[[0.,0.,70.],[0.,0.,-70.],[0.,0.,0.]]);
  pack(&f,&b,&mut out,false).unwrap();
  assert_eq!([out[14],out[INSTANCE+14],out[2*INSTANCE+14]],[-70.,0.,70.]);
  assert!(out.chunks_exact(INSTANCE).all(|v|v[23]==2.));
  // Even a nearly complete fade must retain blending, not the equal-depth pass.
  f[HEADER+9]=0.9999;pack(&f,&b,&mut out,false).unwrap();
  assert_eq!([out[14],out[INSTANCE+14],out[2*INSTANCE+14]],[70.,0.,-70.]);
  assert!(out.chunks_exact(INSTANCE).all(|v|v[23]==0.));
  f[HEADER+9]=1.;f[RHINE+5]=2.;pack(&f,&b,&mut out,false).unwrap();
  assert!(out.chunks_exact(INSTANCE).all(|v|v[23]==0.));
  f[ACTION+27]=1.;f[ACTION+23]=0.;let mut fg=[0.;INSTANCE];
  assert_eq!(pack(&f,&b,&mut fg,true),Some(1));assert_eq!(fg[23],1.);
 }
 #[test]fn lamp_reaches_multiple_forward_and_lateral_sleeves(){
  let(f,b,mut out)=scene(&[[0.,0.,0.],[110.,0.,70.],[0.,90.,100.],[0.,0.,-70.],[0.,0.,300.]]);
  assert_eq!(pack(&f,&b,&mut out,false),Some(5));
  assert_eq!(instance(&out,[0.,0.,0.])[35],0.,"no self rim halo");
  assert!(instance(&out,[110.,0.,70.])[35]>0.,"lateral glass receives the forward lamp");
  assert!(instance(&out,[0.,90.,100.])[35]>0.,"more than one receiver, without cell adjacency");
  assert_eq!(instance(&out,[0.,0.,-70.])[35],0.,"no backwards emission");assert_eq!(instance(&out,[0.,0.,300.])[35],0.,"finite reach");
  assert_eq!(pack(&f,&b,&mut out[..5*INSTANCE-1],false),None);
 }
 #[test]fn opaque_core_blocks_area_light_and_pose_changes_clear_cached_shadow(){
  let(mut f,b,mut out)=scene(&[[0.,0.,0.],[0.,0.,140.],[0.,0.,70.]]);
  for a in [0,5,10]{f[RHINE_POSES+32+a]=250.;}
  pack(&f,&b,&mut out,false).unwrap();assert_eq!(instance(&out,[0.,0.,140.])[35],0.,"intervening opaque album blocks the light");
  f[RHINE_POSES+32+12]=1000.;pack(&f,&b,&mut out,false).unwrap();assert!(instance(&out,[0.,0.,140.])[35]>0.,"moving the blocker reveals the light immediately");
  f[HEADER+9]=0.;assert_eq!(pack(&f,&b,&mut out,false),Some(2));assert_eq!(instance(&out[..2*INSTANCE],[0.,0.,140.])[35],0.,"hidden emitters do not illuminate");
 }
 #[test]fn paired_shadow_rays_miss_real_upper_and_side_light_paths(){
  let old_receivers=[[LEFT+0.025,0.15,0.],[RIGHT-0.025,0.15,0.],[-0.3,TOP-0.025,0.],[0.3,TOP-0.025,0.]];
  let mut positions=vec![[0.,0.,0.],[0.,0.,140.]];
  for (start,end) in SOURCE_POINTS.into_iter().zip(old_receivers){positions.push([50.*(start[0]+end[0]),50.*(start[1]+end[1]),70.+50.*PRINT_Z]);}
  let(mut f,b,_)=scene(&positions);
  for n in 2..6{for a in [0,5,10]{f[RHINE_POSES+n*16+a]=8.;}}
  let mut field=LightField::default();field.update(&f,&b,6);
  for (start,end) in SOURCE_POINTS.into_iter().zip(old_receivers){
   assert!(field.blocked(0,0,1,field.cards[0].point(start),field.cards[1].point(end)),"old diagonal sample must be blocked");
  }
  let visible=field.visibility(0,1,&mut Bounds::empty());
  assert!(visible[2]>0.&&visible[3]>0.,"both upper receiving corners have clear area-light paths despite old all-black result");
  assert!(visible[0]>0.&&visible[1]>0.,"lateral/lower corners also receive the finite emitter");
 }
 #[test]fn upper_visibility_does_not_light_the_blocked_lower_glass(){
  let(f,b,_)=scene(&[[0.,0.,0.],[0.,0.,140.],[0.,-40.,70.]]);
  let mut field=LightField::default();field.update(&f,&b,3);field.resolve(1);
  let visible=field.visibility(0,1,&mut Bounds::empty());assert_eq!(visible,[0.,0.,1.,1.]);
  let light=field.packed[1][0];assert_eq!(light[7],1.);
  let mask=light[11]as u32;
  for i in 0..4{assert_eq!(((mask>>(3*i))&7)as f32*0.25,visible[i]);}
  assert_eq!(mask,2304,"the shader must receive distinct dark bottom and lit top, not an averaged whole-card shadow");
 }
 #[test]fn two_lights_follow_ready_artwork_alpha_and_foreground_packing(){
  let(mut f,mut b,mut out)=scene(&[[-70.,0.,0.],[0.,0.,100.],[70.,0.,0.]]);f[HEADER+2*STRIDE+5]=0.6;
  f[ACTION+27]=1.;f[ACTION+23]=1.;let mut fg=[0.;INSTANCE];
  assert_eq!(pack(&f,&b,&mut fg,true),Some(1));assert!(fg[35]>0.&&fg[47]>0.);
  assert!((fg[36]-0.8).abs()<0.001);assert_eq!(fg[34],1.);
  b[1]=0;pack(&f,&b,&mut fg,true).unwrap();assert_eq!(fg[47],0.);assert!((fg[35]-0.6).abs()<0.001);
  f[HEADER+2*STRIDE+9]=0.5;pack(&f,&b,&mut fg,true).unwrap();assert!((fg[35]-0.3).abs()<0.001);
  f[ACTION+27]=0.;assert_eq!(pack(&f,&b,&mut out,false),Some(3));
  let receiver=(0..3).find(|&n|out[n*INSTANCE+14]==100.).unwrap()*INSTANCE;assert_eq!(out[receiver+35],fg[35]);
 }
 #[test]fn retained_lighting_matches_full_rebuild_through_motion_readiness_and_removal(){
  let positions:Vec<_>=(0..96).map(|n|[(n%8)as f32*107.,(n/8%3)as f32*99.,(n/24)as f32*81.]).collect();
  let(mut f,mut b,_)=scene(&positions);for n in 0..96{f[HEADER+n*STRIDE+5]=if n%9==0{0.7}else{0.};}
  let mut retained=LightField::default();
  for step in 0..48{
   let n=step*13%96;let m=RHINE_POSES+n*16;f[m+12]+=3.1;f[m+13]-=5.7;
   if step%3==0{f[HEADER+n*STRIDE+5]=0.85;}
   if step%4==0{f[HEADER+n*STRIDE+9]=0.5;} // no longer a fully opaque blocker
   if step%5==0{b[n*5+1]^=1;b[n*5+4]^=0x00553377;}
   if step==24{f[16]=95.;}
   let count=f[16]as usize;retained.update(&f,&b,count);
   let mut fresh=LightField::default();fresh.update(&f,&b,count);fresh.resolve_all();
   for receiver in 0..count{if step%7==0||receiver%4==step%4{
    retained.resolve(receiver);
    assert_eq!(retained.packed[receiver],fresh.packed[receiver],"deferred light/shadow mismatch at frame {step}, receiver {receiver}");
   }}
  }
  assert_eq!(retained.tree_builds,2,"motion refits instead of rebuilding the BVH");
 }
 #[test]fn distant_motion_reuses_unaffected_receivers_and_empty_foreground_does_no_light_work(){
  let(mut f,b,mut out)=scene(&[[0.,0.,0.],[0.,0.,110.],[3000.,0.,0.],[3000.,0.,110.]]);
  let mut field=LightField::default();field.update(&f,&b,4);field.resolve_all();let updates=field.receiver_updates;
  f[RHINE_POSES+2*16+12]+=2.;field.update(&f,&b,4);field.resolve_all();
  assert_eq!(field.receiver_updates-updates,1);assert_eq!(field.tree_builds,1);
  pack(&f,&b,&mut out,false).unwrap();let before=out.clone();
  f[RHINE+18]=-1.;pack(&f,&b,&mut out,false).unwrap();
  for n in 0..4{assert_eq!(&out[n*INSTANCE..n*INSTANCE+28],&before[n*INSTANCE..n*INSTANCE+28]);assert!(out[n*INSTANCE+28..(n+1)*INSTANCE].iter().all(|&v|v==0.));}
  LIGHT_FIELD.with(|v|{let mut field=v.borrow_mut();field.cards.clear();field.packed.clear();});
  assert_eq!(pack(&f,&b,&mut[],true),Some(0));
  LIGHT_FIELD.with(|v|assert!(v.borrow().cards.is_empty()));
  f[RHINE+18]=0.;pack(&f,&b,&mut out,false).unwrap();assert_eq!(out,before,"theme restore must repopulate lighting");
 }
}
