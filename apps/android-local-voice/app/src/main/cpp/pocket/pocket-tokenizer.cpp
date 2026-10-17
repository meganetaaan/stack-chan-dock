#include <jni.h>
#include <sentencepiece_processor.h>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

static void fail(JNIEnv *env,const std::exception &e){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());}
static std::string utf8(JNIEnv *env,jbyteArray bytes){std::string s(env->GetArrayLength(bytes),'\0');env->GetByteArrayRegion(bytes,0,s.size(),reinterpret_cast<jbyte*>(s.data()));return s;}
extern "C" JNIEXPORT jlong JNICALL Java_dev_irte_voice_PocketTokenizer_open(JNIEnv *env,jobject,jbyteArray path){
    try {auto p=std::make_unique<sentencepiece::SentencePieceProcessor>();auto result=p->Load(utf8(env,path));if(!result.ok())throw std::runtime_error(result.ToString());if(p->GetPieceSize()!=4000)throw std::runtime_error("Expected Japanese 4000-piece tokenizer");return reinterpret_cast<jlong>(p.release());}catch(const std::exception &e){fail(env,e);return 0;}
}
extern "C" JNIEXPORT jintArray JNICALL Java_dev_irte_voice_PocketTokenizer_encode(JNIEnv *env,jobject,jlong handle,jbyteArray text){
    try {auto p=reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);if(!p)throw std::runtime_error("Tokenizer closed");std::vector<int> ids;auto result=p->Encode(utf8(env,text),&ids);if(!result.ok())throw std::runtime_error(result.ToString());auto out=env->NewIntArray(ids.size());if(out)env->SetIntArrayRegion(out,0,ids.size(),ids.data());return out;}catch(const std::exception &e){fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_irte_voice_PocketTokenizer_decode(JNIEnv *env,jobject,jlong handle,jintArray tokens){
    try {auto p=reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);if(!p)throw std::runtime_error("Tokenizer closed");std::vector<int> ids(env->GetArrayLength(tokens));env->GetIntArrayRegion(tokens,0,ids.size(),ids.data());std::string text;auto result=p->Decode(ids,&text);if(!result.ok())throw std::runtime_error(result.ToString());auto out=env->NewByteArray(text.size());if(out)env->SetByteArrayRegion(out,0,text.size(),reinterpret_cast<const jbyte*>(text.data()));return out;}catch(const std::exception &e){fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_dev_irte_voice_PocketTokenizer_close(JNIEnv*,jobject,jlong handle){delete reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);}
