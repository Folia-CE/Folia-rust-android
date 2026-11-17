//! Orientation dispatch shares catalogue events but never shares the two layouts' transforms.
use super::*;
pub(super) struct Runtime{arrival:arrival::Arrival,navigation:Option<([f32;3],usize,rhine::NavigationView)>,style:i32,pending_style:Option<i32>,style_alpha:Spring,transition_dirty:bool,output_revision:u32,source_revision:u32,source_route:bool,width:f32,height:f32,pub count:usize,pub landscape:State,pub portrait:rhine::Rhine,pub is_portrait:bool,pub commands:Vec<Command>}
impl Runtime{
 pub fn new()->Self{Self{arrival:arrival::Arrival::default(),navigation:None,style:0,pending_style:None,style_alpha:Spring::new(1.),transition_dirty:true,output_revision:0,source_revision:u32::MAX,source_route:false,width:0.,height:0.,count:0,landscape:State::new(),portrait:rhine::Rhine::new(),is_portrait:false,commands:Vec::with_capacity(64)}}
 pub fn viewport(&mut self,w:f32,h:f32){self.width=w;self.height=h;let portrait=match self.style{1=>true,2=>false,_=>h>w};if portrait!=self.is_portrait{let mode=self.mode();if self.landscape.hidden_cell.is_some(){self.landscape.finish_removal();}self.landscape.close_actions();self.landscape.menu.jump(0.);self.landscape.confirmation.jump(0.);self.landscape.lift.jump(0.);self.landscape.lift_shadow.jump(0.);self.landscape.action_cell=None;self.landscape.signature=[u32::MAX;9];self.portrait.cancel_transient();self.landscape.mode=mode;self.portrait.common.mode=mode;self.is_portrait=portrait;}if portrait{self.portrait.common.width=w;self.portrait.common.height=h;}else{self.landscape.width=w;self.landscape.height=h;}}
 fn mode(&self)->Spring{if self.is_portrait{self.portrait.common.mode}else{self.landscape.mode}}
 fn portrait_for(&self,style:i32)->bool{match style{1=>true,2=>false,_=>self.height>self.width}}
 pub fn command(&mut self,op:i32,index:i32,a:f32,b:f32){
  // Paired with the Kotlin scene transaction. Playback may change under a child,
  // but closing it must restore the exact parent viewport rather than locate playback.
  if op==33 {
   self.arrival.enabled=true;self.arrival.released=index>0;
   // A secondary world is already in spatial mode; its parent owns the entry morph.
   self.landscape.mode.jump(1.);self.portrait.common.mode.jump(1.);
   self.transition_dirty=true;return
  }
  if op==31 {
   if self.navigation.is_none(){self.navigation=Some(([self.landscape.x.value,self.landscape.y.value,self.landscape.zoom.value],self.landscape.selected,self.portrait.navigation_view()));}
   return
  }
  if op==32 {
   if let Some((v,selected,rhine))=self.navigation.take(){
    self.landscape.x.jump(v[0]);self.landscape.y.jump(v[1]);self.landscape.zoom.jump(v[2]);
    self.landscape.selected=selected;self.landscape.follow=false;self.landscape.pinching=false;
    self.landscape.lead_x=0.;self.landscape.lead_y=0.;self.landscape.signature=[u32::MAX;9];
    self.portrait.restore_navigation_view(rhine);self.transition_dirty=true;
   }
   return
  }
  if op==30{
   if !(0..=2).contains(&index){return}
   self.transition_dirty=true;
   if self.mode().value<0.01&&self.mode().target<0.01{
    self.pending_style=None;self.style_alpha.jump(1.);self.style=index;self.viewport(self.width,self.height);
   }else if self.portrait_for(index)==self.is_portrait{
    // A new choice can reverse a fade at its current value/velocity. Never queue
    // obsolete styles or jump back to a fully opaque composite.
    self.pending_style=None;self.style=index;self.style_alpha.target=1.;
   }else{self.pending_style=Some(index);self.style_alpha.target=0.;}
   return
  }
  if op==3 {if index==0{self.pending_style=None;self.style_alpha.jump(self.style_alpha.value.clamp(0.,1.));}else if self.pending_style.is_none(){self.style_alpha.target=1.;}}
  if op==8{self.pending_style=None;self.style_alpha.jump(1.);}
  if matches!(op,0|2|3|7|8|10|20){if matches!(op,0|10)&&index>=0{self.count=index as usize;}if op==20&&index>=0&&(index as usize)<self.count{self.count-=1;}self.landscape.command(op,index,a,b);self.portrait.command(op,index,a,b);}
  else if self.is_portrait{self.portrait.command(op,index,a,b);}else{self.landscape.command(op,index,a,b);}
 }
 pub fn frame(&mut self,dt:f32,w:f32,h:f32,out:&mut[f32])->bool{
  if out.len()<FLOATS{return false}
  let before=self.style_alpha.value;
  if self.mode().value<=0.0005&&self.mode().target==0.{self.style_alpha.jump(1.);}
  let dt=dt.clamp(0.,0.1);let mut changing=self.style_alpha.step_exact(dt,900.,60.);
  if self.pending_style.is_some()&&self.style_alpha.value<=0.006{
   self.style=self.pending_style.take().unwrap();self.viewport(w,h);
   self.style_alpha.jump(0.);self.style_alpha.target=1.;changing=true;
  }
  let ok=if self.is_portrait{self.portrait.frame(dt,w,h,out)}else{self.landscape.frame(dt,w,h,out)};
  if !ok{return false}
  let arrival_active=self.arrival.active();
  let arrival_moving=self.arrival.apply(dt,out);
  let source=out[23].to_bits();
  if arrival_active||self.transition_dirty||source!=self.source_revision||self.source_route!=self.is_portrait||before!=self.style_alpha.value{
   self.output_revision=self.output_revision%1_000_000+1;
  }
  self.source_revision=source;self.source_route=self.is_portrait;self.transition_dirty=false;
  out[23]=self.output_revision as f32;out[ACTION+128]=self.style_alpha.value.clamp(0.,1.);
  // Renderer background caches must include per-card arrival geometry/alpha.
  if arrival_active {out[ACTION+124]=self.output_revision as f32;}
  if arrival_moving||changing||self.pending_style.is_some(){out[0]=1.;out[24]=1.;}
  true
 }

}

#[cfg(test)]mod tests{use super::*;
 #[test]fn album_entry_crosses_85_percent_while_the_selected_instance_is_still_opening(){
  for style in [1,2] {
   let mut r=Runtime::new();let mut f=vec![0.;FLOATS];
   r.command(30,style,0.,0.);r.viewport(400.,890.);
   // One track appears on many physical cells, including when resuming the same song.
   r.command(10,1,0.,0.);r.command(3,1,0.,0.);
   for _ in 0..300 {r.frame(1./120.,400.,890.,&mut f);}
   let slot=(0..f[16] as usize).find(|&i|f[HEADER+i*STRIDE+7] == 1.).unwrap();
   let cell=f[HEADER+slot*STRIDE+7] as i32;
   r.command(1,cell,0.,0.);r.command(29,0,0.,0.);r.command(2,0,1.,0.);
   r.frame(0.,400.,890.,&mut f);
   assert!(f[ACTION+PLAYING_OPEN_PROGRESS]<0.85);
   let mut crossed=false;
   for _ in 0..600 {
    r.frame(1./120.,400.,890.,&mut f);
    assert_eq!(f[ACTION+22],cell as f32);
    let open=f[ACTION+PLAYING_OPEN_PROGRESS];
    if !crossed && open>=0.85 {assert!(open<1.);assert_eq!(f[0],1.);crossed=true;}
    if f[0]==0.{break}
   }
   assert!(crossed);assert_eq!(f[0],0.);assert_eq!(f[ACTION+PLAYING_OPEN_PROGRESS],1.);
  }
 }
 #[test]fn explicit_styles_override_orientation_without_resetting_catalogue_or_camera(){
  let mut r=Runtime::new();let mut f=vec![0.;FLOATS];r.viewport(400.,890.);r.command(10,1024,12.,12.);r.command(3,1,0.,0.);
  r.command(30,2,0.,0.);for _ in 0..150{r.frame(1./120.,400.,890.,&mut f);}assert_eq!(f[RHINE],0.);assert_eq!(r.landscape.playing_song,Some(12));
  r.command(30,1,0.,0.);r.viewport(1200.,800.);for _ in 0..150{r.frame(1./120.,1200.,800.,&mut f);}assert_eq!(f[RHINE],1.);
  r.command(2,13,1.,0.);r.command(30,2,0.,0.);assert_eq!(r.landscape.playing_song,Some(13));assert_eq!(r.portrait.common.playing_song,Some(13));
  r.command(30,0,0.,0.);for _ in 0..150{r.frame(1./120.,1200.,800.,&mut f);}assert!(!r.is_portrait);r.viewport(400.,890.);assert!(r.is_portrait);
 }
 #[test]fn visible_style_handoff_is_non_linear_and_latest_choice_wins(){
  let mut r=Runtime::new();let mut f=vec![0.;FLOATS];r.viewport(400.,890.);r.command(0,1024,0.,0.);
  r.portrait.common.mode.jump(1.);r.landscape.mode.jump(1.);r.frame(0.,400.,890.,&mut f);
  let initial=f[23];r.command(30,2,0.,0.);r.frame(0.,400.,890.,&mut f);
  assert_eq!(f[0],1.);assert_ne!(f[23],initial,"zero-dt request must still schedule its first swap");
  for _ in 0..8{r.frame(1./120.,400.,890.,&mut f);}
  assert!(r.is_portrait);assert!(f[ACTION+128]>0.&&f[ACTION+128]<1.);
  let alpha=f[ACTION+128];r.command(30,1,0.,0.);assert_eq!(r.style_alpha.value,alpha);
  for _ in 0..150{r.frame(1./120.,400.,890.,&mut f);assert!(r.is_portrait);}
  assert_eq!(f[ACTION+128],1.);
  r.command(30,2,0.,0.);let mut previous=1.;let mut switched=false;
  for _ in 0..180{r.frame(1./120.,400.,890.,&mut f);
   if !r.is_portrait&&!switched{assert!(previous<0.009);assert_eq!(f[ACTION+128],0.);switched=true;}
   previous=f[ACTION+128];
  }
  assert!(switched);assert_eq!(f[ACTION+128],1.);assert_eq!(f[0],0.);
 }
 #[test]fn list_exit_cancels_pending_style_and_preserves_entry_motion(){
  let mut r=Runtime::new();let mut f=vec![0.;FLOATS];r.viewport(400.,890.);r.command(0,1024,0.,0.);r.command(3,1,0.,0.);
  r.command(30,2,0.,0.);for _ in 0..150{r.frame(1./120.,400.,890.,&mut f);}
  assert_eq!(r.mode().value,1.);assert_eq!(r.mode().target,1.);
  r.command(30,1,0.,0.);r.frame(1./120.,400.,890.,&mut f);r.command(3,0,0.,0.);
  for _ in 0..180{r.frame(1./120.,400.,890.,&mut f);assert!(!r.is_portrait);}
  assert!(r.pending_style.is_none());assert_eq!(f[1],0.);assert_eq!(f[ACTION+128],1.);
 }
 #[test]fn orientation_uses_separate_layouts_and_shared_catalogue(){let mut r=Runtime::new();let mut f=vec![0.;FLOATS];r.viewport(400.,890.);r.command(0,1024,0.,0.);r.command(3,1,0.,0.);r.portrait.common.mode.jump(1.);assert!(r.frame(0.1,400.,890.,&mut f));assert_eq!(f[RHINE],1.);r.command(4,0,60.,20.);r.frame(0.1,400.,890.,&mut f);r.viewport(1200.,800.);assert!(r.frame(0.1,1200.,800.,&mut f));assert_eq!(f[RHINE],0.);assert!(f[16]<=VISIBLE as f32);assert_eq!(r.landscape.count,1024);assert_eq!(r.portrait.common.count,1024);r.command(20,8,0.,0.);assert_eq!(r.landscape.count,1023);assert_eq!(r.portrait.common.count,1023);}
}

#[cfg(test)]mod navigation_tests {
 use super::*;
 #[test]fn collection_bookmark_uses_public_mailbox_in_order(){
  let id=create();let mut f=vec![0.;FLOATS];
  assert!(command(id,30,2,0.,0.));assert!(command(id,10,128,0.,-1.));assert!(command(id,3,1,0.,0.));
  for _ in 0..160{assert!(step(id,1./90.,400.,890.,&mut f));}
  command(id,4,0,77.,-39.);step(id,0.,400.,890.,&mut f);let expected=[f[2],f[3],f[4]];
  assert!(command(id,31,0,0.,0.));assert!(command(id,2,37,1.,0.));assert!(command(id,32,0,0.,0.));
  for _ in 0..160{assert!(step(id,1./90.,400.,890.,&mut f));}
  assert_eq!([f[2],f[3],f[4]],expected);release(id);
 }

 #[test]fn collection_return_preserves_map_view_after_playback_and_reentry(){
  let mut r=Runtime::new();let mut f=vec![0.;FLOATS];r.command(30,2,0.,0.);r.viewport(400.,890.);
  r.command(10,128,0.,-1.);r.command(3,1,0.,0.);
  for _ in 0..160{r.frame(1./90.,400.,890.,&mut f);}
  r.command(4,0,93.,-127.);r.command(6,0,1.15,0.);
  for _ in 0..160{r.frame(1./90.,400.,890.,&mut f);}
  let expected=[f[2],f[3],f[4]];
  for song in [3,19,7]{
   r.command(31,0,0.,0.);r.command(31,0,0.,0.);r.command(2,song,1.,0.);r.command(32,0,0.,0.);
   for _ in 0..120{r.frame(1./90.,400.,890.,&mut f);}
   assert_eq!([f[2],f[3],f[4]],expected);assert_eq!(r.landscape.playing_song,Some(song as usize));
  }
 }
 #[test]fn collection_return_preserves_rhine_view_after_playback(){
  let mut r=Runtime::new();let mut f=vec![0.;FLOATS];r.command(30,1,0.,0.);r.viewport(400.,890.);
  r.command(10,128,0.,-1.);r.command(3,1,0.,0.);
  for _ in 0..160{r.frame(1./90.,400.,890.,&mut f);}
  r.command(26,0,0.,0.);r.command(27,0,51.,73.);r.command(28,0,0.,0.);
  for _ in 0..160{r.frame(1./90.,400.,890.,&mut f);}
  let expected=r.portrait.navigation_view();
  for song in [15,27,8]{
   r.command(31,0,0.,0.);r.command(2,song,1.,0.);r.command(32,0,0.,0.);
   for _ in 0..120{r.frame(1./90.,400.,890.,&mut f);}
   assert_eq!(r.portrait.navigation_view(),expected);assert_eq!(r.portrait.common.playing_song,Some(song as usize));
  }
 }
}
