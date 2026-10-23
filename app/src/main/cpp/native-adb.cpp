#include <jni.h>
#include <string>
#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <cstring>
#include <fcntl.h>

#define LOG_TAG "ERIX_NDK"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_erix_ptp_ptp_AdbNative_testConnection(JNIEnv *env, jobject thiz, jint fd) {
    if (fd < 0) {
        LOGE("Invalid FD: %d", fd);
        return JNI_FALSE;
    }
    LOGI("Menerima USB File Descriptor: %d", fd);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_erix_ptp_ptp_AdbNative_bulkTransferNative(
        JNIEnv *env, jobject thiz,
        jint fd, jint epAddr, jboolean isIn,
        jbyteArray buffer, jint offset, jint length, jint timeout) {

    // Siapkan buffer C++
    jbyte *buf = env->GetByteArrayElements(buffer, nullptr);
    if (!buf) return -1;

    // Siapkan struct USBDEVFS_BULK untuk ioctl
    struct usbdevfs_bulktransfer bulk;
    bulk.ep = epAddr;
    bulk.len = length;
    bulk.timeout = timeout;
    bulk.data = buf + offset;

    // Jalankan ioctl
    int r = ioctl(fd, USBDEVFS_BULK, &bulk);
    if (r < 0) {
        // Catat error jika perlu
    }

    // Kembalikan buffer ke Java
    env->ReleaseByteArrayElements(buffer, buf, 0);
    return r;
}
