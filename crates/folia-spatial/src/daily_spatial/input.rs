//! Semantic inputs; all state transitions are serialized by the scene mailbox.
use super::*;
impl State {
 pub(super) fn command(&mut self,op:i32,index:i32,a:f32,b:f32)->bool{
  if !a.is_finite()||!b.is_finite(){return false}
  match op{
   0=>{if !(0..=1_000_000).contains(&index){return false}let initial=self.count==0;self.count=index as usize;if initial&&self.count>0{let(x,y)=self.center(self.selected);self.x.jump(-x);self.y.jump(-y);}}
   1=>{if self.count==0||index< -1{return false}if index>=0{self.selected=index as usize;}self.follow=true;
    if self.playing_song==Some(self.song(self.selected)){self.playing=Some(self.selected);}}
   2=>{let song=if index>=0&&(index as usize)<self.count{Some(index as usize)}else{None};
    let next=if song==self.playing_song{self.playing}else{song.map(|i|self.playback_cell(i))};
    if next!=self.playing{if let Some(i)=next{
     // The fallback cell may still carry an older repeat binding after catalogue growth.
     // Rebind it to the actual requested track before publishing the playback snapshot.
     if let Some(track)=song{self.bindings.insert(i,track);}if self.action_phase==0{self.selected=i;self.follow=true;}
    }}
    self.playing_song=song;self.playing=next;self.running=a>0.5;}
   14=>{if let Some(cell)=self.playing{self.selected=cell;self.follow=true;}else{return false}}
   15..=19|21..=22=>return self.context_command(op,index),
   20=>{if index<0||index as usize>=self.count{return false}let removed=index as usize;let removes_action=self.hidden_cell.or(self.action_cell).map(|cell|self.song(cell))==Some(removed);self.count-=1;
    for song in self.bindings.values_mut(){if *song>removed{*song-=1;}else if *song==removed{*song=removed.min(self.count.saturating_sub(1));}}
    if let Some(song)=self.playing_song{if song==removed{self.playing=None;self.playing_song=None;self.running=false;self.follow=false;}else if song>removed{self.playing_song=Some(song-1);}}
    if removes_action{self.follow=false;self.finish_removal();}
    self.region=None;self.signature=[u32::MAX;9];}
   3=>{if index==0{self.close_actions();}self.mode.target=if index!=0{1.0}else{0.0};}
   4=>{if self.action_phase!=3{self.close_actions();}self.lead_x=(a*6.).clamp(-320.,320.);self.lead_y=(b*6.).clamp(-320.,320.);self.quiet=0.12;self.follow=false;self.x.jump(self.x.value+a/self.zoom.value);self.y.jump(self.y.value+b/self.zoom.value);}
   5=>{self.lead_x=(a*0.15).clamp(-320.,320.);self.lead_y=(b*0.15).clamp(-320.,320.);self.quiet=0.12;self.follow=false;self.x.velocity=a.clamp(-2400.0,2400.0)/self.zoom.value;self.y.velocity=b.clamp(-2400.0,2400.0)/self.zoom.value;self.x.target=self.x.value+self.x.velocity*0.12;self.y.target=self.y.value+self.y.velocity*0.12;}
   6=>{self.quiet=0.12;self.zoom.target=(self.zoom.target*a.clamp(0.5,2.0)).clamp(ZOOM_MIN,ZOOM_MAX);}
   11=>{self.close_actions();self.pinching=true;self.pinch_raw=self.zoom.value;self.zoom.jump(self.zoom.value);}
   12=>{self.quiet=0.12;self.pinch_raw=(self.pinch_raw*a.clamp(0.5,2.)).clamp(ZOOM_MIN*0.4,ZOOM_MAX*1.6);
    let raw=self.pinch_raw;let bound=raw.clamp(ZOOM_MIN,ZOOM_MAX);
    let edge=bound+(raw-bound)/(1.+(raw-bound).abs()/0.32);
    // 97% immediate gesture response; only the remaining 3% settles between input samples.
    self.zoom.target=edge;self.zoom.value+=(edge-self.zoom.value)*0.97;self.zoom.velocity=0.;}
   13=>{self.pinching=false;self.zoom.target=self.zoom.value.clamp(ZOOM_MIN,ZOOM_MAX);self.zoom.velocity=0.;}
   7=>{self.header=a.max(0.0);self.scroll=b.max(0.0);}
   8=>{self.mode.jump(0.0);}
   10=>{if !(0..=1_000_000).contains(&index){return false}self.count=index as usize;self.selected=(a.max(0.0)as usize).min(self.count.saturating_sub(1));self.close_actions();self.action_cell=None;self.hidden_cell=None;self.fill_cell=None;self.weights.clear();self.bindings.clear();self.next_bindings.clear();self.playing=if b>=0.0&&(b as usize)<self.count{Some(b as usize)}else{None};self.playing_song=self.playing;if let Some(i)=self.playing{self.weights.insert(i,1.0);}self.rebuild_warps();self.region=None;self.signature=[u32::MAX;9];}
   _=>return false,
  }true
 }
}
pub(super) fn hit_geometry(mode:f32,pan_x:f32,pan_y:f32,zoom:f32,width:f32,height:f32,center_y:f32,visible:&[(usize,Rect)],x:f32,y:f32)->i32{
  if mode<0.95||!x.is_finite()||!y.is_finite(){return -1}
  let(cx,cy,rx,ry)=(width/2.,center_y,(width*1.3).max(900.),(height*1.7).max(1100.));let mut px=x-cx;let mut py=y-cy;
  let project=|a:f32,b:f32|{let ax=a/rx;let ay=b/ry;let z=rx*(ax.cos()-1.)+ry*(ay.cos()-1.);let p=1500./(1500.-z);(cx+rx*ax.sin()*p,cy+ry*ay.sin()*p)};
  for _ in 0..9{let(a,b)=project(px,py);let(c,d)=project(px+0.25,py);let(e,f)=project(px,py+0.25);let(jx,jy,kx,ky)=((c-a)*4.,(d-b)*4.,(e-a)*4.,(f-b)*4.);let det=jx*ky-kx*jy;if det.abs()<0.01{return -1}let(ex,ey)=(x-a,y-b);px+=(ex*ky-ey*kx)/det;py+=(ey*jx-ex*jy)/det;if ex.abs()+ey.abs()<0.01{break}}
  if (px/rx).abs()>1.22||(py/ry).abs()>1.22{return -1}let wx=px/zoom-pan_x;let wy=py/zoom-pan_y;
  for &(index,r)in visible{if wx>=r.x&&wx<=r.x+r.w&&wy>=r.y&&wy<=r.y+r.h{let qx=(wx-r.x-r.w/2.).abs()-(r.w/2.-6.);let qy=(wy-r.y-r.h/2.).abs()-(r.h/2.-6.);if qx.max(0.).hypot(qy.max(0.))+qx.max(qy).min(0.)<=6.{return index as i32}}}-1
 
}

