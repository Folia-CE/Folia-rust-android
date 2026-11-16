//! Reusable closed glass meshes, generated once by Rust. No stroke/outline geometry.
pub const ART:f32=1.;pub const LEFT:f32=-0.6;pub const RIGHT:f32=0.6;pub const TOP:f32=0.6;pub const BOTTOM:f32=-0.73333335;// Shared by geometry, volume picking and the dust silhouette. Optical path: daily_rhine.frag.
pub const THICK:f32=1.7/30.; // 1.7 cm glass against a 30 cm cover.
// Separate exterior print faces; tiny stand-off prevents coplanar glass/print z-fighting.
pub const PRINT_Z:f32=THICK/2.+0.0004;
pub const MAX_FLOATS:usize=6*6*6*6*6;
/// Compact only bit-identical position/normal pairs. Keep every triangle and its
/// original winding/provoking vertex; hard edges and rounded normals stay distinct.
/// Runs once per GL context, not in the animation/instance packing path.
pub fn indexed_mesh(kind:i32,vertices:&mut[f32],indices:&mut[u16])->Option<(usize,usize)>{
 let count=mesh(kind,vertices)?;
 if indices.len()<count{return None}
 let mut unique=std::collections::HashMap::<[u32;6],u16>::with_capacity(count);
 for (i,index) in indices[..count].iter_mut().enumerate(){
  let vertex:[f32;6]=std::array::from_fn(|k|vertices[i*6+k]);
  let key=vertex.map(f32::to_bits);
  *index=if let Some(&old)=unique.get(&key){old}else{
   let next=u16::try_from(unique.len()).ok()?;
   vertices[next as usize*6..next as usize*6+6].copy_from_slice(&vertex);
   unique.insert(key,next);next
  };
 }
 Some((unique.len(),count))
}
pub fn mesh(kind:i32,out:&mut[f32])->Option<usize>{
 if kind==5||kind==6{return archive_bevel(out,kind==6)}
 if kind==3{
  if out.len()<72{return None}let mut at=0;
  for sign in [-1.,1.]{let corners=if sign>0.{[(-0.5,-0.5),(0.5,-0.5),(-0.5,0.5),(0.5,-0.5),(0.5,0.5),(-0.5,0.5)]}else{[(-0.5,-0.5),(-0.5,0.5),(0.5,-0.5),(0.5,-0.5),(-0.5,0.5),(0.5,0.5)]};
   for(x,y)in corners{out[at..at+6].copy_from_slice(&[x,y,sign*PRINT_Z,0.,0.,sign]);at+=6;}
  }return Some(12)
 }
 if !(0..=4).contains(&kind){return None}
 let glass=kind==1||kind==2;let half=if glass{[0.6,2./3.,THICK/2.]}else if kind==4{[0.5,0.5,THICK/2.]}else{[0.5,0.5,0.0035]};let center=if glass{-1./15.}else{0.};let r=if kind==2{0.010}else{0.};
 let n=if kind==2{7}else{2};let required=6*(n-1)*(n-1)*6*6;if out.len()<required{return None}
 let coordinate=|axis:usize,i:usize|{let h=half[axis];if n==2{if i==0{-h}else{h}}else{[-h,-h+r*0.293,-h+r,0.,h-r,h-r*0.293,h][i]}};
 let mut at=0;
 for axis in 0..3{for sign in [-1.,1.]{let u=(axis+1)%3;let v=(axis+2)%3;
  for y in 0..n-1{for x in 0..n-1{let corners=if sign>0.{[(x,y),(x+1,y),(x,y+1),(x+1,y),(x+1,y+1),(x,y+1)]}else{[(x,y),(x,y+1),(x+1,y),(x+1,y),(x,y+1),(x+1,y+1)]};
   for(i,j)in corners{let mut p=[0.;3];p[axis]=sign*half[axis];p[u]=coordinate(u,i);p[v]=coordinate(v,j);let mut normal=[0.;3];
    if r>0.{let core=[p[0].clamp(-half[0]+r,half[0]-r),p[1].clamp(-half[1]+r,half[1]-r),p[2].clamp(-half[2]+r,half[2]-r)];let d=[p[0]-core[0],p[1]-core[1],p[2]-core[2]];let len=(d[0]*d[0]+d[1]*d[1]+d[2]*d[2]).sqrt();for k in 0..3{normal[k]=d[k]/len;p[k]=core[k]+normal[k]*r;}}
    else{normal[axis]=sign;}p[1]+=center;out[at..at+3].copy_from_slice(&p);out[at+3..at+6].copy_from_slice(&normal);at+=6;
   }
  }}
 }}Some(at/6)
}

// Shared background bevel: 132 vertices rather than the lifted mesh's 1296.
// Six flat faces, twelve edge strips, eight corner triangles. Generated once;
// interpolated edge normals meet flat-face normals without shading large planes.
fn archive_bevel(out:&mut[f32],rim_only:bool)->Option<usize>{
 if out.len()<(if rim_only{168}else{132})*6{return None}
 let half=[0.6,2./3.,THICK/2.];let radius=0.010;let core=half.map(|h|h-radius);let mut at=0;
 fn triangle(out:&mut[f32],at:&mut usize,mut vertices:[[f32;6];3]){
  let a=vertices[0];let b=vertices[1];let c=vertices[2];
  let u=[b[0]-a[0],b[1]-a[1],b[2]-a[2]];let v=[c[0]-a[0],c[1]-a[1],c[2]-a[2]];
  let cross=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]];
  if (0..3).map(|i|cross[i]*(a[i+3]+b[i+3]+c[i+3])).sum::<f32>()<0.{vertices.swap(1,2);}
  for mut vertex in vertices{vertex[1]-=1./15.;out[*at..*at+6].copy_from_slice(&vertex);*at+=6;}
 }
 fn quad(out:&mut[f32],at:&mut usize,v:[[f32;6];4]){triangle(out,at,[v[0],v[1],v[2]]);triangle(out,at,[v[0],v[2],v[3]]);}
 for axis in 0..3{for sign in [-1.,1.]{let u=(axis+1)%3;let v=(axis+2)%3;let mut points=[[0.;6];4];
  if rim_only&&axis==2{
   // Exactly the square previously discarded in the fragment shader. Its solid
   // core/exterior print are still separate closed geometry with depth writes.
   // Translate the hole into this mesh's pre-offset coordinates, keeping the
   // original bevel, flat normals and all optically visible material unchanged.
   let bottom=-0.5+1./15.;let top=0.5+1./15.;
   for (left,right,lo,hi) in [(-core[0],-0.5,-core[1],core[1]),(0.5,core[0],-core[1],core[1]),(-0.5,0.5,-core[1],bottom),(-0.5,0.5,top,core[1])]{
    quad(out,&mut at,[[left,lo,sign*half[2],0.,0.,sign],[right,lo,sign*half[2],0.,0.,sign],[right,hi,sign*half[2],0.,0.,sign],[left,hi,sign*half[2],0.,0.,sign]]);
   }
   continue;
  }
  for (i,(a,b)) in [(-1.,-1.),(1.,-1.),(1.,1.),(-1.,1.)].iter().copied().enumerate(){
   points[i][axis]=sign*half[axis];points[i][u]=a*core[u];points[i][v]=b*core[v];points[i][axis+3]=sign;
  }quad(out,&mut at,points);
 }}
 for a in 0..3{for b in a+1..3{let c=3-a-b;for sa in [-1.,1.]{for sb in [-1.,1.]{
  let mut points=[[0.;6];4];
  for (i,(side,end)) in [(false,-1.),(true,-1.),(true,1.),(false,1.)].iter().copied().enumerate(){
   points[i][a]=sa*if side{core[a]}else{half[a]};points[i][b]=sb*if side{half[b]}else{core[b]};points[i][c]=end*core[c];
   points[i][if side{b+3}else{a+3}]=if side{sb}else{sa};
  }quad(out,&mut at,points);
 }}}}
 for sx in [-1.,1.]{for sy in [-1.,1.]{for sz in [-1.,1.]{let sign=[sx,sy,sz];let mut points=[[0.;6];3];
  for axis in 0..3{for i in 0..3{points[axis][i]=sign[i]*if i==axis{half[i]}else{core[i]};}points[axis][axis+3]=sign[axis];}
  triangle(out,&mut at,points);
 }}}
 Some(at/6)
}

#[cfg(test)]mod tests{use super::*;
 #[test]fn indexed_triangles_reconstruct_every_original_float_bit(){
  for kind in 0..=6{
   let mut original=vec![0.;MAX_FLOATS];let count=mesh(kind,&mut original).unwrap();
   let mut compact=vec![0.;MAX_FLOATS];let mut indices=vec![0;MAX_FLOATS/6];
   let (unique,n)=indexed_mesh(kind,&mut compact,&mut indices).unwrap();
   assert_eq!(n,count);assert!(unique<count);
   for (i,&index) in indices[..n].iter().enumerate(){assert!((index as usize)<unique);
    for k in 0..6{assert_eq!(compact[index as usize*6+k].to_bits(),original[i*6+k].to_bits(),"kind {kind}, vertex {i}, component {k}");}
   }
   println!("mesh {kind}: {count} triangle vertices -> {unique} unique, {n} unchanged indices");
  }
  assert!(indexed_mesh(-1,&mut vec![0.;MAX_FLOATS],&mut vec![0;MAX_FLOATS/6]).is_none());
  assert!(indexed_mesh(6,&mut [0.;6],&mut [0;168]).is_none());
  assert!(indexed_mesh(6,&mut vec![0.;MAX_FLOATS],&mut [0;167]).is_none());
 }
 #[test]fn closed_volume_normals_and_margins(){let mut out=vec![0.;MAX_FLOATS];assert!((TOP-0.5-0.1).abs()<1e-6);assert!((-0.5-BOTTOM-7./30.).abs()<1e-6);for kind in 0..3{let count=mesh(kind,&mut out).unwrap();assert!(count>0&&count*6<=MAX_FLOATS);for v in out[..count*6].chunks_exact(6){assert!(v.iter().all(|x|x.is_finite()));assert!((v[3]*v[3]+v[4]*v[4]+v[5]*v[5]-1.).abs()<1e-5);}}assert!(mesh(2,&mut[0.;6]).is_none());}
 #[test]fn glass_extents_match_hit_volume(){
  assert!((THICK*30.-1.7).abs()<1e-6);let mut out=vec![0.;MAX_FLOATS];
  for kind in [1,2]{let count=mesh(kind,&mut out).unwrap();let mut lo=f32::INFINITY;let mut hi=f32::NEG_INFINITY;for v in out[..count*6].chunks_exact(6){lo=lo.min(v[2]);hi=hi.max(v[2]);}assert!((lo+THICK/2.).abs()<1e-6);assert!((hi-THICK/2.).abs()<1e-6);}
 }

 #[test]fn opaque_album_core_fills_the_entire_new_thickness(){
  let mut out=vec![0.;MAX_FLOATS];let n=mesh(4,&mut out).unwrap();assert_eq!(n,36);
  let mut lo=[f32::INFINITY;3];let mut hi=[f32::NEG_INFINITY;3];
  for p in out[..n*6].chunks_exact(6){for axis in 0..3{lo[axis]=lo[axis].min(p[axis]);hi[axis]=hi[axis].max(p[axis]);}
   assert!((p[3]*p[3]+p[4]*p[4]+p[5]*p[5]-1.).abs()<1e-6);
  }
  assert_eq!(lo,[-0.5,-0.5,-THICK/2.]);assert_eq!(hi,[0.5,0.5,THICK/2.]);assert!(PRINT_Z>hi[2]);
 }
 #[test]fn background_bevel_is_closed_outward_and_keeps_flat_faces(){
  let mut out=vec![0.;MAX_FLOATS];let n=mesh(5,&mut out).unwrap();assert_eq!(n,132);
  let mut edges=std::collections::HashMap::new();
  for triangle in out[..n*6].chunks_exact(18){
   let points:Vec<[u32;3]>=triangle.chunks_exact(6).map(|v|[v[0].to_bits(),v[1].to_bits(),v[2].to_bits()]).collect();
   for (a,b) in [(0,1),(1,2),(2,0)]{let edge=if points[a]<points[b]{(points[a],points[b])}else{(points[b],points[a])};*edges.entry(edge).or_insert(0)+=1;}
   let u=[triangle[6]-triangle[0],triangle[7]-triangle[1],triangle[8]-triangle[2]];
   let v=[triangle[12]-triangle[0],triangle[13]-triangle[1],triangle[14]-triangle[2]];
   let cross=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]];
   assert!((0..3).map(|i|cross[i]*triangle[i+3]).sum::<f32>()>0.);
  }
  assert!(edges.values().all(|&count|count==2),"bevel must not open seams");
  for face in out[..36*6].chunks_exact(6*6){let normal=&face[3..6];for p in face.chunks_exact(6){assert_eq!(&p[3..6],normal);}}
  assert!(mesh(5,&mut [0.;6]).is_none());
 }
 #[test]fn rim_mesh_removes_only_shader_discarded_print_area(){
  let mut old=vec![0.;MAX_FLOATS];let mut rim=vec![0.;MAX_FLOATS];
  let a=mesh(5,&mut old).unwrap();let b=mesh(6,&mut rim).unwrap();assert_eq!(b,168);
  // All edge strips and corner triangles stay byte-identical.
  assert_eq!(&old[36*6..a*6],&rim[72*6..b*6]);
  fn area(vertices:&[f32])->f32{vertices.chunks_exact(18).map(|t|{
   let u=[t[6]-t[0],t[7]-t[1],t[8]-t[2]];let v=[t[12]-t[0],t[13]-t[1],t[14]-t[2]];
   let n=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]];
   assert!((0..3).map(|i|n[i]*t[i+3]).sum::<f32>()>0.);
   n.iter().map(|x|x*x).sum::<f32>().sqrt()*0.5
  }).sum()}
  assert!((area(&old[..a*6])-area(&rim[..b*6])-2.).abs()<1e-5);
  // No remaining front/back triangle crosses the printed square's interior.
  for t in rim[..72*6].chunks_exact(18){if t[5].abs()>0.92{
   let points:Vec<_>=t.chunks_exact(6).collect();
   assert!([0,1].iter().any(|&axis|points.iter().all(|v|v[axis]<=-0.5+1e-6)||points.iter().all(|v|v[axis]>=0.5-1e-6)));
  }}
  assert!(mesh(6,&mut[0.;132*6]).is_none());
 }
 #[test]fn exterior_print_is_clear_of_glass_and_faces_outward(){let mut out=vec![0.;MAX_FLOATS];let n=mesh(3,&mut out).unwrap();assert_eq!(n,12);for t in out[..n*6].chunks_exact(18){assert!(t[2].abs()>THICK/2.);let ab=[t[6]-t[0],t[7]-t[1]];let ac=[t[12]-t[0],t[13]-t[1]];assert!((ab[0]*ac[1]-ab[1]*ac[0])*t[5]>0.);for v in t.chunks_exact(6){assert!(v[0].abs()<=0.5&&v[1].abs()<=0.5);assert_eq!(v[2].abs(),PRINT_Z);}}}

}
