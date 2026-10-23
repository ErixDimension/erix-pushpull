#include <jni.h>
#include <string>
#include <android/log.h>
#include <libusb.h>

#define LOG_TAG "ERIX_NDK"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static libusb_context *ctx = nullptr;
static libusb_device_handle *dev_handle = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_erix_ptp_ptp_AdbNative_testConnection(JNIEnv *env, jobject thiz, jint fd) {
    if (fd < 0) return JNI_FALSE;
    if (!ctx) libusb_init(&ctx);
    if (dev_handle) libusb_close(dev_handle);
    int r = libusb_wrap_sys_device(ctx, (intptr_t)fd, &dev_handle);
    if (r < 0) { LOGE("Gagal wrap FD: %d", r); return JNI_FALSE; }
    LOGI("libusb berhasil mengambil alih FD");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_erix_ptp_ptp_AdbNative_bulkTransferNative(
        JNIEnv *env, jobject thiz,
        jint fd, jint epAddr, jboolean isIn,
        jbyteArray buffer, jint offset, jint length, jint timeout) {

    if (!dev_handle) return -1;
    jbyte *buf = env->GetByteArrayElements(buffer, nullptr);
    if (!buf) return -1;
    int actual_length = 0;
    unsigned char endpoint = (unsigned char)epAddr;

    int r = libusb_bulk_transfer(dev_handle, endpoint, (unsigned char*)(buf + offset), length, &actual_length, timeout);

    if (r == LIBUSB_ERROR_PIPE) {
        LOGI("Stall detected, clear halt...");
        libusb_clear_halt(dev_handle, endpoint);
        r = libusb_bulk_transfer(dev_handle, endpoint, (unsigned char*)(buf + offset), length, &actual_length, timeout);
    }
    env->ReleaseByteArrayElements(buffer, buf, 0);
    if (r < 0) { LOGE("libusb transfer gagal: %d", r); return -1; }
    return actual_length;
}
