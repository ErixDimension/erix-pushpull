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
Java_com_erix_ptp_ptp_AdbNative_initUsb(JNIEnv *env, jobject thiz, jint fd, jint intfIndex) {
    if (!ctx) libusb_init(&ctx);
    if (dev_handle) { libusb_close(dev_handle); dev_handle = nullptr; }

    int r = libusb_wrap_sys_device(ctx, (intptr_t)fd, &dev_handle);
    if (r < 0 || !dev_handle) {
        LOGE("wrap FD failed: %d", r);
        return JNI_FALSE;
    }

    // Coba detach kernel driver Android (usbfs)
    libusb_set_auto_detach_kernel_driver(dev_handle, 1);

    // Klaim interface untuk libusb
    r = libusb_claim_interface(dev_handle, intfIndex);
    if (r < 0) {
        LOGE("claim interface %d failed: %d", intfIndex, r);
        return JNI_FALSE;
    }

    LOGI("libusb init OK (interface %d claimed)", intfIndex);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_erix_ptp_ptp_AdbNative_bulkTransfer(JNIEnv *env, jobject thiz, jint epAddr, jbyteArray buffer, jint offset, jint length, jint timeout) {
    if (!dev_handle) return -1; // Anti FC
    
    jbyte *buf = env->GetByteArrayElements(buffer, nullptr);
    if (!buf) return -1;
    
    int actual = 0;
    int r = libusb_bulk_transfer(dev_handle, (unsigned char)epAddr, (unsigned char*)(buf + offset), length, &actual, timeout);
    
    env->ReleaseByteArrayElements(buffer, buf, 0);
    
    if (r < 0) {
        // Jangan log setiap kali timeout/error, terlalu berisik
        return -1;
    }
    return actual;
}
