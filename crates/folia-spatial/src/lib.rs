mod jni; mod icolor;
use jni::{array_length,direct_range,Critical,Env,JObject};
const FALSE:u8=0; const TRUE:u8=1;
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeCoverAccent(
    env: Env, _class: JObject, pixels: JObject,
) -> i32 {
    unsafe {
        let n=array_length(env,pixels);
        if n==0{return 0}
        let Some(mut held)=Critical::<i32>::get(env,pixels,n) else{return 0};
        icolor::cover_accent(held.slice()) as i32
    }
}
pub mod daily_spatial;
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeCoverClusters(
    env:Env,_class:JObject,pixels:JObject,width:i32,height:i32,output:JObject,
)->u8 {
    if width<=0||height<=0{return FALSE}
    let Some(count)=(width as usize).checked_mul(height as usize)else{return FALSE};
    unsafe {
        if array_length(env,pixels)<count||array_length(env,output)<12{return FALSE}
        let colors={
            let Some(mut held)=Critical::<u32>::get(env,pixels,count)else{return FALSE};
            held.slice().to_vec()
        };
        // Run the existing SalamPlayer clustering outside JNI's pinned-array critical region.
        let clusters=icolor::extract_clusters(&colors,width as usize,height as usize);
        let Some(mut held)=Critical::<i32>::get(env,output,12)else{return FALSE};
        let out=held.slice();
        for (i,c) in clusters.iter().enumerate(){
            out[i*4]=c.color as i32;out[i*4+1]=c.x.to_bits() as i32;
            out[i*4+2]=c.y.to_bits() as i32;out[i*4+3]=c.radius.to_bits() as i32;
        }
        TRUE
    }
}
mod daily_snapshot;
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeDailySnapshotPixels(
 env:Env,_class:JObject,rgba:JObject,width:i32,height:i32,argb:JObject)->u8{
 let Some(count)=daily_snapshot::pixels(width,height)else{return FALSE};
 unsafe{
  let Some(ptr)=direct_range(env,rgba,0,count*4)else{return FALSE};
  if array_length(env,argb)<count{return FALSE}
  let Some(mut output)=Critical::<i32>::get(env,argb,count)else{return FALSE};
  if daily_snapshot::convert(std::slice::from_raw_parts(ptr,count*4),width,height,output.slice()){TRUE}else{FALSE}
 }
}

#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeDailySpatialCreate(_env:Env,_class:JObject)->i64{daily_spatial::create()}
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeDailySpatialRelease(_env:Env,_class:JObject,id:i64){daily_spatial::release(id)}
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeDailySpatialCommand(_env:Env,_class:JObject,id:i64,op:i32,index:i32,a:f32,b:f32)->u8{if daily_spatial::command(id,op,index,a,b){TRUE}else{FALSE}}
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeDailySpatialHit(_env:Env,_class:JObject,id:i64,x:f32,y:f32)->i32{daily_spatial::hit(id,x,y)}
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeDailySpatialStep(env:Env,_class:JObject,id:i64,dt:f32,w:f32,h:f32,output:JObject)->u8{
 unsafe{
  let Some(ptr)=direct_range(env,output,0,daily_spatial::FLOATS*4)else{return FALSE};
  if (ptr as usize)%std::mem::align_of::<f32>()!=0{return FALSE}
  let out=std::slice::from_raw_parts_mut(ptr as *mut f32,daily_spatial::FLOATS);
  if daily_spatial::step(id,dt,w,h,out){TRUE}else{FALSE}
 }
}

// Budget background lyric View preparation against the current display's vsync interval.
// The Java side measures real group cost; no fixed FPS, and no dropping queued lyric groups.
fn lyric_preload_quota(period_ns:i64, elapsed_ns:i64, cost_ns:i64, remaining:i32)->i32 {
    if remaining<=0 || period_ns<=0 { return 0; }
    let reserve=period_ns/2;
    let available=(period_ns-elapsed_ns.max(0)-reserve).max(0);
    let budget=(period_ns/8).clamp(250_000,2_000_000).min(available);
    if budget<250_000 { return 0; }
    (budget/cost_ns.max(100_000)).clamp(1,4).min(remaining as i64) as i32
}
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeLyricPreloadQuota(
    _env:Env,_class:JObject,period:i64,elapsed:i64,cost:i64,remaining:i32)->i32 {
    lyric_preload_quota(period,elapsed,cost,remaining)
}
#[cfg(test)] mod lyric_preload_tests {
    use super::*;
    #[test] fn respects_vsync_and_never_discards_remaining_work(){
        assert_eq!(lyric_preload_quota(8_333_333,7_000_000,300_000,50),0);
        assert_eq!(lyric_preload_quota(8_333_333,500_000,300_000,50),3);
        assert_eq!(lyric_preload_quota(16_666_667,500_000,300_000,50),4);
        assert_eq!(lyric_preload_quota(8_333_333,0,2_000_000,1),1);
        assert_eq!(lyric_preload_quota(8_333_333,0,300_000,0),0);
    }
}

mod render_policy;
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeFrameSubmission(
 _env:Env,_class:JObject,frame:i64,deadline:i64,now:i64,last:i64,cost:i64,waiting:u8)->i32{
 render_policy::submission(frame,deadline,now,last,cost,waiting!=0)
}
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeFrameDrawCost(
 _env:Env,_class:JObject,previous:i64,sample:i64)->i64{render_policy::draw_cost(previous,sample)}
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeLoudnessRamp(
 _env:Env,_class:JObject,from:i32,to:i32,elapsed:i64,duration:i64)->i32{render_policy::loudness_ramp(from,to,elapsed,duration)}

#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeFrameUploadAllowance(
 _env:Env,_class:JObject,deadline:i64,now:i64,cost:i64)->i32{render_policy::upload_allowance(deadline,now,cost)}

#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeDailyBlurRegions(_env:Env,_class:JObject,h:i32,d:f32,t:f32,b:f32,r:f32)->i64{render_policy::blur_regions(h,d,t,b,r)}
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeDailyMeshLevel(_env:Env,_class:JObject,s:f32,r:f32,d:f32)->i32{render_policy::mesh_level(s,r,d)}

#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeDailyPackInstances(
 env:Env,_class:JObject,input:JObject,bindings:JObject,output:JObject,density:f32)->i64{
 unsafe{
  let in_len=daily_spatial::FLOATS*4;let bind_len=daily_spatial::SLOTS*5*4;let out_len=daily_spatial::SLOTS*16*4;
  let Some(a)=direct_range(env,input,0,in_len)else{return -1};
  let Some(b)=direct_range(env,bindings,0,bind_len)else{return -1};
  let Some(c)=direct_range(env,output,0,out_len)else{return -1};
  let(a0,b0,c0)=(a as usize,b as usize,c as usize);
  if a0%4!=0||b0%4!=0||c0%4!=0||(c0<a0+in_len&&a0<c0+out_len)||(c0<b0+bind_len&&b0<c0+out_len){return -1}
  let f=std::slice::from_raw_parts(a as *const f32,in_len/4);let slots=std::slice::from_raw_parts(b as *const i32,bind_len/4);
  let out=std::slice::from_raw_parts_mut(c as *mut f32,out_len/4);
  match render_policy::pack_instances_tagged(f,slots,out,density){Some((n,lift))=>(n[0]as i64)|((n[1]as i64)<<10)|((n[2]as i64)<<20)|lift as i64,None=>-1}
 }
}

#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeDailyContextBlurKernel(
 env:Env,_class:JObject,radius:f32,density:f32,output:JObject)->u8{
 unsafe{
  let Some(ptr)=direct_range(env,output,0,14*4)else{return FALSE};
  if ptr as usize%4!=0{return FALSE}
  let out=std::slice::from_raw_parts_mut(ptr as *mut f32,14);
  if render_policy::context_blur_kernel(radius,density,out){TRUE}else{FALSE}
 }
}

#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeDailyRhineMesh(env:Env,_class:JObject,kind:i32,output:JObject)->i32{
 unsafe{let size=daily_spatial::rhine_mesh::MAX_FLOATS;let Some(p)=direct_range(env,output,0,size*4)else{return -1};if p as usize%4!=0{return -1}daily_spatial::rhine_mesh::mesh(kind,std::slice::from_raw_parts_mut(p as *mut f32,size)).map(|n|n as i32).unwrap_or(-1)}
}
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeDailyRhineIndexedMesh(env:Env,_class:JObject,kind:i32,vertices:JObject,indices:JObject)->i64{
 unsafe{
  let floats=daily_spatial::rhine_mesh::MAX_FLOATS;let vl=floats*4;let il=floats/6*2;
  let Some(v)=direct_range(env,vertices,0,vl)else{return -1};
  let Some(i)=direct_range(env,indices,0,il)else{return -1};
  let(x,y)=(v as usize,i as usize);
  if x%4!=0||y%2!=0||(x<y+il&&y<x+vl){return -1}
  daily_spatial::rhine_mesh::indexed_mesh(kind,std::slice::from_raw_parts_mut(v as *mut f32,floats),std::slice::from_raw_parts_mut(i as *mut u16,il/2))
   .map(|(vertices,indices)|((indices as i64)<<32)|vertices as i64).unwrap_or(-1)
 }
}
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeDailyRhinePack(env:Env,_class:JObject,scene:JObject,bindings:JObject,output:JObject,foreground:u8)->i32{
 unsafe{let il=daily_spatial::FLOATS*4;let bl=daily_spatial::SLOTS*5*4;let ol=if foreground!=0{daily_spatial::rhine_gpu::INSTANCE*4}else{daily_spatial::SLOTS*daily_spatial::rhine_gpu::INSTANCE*4};let Some(a)=direct_range(env,scene,0,il)else{return -1};let Some(b)=direct_range(env,bindings,0,bl)else{return -1};let Some(c)=direct_range(env,output,0,ol)else{return -1};let(x,y,z)=(a as usize,b as usize,c as usize);if x%4!=0||y%4!=0||z%4!=0||(z<x+il&&x<z+ol)||(z<y+bl&&y<z+ol){return -1}daily_spatial::rhine_gpu::pack(std::slice::from_raw_parts(a as *const f32,il/4),std::slice::from_raw_parts(b as *const i32,bl/4),std::slice::from_raw_parts_mut(c as *mut f32,ol/4),foreground!=0).map(|n|n as i32).unwrap_or(-1)}
}
#[no_mangle]
pub extern "system" fn Java_io_github_techuouo520_folia_spatial_SpatialNative_nativeDailyRhinePackChanged(env:Env,_class:JObject,scene:JObject,bindings:JObject,output:JObject,previous:i32)->i64{
 unsafe{
  let il=daily_spatial::FLOATS*4;let bl=daily_spatial::SLOTS*5*4;let ol=daily_spatial::SLOTS*daily_spatial::rhine_gpu::INSTANCE*4;
  let Some(a)=direct_range(env,scene,0,il)else{return -1};let Some(b)=direct_range(env,bindings,0,bl)else{return -1};let Some(c)=direct_range(env,output,0,ol)else{return -1};
  let(x,y,z)=(a as usize,b as usize,c as usize);
  if x%4!=0||y%4!=0||z%4!=0||(z<x+il&&x<z+ol)||(z<y+bl&&y<z+ol){return -1}
  daily_spatial::rhine_gpu::pack_changes(std::slice::from_raw_parts(a as *const f32,il/4),std::slice::from_raw_parts(b as *const i32,bl/4),std::slice::from_raw_parts_mut(c as *mut f32,ol/4),if previous<0{usize::MAX}else{previous as usize})
   .map(|(count,flags)|((flags as i64)<<32)|count as i64).unwrap_or(-1)
 }
}

#[path="particles/lib.rs"] mod particles;
