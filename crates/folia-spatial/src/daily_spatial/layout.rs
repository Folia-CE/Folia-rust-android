//! Infinite cells, shared warp boundaries, curved projection and typography regions.
use super::*;
// Scale-aware typography: an enlarged short edge must not keep a narrow unscaled side panel.
pub(super) fn caption_region(r:Rect)->(f32,f32,f32,f32,bool,f32){
 let short=r.w.min(r.h);let scale=short/104.;
 let side_w=r.w-short-20.;let side_h=r.h-20.;
 let below_w=r.w-20.;let below_h=r.h-short-16.;
 if r.w-r.h>=70.&&side_w>=48.&&side_h>=46.{
  (r.x+short+10.,r.y+10.,side_w,side_h,false,scale.min(side_w/48.).min(side_h/46.))
 }else if r.h-r.w>=64.&&below_w>=48.&&below_h>=46.{
  (r.x+10.,r.y+short+8.,below_w,below_h,false,scale.min(below_w/48.).min(below_h/46.))
 }else{(r.x+10.*scale,r.y+r.h-54.*scale,r.w-20.*scale,46.*scale,true,scale)}
}
pub(super) fn block(index:usize)->(i32,i32){
 if index==0{return(0,0)}
 let r=(((index as f64).sqrt()+1.0)/2.0).floor() as i32;
 let d=index as i64-((2*r-1) as i64).pow(2);let side=2*r as i64;
 if d<side{(r,1-r+d as i32)}else if d<side*2{(r-1-(d-side) as i32,r)}else if d<side*3{(-r,r-1-(d-side*2) as i32)}else{(-r+1+(d-side*3) as i32,-r)}
}
pub(super) fn block_index(x:i32,y:i32)->usize{
 let r=x.abs().max(y.abs());if r==0{return 0}
 let side=2*r;let d=if x==r&&y> -r{y+r-1}else if y==r{side+r-1-x}else if x== -r{side*2+r-1-y}else{side*3+x+r-1};
 ((2*r-1) as usize).pow(2)+d as usize
}
pub fn base(index:usize)->Rect{let(bx,by)=block(index/8);base_at(index,bx,by)}
pub(super) fn base_at(index:usize,bx:i32,by:i32)->Rect{
 // Restrained, staggered rectangles, rotated by module. No tiny strips or uniform square grid.
 const T:[(f32,f32,f32,f32);8]=[(0.,0.,2.7,2.),(2.7,0.,3.3,2.),
  (0.,2.,1.9,2.2),(1.9,2.,2.3,2.2),(4.2,2.,1.8,2.2),
  (0.,4.2,2.2,1.8),(2.2,4.2,1.8,1.8),(4.,4.2,2.,1.8)];
 let(mut x,mut y,mut w,mut h)=T[index%8];
 for _ in 0..((index/8)%4){let old=x;x=6.0-y-h;y=old;std::mem::swap(&mut w,&mut h);}
 Rect{x:(bx as f32*6.0+x-2.0)*CELL+GAP/2.0,y:(by as f32*6.0+y-2.0)*CELL+GAP/2.0,w:w*CELL-GAP,h:h*CELL-GAP}
}
impl State {
 // The same boundary is shared by many cards. Index its exact float value only when
 // the spatial window changes; evaluate the unchanged axis formula once per boundary.
 pub(super) fn index_edges(&mut self){
  self.edge_x.clear();self.edge_y.clear();self.edge_indices.clear();
  for &(_,r)in &self.nearby{self.edge_x.extend([r.x,r.x+r.w]);self.edge_y.extend([r.y,r.y+r.h]);}
  for axis in [&mut self.edge_x,&mut self.edge_y]{axis.sort_unstable_by(f32::total_cmp);axis.dedup_by(|a,b|a.to_bits()==b.to_bits());}
  for &(_,r)in &self.nearby{
   let find=|axis:&[f32],v:f32|axis.binary_search_by(|x|x.total_cmp(&v)).expect("indexed boundary");
   self.edge_indices.push([find(&self.edge_x,r.x),find(&self.edge_y,r.y),find(&self.edge_x,r.x+r.w),find(&self.edge_y,r.y+r.h)]);
  }
  self.warped_x.resize(self.edge_x.len(),0.);self.warped_y.resize(self.edge_y.len(),0.);
 }
 pub(super) fn update_edges(&mut self){
  for i in 0..self.edge_x.len(){self.warped_x[i]=self.warp_axis(self.edge_x[i],true);}
  for i in 0..self.edge_y.len(){self.warped_y[i]=self.warp_axis(self.edge_y[i],false);}
 }
 pub(super) fn cached_rect(&self,n:usize)->Rect{
  let [l,t,r,b]=self.edge_indices[n];let(x,y)=(self.warped_x[l],self.warped_y[t]);Rect{x,y,w:self.warped_x[r]-x,h:self.warped_y[b]-y}
 }
 pub(super) fn rebuild_warps(&mut self){
  self.warps.clear();
  for(&index,&weight)in &self.weights{let r=base(index);let short=r.w.min(r.h);let half=short/2.;
   self.warps.push((r.x+half,r.y+half,half,((CELL*4.-GAP)/short-1.).max(0.)*weight));}
 }
 pub(super) fn warp_axis(&self,value:f32,horizontal:bool)->f32{
  let mut out=value;
  for &(x,y,half,gain)in &self.warps{out+=gain*(value-if horizontal{x}else{y}).clamp(-half,half);}
  out
 }
 pub(super) fn live(&self,index:usize)->Rect{self.warp_rect(base(index))}
 pub(super) fn warp_rect(&self,r:Rect)->Rect{let x=self.warp_axis(r.x,true);let y=self.warp_axis(r.y,false);Rect{x,y,w:self.warp_axis(r.x+r.w,true)-x,h:self.warp_axis(r.y+r.h,false)-y}}
 pub(super) fn center(&self,index:usize)->(f32,f32){let r=self.live(index);let s=r.w.min(r.h);(r.x+s/2.0,r.y+s/2.0)}
 pub(super) fn camera(&self)->(f32,f32,f32,f32){(self.width/2.0,self.height*0.5,(self.width*1.3).max(900.0),(self.height*1.7).max(1100.0))}
 pub(super) fn projected(&self,x:f32,y:f32)->(f32,f32,f32){
  let(cx,cy,rx,ry)=self.camera();let ax=(x+self.x.value)*self.zoom.value/rx;let ay=(y+self.y.value)*self.zoom.value/ry;
  let(cos_x,cos_y)=(ax.cos(),ay.cos());let z=rx*(cos_x-1.)+ry*(cos_y-1.);let p=1500./(1500.-z);
  (cx+rx*ax.sin()*p,cy+ry*ay.sin()*p,p*cos_x.min(cos_y).max(0.))
 }
 pub(super) fn project(&self,x:f32,y:f32)->(f32,f32){let(x,y,_)=self.projected(x,y);(x,y)}
}
