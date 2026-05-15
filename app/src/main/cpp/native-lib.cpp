
#include &lt;jni.h&gt;
#include &lt;android/log.h&gt;

#define LOG_TAG "AIAimAssist"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_zsywan_aiaimassist_MainActivity_detectObjects(
        JNIEnv* env,
        jobject thiz,
        jbyteArray rgb_data,
        jint width,
        jint height) {
    
    jbyte* data = env-&gt;GetByteArrayElements(rgb_data, nullptr);
    if (data == nullptr) {
        return nullptr;
    }
    
    jsize data_size = env-&gt;GetArrayLength(rgb_data);
    
    LOGI("Received frame: %dx%d, size: %d", width, height, data_size);
    
    jfloatArray result_array = env-&gt;NewFloatArray(2);
    if (result_array == nullptr) {
        env-&gt;ReleaseByteArrayElements(rgb_data, data, 0);
        return nullptr;
    }
    
    float results[2] = {0.0f, 0.0f};
    
    env-&gt;SetFloatArrayRegion(result_array, 0, 2, results);
    env-&gt;ReleaseByteArrayElements(rgb_data, data, 0);
    
    return result_array;
}

