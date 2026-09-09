// Real-ESRGAN JNI bridge for Android (Vulkan GPU, ncnn)
// Loads models from app files, processes via GPU, returns byte buffer

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>

#include "realesrgan.h"

#define TAG "ImagineJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static RealESRGAN* g_realesrgan = nullptr;
static bool g_gpu_init = false;
static int g_scale = 4;
static int g_out_width = 0;
static int g_out_height = 0;
static int g_channels = 3;

static unsigned char* g_outputBuf = nullptr;
static size_t g_outputBufSize = 0;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
        return -1;
    return JNI_VERSION_1_6;
}

// initialize GPU instance
extern "C" JNIEXPORT jboolean JNICALL
Java_com_kaminari_imagine_Engine_initGpu(JNIEnv* env, jobject thiz) {
    if (g_gpu_init) return JNI_TRUE;
    ncnn::create_gpu_instance();
    int gpu_count = ncnn::get_gpu_count();
    if (gpu_count <= 0) {
        LOGE("No Vulkan GPU found");
        return JNI_FALSE;
    }
    LOGI("Vulkan GPU initialized, count=%d", gpu_count);
    g_gpu_init = true;
    return JNI_TRUE;
}

// get number of vulkan devices
extern "C" JNIEXPORT jint JNICALL
Java_com_kaminari_imagine_Engine_gpuCount(JNIEnv* env, jobject thiz) {
    if (!g_gpu_init) return 0;
    return ncnn::get_gpu_count();
}

// load model from files on disk (extracted from assets by Kotlin side)
// paramPath/binPath: absolute paths. scale: model native scale.
// tilesize: 0 = auto based on GPU heap budget
extern "C" JNIEXPORT jboolean JNICALL
Java_com_kaminari_imagine_Engine_loadModel(JNIEnv* env, jobject thiz,
                                           jstring paramPath, jstring binPath,
                                           jint scale, jint tilesize, jint prepadding) {
    if (!g_gpu_init) {
        LOGE("GPU not initialized");
        return JNI_FALSE;
    }
    if (g_realesrgan) {
        delete g_realesrgan;
        g_realesrgan = nullptr;
    }

    const char* cParam = env->GetStringUTFChars(paramPath, nullptr);
    const char* cBin = env->GetStringUTFChars(binPath, nullptr);

    g_realesrgan = new RealESRGAN(ncnn::get_default_gpu_index(), false);

    // choose tile size automatically like upstream main.cpp
    int tile = tilesize;
    if (tile <= 0) {
        uint32_t heap_budget = ncnn::get_gpu_device(ncnn::get_default_gpu_index())->get_heap_budget();
        if (heap_budget > 1900) tile = 200;
        else if (heap_budget > 550) tile = 100;
        else if (heap_budget > 190) tile = 64;
        else tile = 32;
    }

    g_realesrgan->scale = scale;
    g_realesrgan->tilesize = tile;
    g_realesrgan->prepadding = prepadding;

    int ret = g_realesrgan->load(std::string(cParam), std::string(cBin));

    env->ReleaseStringUTFChars(paramPath, cParam);
    env->ReleaseStringUTFChars(binPath, cBin);

    if (ret != 0) {
        LOGE("Model load failed: %d", ret);
        delete g_realesrgan;
        g_realesrgan = nullptr;
        return JNI_FALSE;
    }
    g_scale = scale;
    LOGI("Model ready: scale=%d, tilesize=%d, prepadding=%d", scale, tile, prepadding);
    return JNI_TRUE;
}

// process image: RGBA byte array in, RGBA byte array out
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_kaminari_imagine_Engine_processImage(JNIEnv* env, jobject thiz,
                                              jbyteArray input, jint width, jint height) {
    if (!g_realesrgan) {
        LOGE("Model not loaded");
        return nullptr;
    }

    jsize inLen = env->GetArrayLength(input);
    if (inLen < width * height * 4) {
        LOGE("Input too small: %d < %d", (int)inLen, width * height * 4);
        return nullptr;
    }

    jbyte* inputData = env->GetByteArrayElements(input, nullptr);
    if (!inputData) {
        LOGE("Failed to get input buffer");
        return nullptr;
    }

    const int scale = g_realesrgan->scale;
    const int outW = width * scale;
    const int outH = height * scale;
    const size_t outSize = (size_t)outW * outH * 4;

    // Build input/output exactly like upstream main.cpp:
    //   inimage  = Mat(w, h, (void*)data, (size_t)c, c)
    //   outimage = Mat(w*scale, h*scale, (size_t)c, c)
    // with c=4 (RGBA) to keep alpha through the pipeline.
    ncnn::Mat inimage = ncnn::Mat(width, height, (void*)inputData, (size_t)4, 4);
    ncnn::Mat outimage = ncnn::Mat(outW, outH, (size_t)4, 4);

    LOGI("Processing %dx%d -> %dx%d (tile %d)", width, height, outW, outH, g_realesrgan->tilesize);
    int ret = g_realesrgan->process(inimage, outimage);

    env->ReleaseByteArrayElements(input, inputData, JNI_ABORT);

    if (ret != 0) {
        LOGE("Process failed: %d", ret);
        return nullptr;
    }

    jbyteArray result = env->NewByteArray((jsize)outSize);
    if (!result) {
        LOGE("Cannot allocate output array of %zu bytes", outSize);
        return nullptr;
    }
    env->SetByteArrayRegion(result, 0, (jsize)outSize, (const jbyte*)outimage.data);
    g_out_width = outW;
    g_out_height = outH;
    g_channels = 4;
    return result;
}

// cleanup
extern "C" JNIEXPORT void JNICALL
Java_com_kaminari_imagine_Engine_destroy(JNIEnv* env, jobject thiz) {
    if (g_realesrgan) {
        delete g_realesrgan;
        g_realesrgan = nullptr;
    }
    if (g_outputBuf) {
        free(g_outputBuf);
        g_outputBuf = nullptr;
        g_outputBufSize = 0;
    }
    if (g_gpu_init) {
        ncnn::destroy_gpu_instance();
        g_gpu_init = false;
    }
    LOGI("Destroyed");
}
