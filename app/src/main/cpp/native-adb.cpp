#include <jni.h>
#include <string>
#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <errno.h>

#define LOG_TAG "ERIX_NDK"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_erix_ptp_ptp_AdbNative_clearHalt(JNIEnv *env, jobject thiz, jint fd, jint epAddr) {
    int ep = epAddr;
    int r = ioctl(fd, USBDEVFS_CLEAR_HALT, &ep);
    if (r < 0) {
        LOGE("clearHalt ep=0x%02X failed (errno=%d)", epAddr, errno);
        return JNI_FALSE;
    }
    LOGI("clearHalt ep=0x%02X OK", epAddr);
    return JNI_TRUE;
}
