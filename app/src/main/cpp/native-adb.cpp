#include <jni.h>
#include <string>
#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <cstring>
#include <fcntl.h>
#include <errno.h>

#define LOG_TAG "ERIX_NDK"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_erix_ptp_ptp_AdbNative_testConnection(JNIEnv *env, jobject thiz, jint fd) {
    if (fd < 0) return JNI_FALSE;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_erix_ptp_ptp_AdbNative_bulkTransferNative(
        JNIEnv *env, jobject thiz,
        jint fd, jint epAddr, jboolean isIn,
        jbyteArray buffer, jint offset, jint length, jint timeout) {

    // Batasi maksimal transfer agar tidak stall (Max 16KB)
    jint safeLen = length > 16384 ? 16384 : length;

    jbyte *buf = env->GetByteArrayElements(buffer, nullptr);
    if (!buf) return -1;

    struct usbdevfs_bulktransfer bulk;
    bulk.ep = epAddr;
    bulk.len = safeLen;
    bulk.timeout = timeout;
    bulk.data = buf + offset;

    int r = ioctl(fd, USBDEVFS_BULK, &bulk);
    
    // Jika error STALL (-EPIPE), bersihkan dan coba lagi sekali
    if (r < 0 && errno == EPIPE) {
        LOGI("Endpoint 0x%X stall, clear halt...", epAddr);
        ioctl(fd, USBDEVFS_CLEAR_HALT, &epAddr);
        r = ioctl(fd, USBDEVFS_BULK, &bulk);
    }

    if (r < 0) {
        LOGE("ioctl bulk gagal: errno %d", errno);
    }

    env->ReleaseByteArrayElements(buffer, buf, 0);
    return r;
}
