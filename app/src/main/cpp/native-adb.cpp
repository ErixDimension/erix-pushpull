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

static int usb_fd = -1;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_erix_ptp_ptp_AdbNative_initUsb(JNIEnv *env, jobject thiz, jint fd, jint outEp, jint inEp) {
    usb_fd = fd;
    LOGI("initUsb: fd=%d, out=0x%02X, in=0x%02X", fd, outEp, inEp);
    if (fd < 0) return JNI_FALSE;

    // 1. Reset device (ini yang belum pernah kita coba!)
    int r = ioctl(fd, USBDEVFS_RESET, 0);
    LOGI("USBDEVFS_RESET: %d (errno=%d)", r, errno);
    usleep(200000); // 200ms

    // 2. Reset endpoint IN (clear stall)
    int ep = inEp;
    r = ioctl(fd, USBDEVFS_RESETEP, &ep);
    LOGI("USBDEVFS_RESETEP(IN): %d (errno=%d)", r, errno);

    // 3. Reset endpoint OUT
    ep = outEp;
    r = ioctl(fd, USBDEVFS_RESETEP, &ep);
    LOGI("USBDEVFS_RESETEP(OUT): %d (errno=%d)", r, errno);

    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_erix_ptp_ptp_AdbNative_bulkTransfer(
        JNIEnv *env, jobject thiz,
        jint epAddr, jbyteArray buffer, jint offset, jint length, jint timeout) {

    if (usb_fd < 0) {
        LOGE("bulkTransfer: usb_fd invalid");
        return -1;
    }
    jbyte *buf = env->GetByteArrayElements(buffer, nullptr);
    if (!buf) return -1;

    struct usbdevfs_bulktransfer bulk;
    bulk.ep = epAddr;
    bulk.len = length;
    bulk.timeout = timeout;
    bulk.data = buf + offset;

    int r = ioctl(usb_fd, USBDEVFS_BULK, &bulk);
    
    if (r < 0) {
        LOGE("bulk ep=0x%02X len=%d: errno=%d (%s)", epAddr, length, errno, strerror(errno));
    }
    
    env->ReleaseByteArrayElements(buffer, buf, 0);
    return r;
}
