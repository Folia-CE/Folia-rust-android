// Own a normal shared mixer session; FAST/MMAP can bypass the DP insert.
#include <aaudio/AAudio.h>
#include <dlfcn.h>
#include <cstdint>
#include <new>
#include <algorithm>
#include <atomic>
struct FoliaOutput {
 AAudioStream* stream=nullptr;void* owner;
 int device=0,burst=0,buffer=0,xruns=0;
 std::atomic<bool> closing{false};
 void(*render)(void*,float*,int,int);void(*error)(void*,int);
};
extern "C" void* folia_output_open(void* owner,void(*render)(void*,float*,int,int),void(*error)(void*,int),int* session){
 auto* p=new(std::nothrow) FoliaOutput{};if(!p)return nullptr;p->owner=owner;p->render=render;p->error=error;
 AAudioStreamBuilder* b=nullptr;if(AAudio_createStreamBuilder(&b)!=AAUDIO_OK){delete p;return nullptr;}
 AAudioStreamBuilder_setDirection(b,AAUDIO_DIRECTION_OUTPUT);
 AAudioStreamBuilder_setSampleRate(b,48000);AAudioStreamBuilder_setChannelCount(b,2);
 AAudioStreamBuilder_setFormat(b,AAUDIO_FORMAT_PCM_FLOAT);
 AAudioStreamBuilder_setSharingMode(b,AAUDIO_SHARING_MODE_SHARED);
 AAudioStreamBuilder_setPerformanceMode(b,AAUDIO_PERFORMANCE_MODE_NONE);
 // Session/usage are API 28. Resolve dynamically to keep API 26/27 loadable.
 auto setSession=reinterpret_cast<void(*)(AAudioStreamBuilder*,int)>(dlsym(RTLD_DEFAULT,"AAudioStreamBuilder_setSessionId"));
 auto getSession=reinterpret_cast<int(*)(AAudioStream*)>(dlsym(RTLD_DEFAULT,"AAudioStream_getSessionId"));
 auto setUsage=reinterpret_cast<void(*)(AAudioStreamBuilder*,int)>(dlsym(RTLD_DEFAULT,"AAudioStreamBuilder_setUsage"));
 auto setContent=reinterpret_cast<void(*)(AAudioStreamBuilder*,int)>(dlsym(RTLD_DEFAULT,"AAudioStreamBuilder_setContentType"));
 if(setSession&&getSession)setSession(b,0); // AAUDIO_SESSION_ID_ALLOCATE
 if(setUsage)setUsage(b,1);if(setContent)setContent(b,2); // MEDIA / MUSIC
 AAudioStreamBuilder_setDataCallback(b,[](AAudioStream* stream,void* user,void* data,int32_t frames)->aaudio_data_callback_result_t{
  auto* p=static_cast<FoliaOutput*>(user);if(p->closing.load(std::memory_order_acquire))return AAUDIO_CALLBACK_RESULT_STOP;
  p->render(p->owner,static_cast<float*>(data),frames,AAudioStream_getDeviceId(stream));return AAUDIO_CALLBACK_RESULT_CONTINUE;
 },p);
 AAudioStreamBuilder_setErrorCallback(b,[](AAudioStream*,void* user,aaudio_result_t code){auto* p=static_cast<FoliaOutput*>(user);if(!p->closing.load(std::memory_order_acquire))p->error(p->owner,code);},p);
 auto result=AAudioStreamBuilder_openStream(b,&p->stream);AAudioStreamBuilder_delete(b);
 if(result!=AAUDIO_OK){delete p;return nullptr;}
 *session=getSession?getSession(p->stream):0;
 if(AAudioStream_getSampleRate(p->stream)!=48000||AAudioStream_getChannelCount(p->stream)!=2||AAudioStream_getFormat(p->stream)!=AAUDIO_FORMAT_PCM_FLOAT||(setSession&&*session<=0)){
  AAudioStream_close(p->stream);delete p;return nullptr;
 }
 p->device=AAudioStream_getDeviceId(p->stream);
 p->burst=std::max(1,AAudioStream_getFramesPerBurst(p->stream));
 p->buffer=AAudioStream_setBufferSizeInFrames(p->stream,p->burst*3);
 if(p->buffer<0)p->buffer=AAudioStream_getBufferSizeInFrames(p->stream);
 return p;
}
extern "C" int folia_output_start(void* p){return AAudioStream_requestStart(static_cast<FoliaOutput*>(p)->stream);}
extern "C" int folia_output_pause(void* p){return AAudioStream_requestPause(static_cast<FoliaOutput*>(p)->stream);}
extern "C" void folia_output_close(void* raw){auto* p=static_cast<FoliaOutput*>(raw);p->closing.store(true,std::memory_order_release);AAudioStream_requestStop(p->stream);AAudioStream_close(p->stream);delete p;}

// Called only on the control thread. Never resize buffers, allocate or lock in the callback.
extern "C" void folia_output_maintain(void* raw){
 auto* p=static_cast<FoliaOutput*>(raw);if(p->closing.load())return;
 const int count=AAudioStream_getXRunCount(p->stream);
 if(count>p->xruns&&p->buffer<p->burst*8){
  const int result=AAudioStream_setBufferSizeInFrames(p->stream,std::min(p->burst*8,p->buffer+p->burst));
  if(result>0)p->buffer=result;
 }
 if(count>=0)p->xruns=count;
}
