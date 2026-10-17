#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <math.h>
#include "saanotts.h"
#include "saanotts_stream.h"
#include "g2p.h"
#include "jdict.h"
#include "saan_kanji.h"

typedef struct {
    void *blob; void *memory; void *dictionary_blob;
    jdict_t dictionary;
    saan_weights weights; saan_arena arena; saan_stream stream;
    int32_t ids[1024]; int started;
} Engine;
static void fail(JNIEnv *env,const char *message) {
    (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalStateException"),message);
}
static Engine *engine(jlong h) { return (Engine *)(intptr_t)h; }
JNIEXPORT jlong JNICALL Java_jp_stackchan_localvoicepoc_sano_NativeSano_open(JNIEnv *env,jclass cls,jstring path) {
    (void)cls;const char *p=(*env)->GetStringUTFChars(env,path,NULL);
    if(!p)return 0;
    FILE *f=fopen(p,"rb");(*env)->ReleaseStringUTFChars(env,path,p);
    if(!f){fail(env,"Cannot open local sano weights");return 0;}
    fseek(f,0,SEEK_END);long n=ftell(f);rewind(f);
    if(n!=654032){fclose(f);fail(env,"Unexpected sano weight size");return 0;}
    Engine *e=calloc(1,sizeof(*e));
    if(!e){fclose(f);fail(env,"Allocation failed");return 0;}
    e->blob=malloc((size_t)n);e->memory=malloc(512*1024);
    int ok=e->blob&&e->memory&&fread(e->blob,1,(size_t)n,f)==(size_t)n;fclose(f);
    if(!ok||saan_weights_open(&e->weights,e->blob,(size_t)n)!=SAAN_OK){free(e->blob);free(e->memory);free(e);fail(env,"Invalid sano weights");return 0;}
    saan_arena_init(&e->arena,e->memory,512*1024);
    return (jlong)(intptr_t)e;
}
JNIEXPORT void JNICALL Java_jp_stackchan_localvoicepoc_sano_NativeSano_loadDictionary(JNIEnv *env,jclass cls,jlong handle,jstring path) {
    (void)cls;Engine *e=engine(handle);if(!e){fail(env,"Closed engine");return;}
    const char *p=(*env)->GetStringUTFChars(env,path,NULL);if(!p)return;
    FILE *f=fopen(p,"rb");(*env)->ReleaseStringUTFChars(env,path,p);
    if(!f){fail(env,"Cannot open pinned Japanese dictionary");return;}
    fseek(f,0,SEEK_END);long n=ftell(f);rewind(f);
    if(n!=13702320){fclose(f);fail(env,"Unexpected Japanese dictionary size");return;}
    void *blob=malloc((size_t)n);jdict_t dictionary;
    int ok=blob&&fread(blob,1,(size_t)n,f)==(size_t)n;fclose(f);
    if(!ok||jdict_open(&dictionary,blob,(size_t)n)!=0){free(blob);fail(env,"Invalid Japanese dictionary");return;}
    free(e->dictionary_blob);e->dictionary_blob=blob;e->dictionary=dictionary;
    if (!saan_kanji_init()) { fail(env,"Japanese G2P initialization failed"); return; }
}
JNIEXPORT jint JNICALL Java_jp_stackchan_localvoicepoc_sano_NativeSano_startJapanese(JNIEnv *env,jclass cls,jlong handle,jbyteArray text) {
    (void)cls;Engine *e=engine(handle);if(!e||!e->dictionary_blob){fail(env,"Load the pinned Japanese dictionary first");return 0;}
    jsize bytes=(*env)->GetArrayLength(env,text);
    if(bytes<=0 || bytes>=SAAN_KANJI_KEY_MAX){fail(env,"Japanese text exceeds frontend byte limit");return 0;}
    jbyte *s=(*env)->GetByteArrayElements(env,text,NULL);if(!s)return 0;
    if(memchr(s,0,(size_t)bytes)){(*env)->ReleaseByteArrayElements(env,text,s,JNI_ABORT);fail(env,"NUL in Japanese text");return 0;}
    int32_t n=0;int tokens=0;e->started=0;
    /* Kotlin serializes native calls process-wide, including shared frontend scratch. */
    saan_kanji_status status=saan_kanji_to_ids(&e->dictionary,(const char *)s,(size_t)bytes,e->memory,512*1024,e->ids,1024,&n,&tokens);
    (*env)->ReleaseByteArrayElements(env,text,s,JNI_ABORT);
    if(status!=SAAN_KANJI_OK||n<=0){fail(env,status==SAAN_KANJI_ERR_TOO_LONG?"Japanese text exceeds the upstream 44-token frontend limit; shorten this sentence":saan_kanji_strerror(status));return 0;}
    saan_arena_reset(&e->arena);memset(&e->stream,0,sizeof(e->stream));
    if(saan_stream_init(&e->stream,&e->weights,&e->arena,e->ids,n,1.0f)!=SAAN_OK){fail(env,"Japanese sano stream init failed");return 0;}
    e->started=1;return e->stream.n_frames;
}
JNIEXPORT jfloatArray JNICALL Java_jp_stackchan_localvoicepoc_sano_NativeSano_pull(JNIEnv *env,jclass cls,jlong handle) {
    (void)cls;Engine *e=engine(handle);if(!e||!e->started){fail(env,"No active stream");return NULL;}
    const float *pcm=NULL;int32_t n=0;
    if(saan_stream_pull_ptr(&e->stream,&pcm,&n)!=SAAN_OK){fail(env,"sano pull failed");return NULL;}
    /* The upstream API returns hops, despite the header's ambiguous n_out name. */
    if(n<0||n>SAAN_CHUNK){fail(env,"Invalid hop count");return NULL;}
    n*=SAAN_HOP;
    for(int32_t i=0;i<n;i++)if(!isfinite(pcm[i])){fail(env,"Nonfinite sano PCM");return NULL;}
    jfloatArray a=(*env)->NewFloatArray(env,n);
    if(a&&n)(*env)->SetFloatArrayRegion(env,a,0,n,pcm);
    return a;
}
JNIEXPORT jlong JNICALL Java_jp_stackchan_localvoicepoc_sano_NativeSano_arenaPeak(JNIEnv *env,jclass cls,jlong handle) {
    (void)env;(void)cls;Engine *e=engine(handle);return e?(jlong)e->arena.peak:0;
}
JNIEXPORT void JNICALL Java_jp_stackchan_localvoicepoc_sano_NativeSano_close(JNIEnv *env,jclass cls,jlong handle) {
    (void)env;(void)cls;Engine *e=engine(handle);if(e){free(e->blob);free(e->memory);free(e->dictionary_blob);free(e);}
}
