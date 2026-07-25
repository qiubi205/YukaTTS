#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "YukaTTS_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_yukatts_TTSEngine_nativeNormalizeAudio(
        JNIEnv *env, jobject, jfloatArray audio, jfloat targetPeak) {
    jsize len = env->GetArrayLength(audio);
    if (len <= 0) return JNI_FALSE;
    jfloat *data = env->GetFloatArrayElements(audio, nullptr);
    if (!data) return JNI_FALSE;
    float maxVal = 0.0f;
    for (jsize i = 0; i < len; i++) {
        float absVal = data[i] > 0 ? data[i] : -data[i];
        if (absVal > maxVal) maxVal = absVal;
    }
    if (maxVal > 0.0f && maxVal < targetPeak) {
        float gain = targetPeak / maxVal;
        for (jsize i = 0; i < len; i++) data[i] *= gain;
    }
    env->ReleaseFloatArrayElements(audio, data, 0);
    return JNI_TRUE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_yukatts_TTSEngine_nativeFloatToPCM16(
        JNIEnv *env, jobject, jfloatArray audio) {
    jsize len = env->GetArrayLength(audio);
    if (len <= 0) return nullptr;
    jfloat *data = env->GetFloatArrayElements(audio, nullptr);
    jsize pcmSize = len * 2;
    jbyteArray result = env->NewByteArray(pcmSize);
    jbyte *pcm = env->GetByteArrayElements(result, nullptr);
    for (jsize i = 0; i < len; i++) {
        float s = data[i];
        if (s > 1.0f) s = 1.0f;
        if (s < -1.0f) s = -1.0f;
        int16_t v = (int16_t)(s * 32767.0f);
        pcm[i * 2] = (jbyte)(v & 0xFF);
        pcm[i * 2 + 1] = (jbyte)((v >> 8) & 0xFF);
    }
    env->ReleaseFloatArrayElements(audio, data, JNI_ABORT);
    env->ReleaseByteArrayElements(result, pcm, 0);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_yukatts_TTSEngine_nativeCreateWavHeader(
        JNIEnv *env, jobject, jint dataSize, jint sampleRate,
        jint channels, jint bitsPerSample) {
    jint totalSize = dataSize + 36;
    jbyteArray h = env->NewByteArray(44);
    jbyte *b = env->GetByteArrayElements(h, nullptr);
    b[0]='R';b[1]='I';b[2]='F';b[3]='F';
    b[4]=(jbyte)(totalSize);b[5]=(jbyte)(totalSize>>8);
    b[6]=(jbyte)(totalSize>>16);b[7]=(jbyte)(totalSize>>24);
    b[8]='W';b[9]='A';b[10]='V';b[11]='E';
    b[12]='f';b[13]='m';b[14]='t';b[15]=' ';
    b[16]=16;b[17]=0;b[18]=0;b[19]=0;
    b[20]=1;b[21]=0;
    b[22]=(jbyte)channels;b[23]=(jbyte)(channels>>8);
    jint br = sampleRate * channels * bitsPerSample / 8;
    jint ba = channels * bitsPerSample / 8;
    b[24]=(jbyte)sampleRate;b[25]=(jbyte)(sampleRate>>8);
    b[26]=(jbyte)(sampleRate>>16);b[27]=(jbyte)(sampleRate>>24);
    b[28]=(jbyte)br;b[29]=(jbyte)(br>>8);
    b[30]=(jbyte)(br>>16);b[31]=(jbyte)(br>>24);
    b[32]=(jbyte)ba;b[33]=(jbyte)(ba>>8);
    b[34]=(jbyte)bitsPerSample;b[35]=(jbyte)(bitsPerSample>>8);
    b[36]='d';b[37]='a';b[38]='t';b[39]='a';
    b[40]=(jbyte)dataSize;b[41]=(jbyte)(dataSize>>8);
    b[42]=(jbyte)(dataSize>>16);b[43]=(jbyte)(dataSize>>24);
    env->ReleaseByteArrayElements(h, b, 0);
    return h;
}

}
