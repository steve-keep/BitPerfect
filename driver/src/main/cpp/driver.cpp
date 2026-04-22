#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <unistd.h>
#include <cstring>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <errno.h>

#define LOG_TAG "BitPerfectDriver"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static uint32_t g_cbw_tag = 0x12345678;

static bool reset_bot(int fd, int interface_number) {
    struct usbdevfs_ctrltransfer ctrl;
    ctrl.bRequestType = 0x21; // Host to Device, Class, Interface
    ctrl.bRequest = 0xFF;     // Bulk-Only Mass Storage Reset
    ctrl.wValue = 0;
    ctrl.wIndex = interface_number;
    ctrl.wLength = 0;
    ctrl.data = NULL;
    ctrl.timeout = 1000;

    if (ioctl(fd, USBDEVFS_CONTROL, &ctrl) < 0) {
        LOGE("BOT Reset failed: %s", strerror(errno));
        return false;
    }
    return true;
}

static bool clear_halt(int fd, int ep) {
    if (ioctl(fd, USBDEVFS_CLEAR_HALT, &ep) < 0) {
        LOGE("Clear Halt failed for EP %d: %s", ep, strerror(errno));
        return false;
    }
    return true;
}

// SCSI commands
#define SCSI_INQUIRY 0x12
#define SCSI_MODE_SENSE_10 0x5A
#define SCSI_MODE_SENSE_6 0x1A
#define SCSI_READ_TOC 0x43
#define SCSI_READ_CD 0xBE

extern "C" JNIEXPORT jstring JNICALL
Java_com_bitperfect_driver_ScsiDriver_getDriverVersion(
        JNIEnv* env,
        jobject /* this */) {
    std::string version = "0.1.0-alpha";
    return env->NewStringUTF(version.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bitperfect_driver_ScsiDriver_initDevice(
        JNIEnv* env,
        jobject /* this */,
        jint fd,
        jint interfaceNumber,
        jint endpointIn,
        jint endpointOut) {

    LOGI("Initializing device: fd %d, interface %d, epIn 0x%02X, epOut 0x%02X", fd, interfaceNumber, endpointIn, endpointOut);

    // 1. Send Bulk-Only Mass Storage Reset (class request)
    struct usbdevfs_ctrltransfer ctrl = {};
    ctrl.bRequestType = 0x21; // Class, Interface, Host-to-Device
    ctrl.bRequest = 0xFF;     // Bulk-Only Mass Storage Reset
    ctrl.wValue = 0;
    ctrl.wIndex = interfaceNumber;
    ctrl.wLength = 0;
    ctrl.timeout = 5000;
    if (ioctl(fd, USBDEVFS_CONTROL, &ctrl) < 0) {
        LOGE("BOT Reset failed during init: %s", strerror(errno));
        // Continue anyway as some devices might not support this
    }

    // 2. Clear halt on IN endpoint
    struct usbdevfs_ctrltransfer clearIn = {};
    clearIn.bRequestType = 0x02; // Standard, Endpoint, Host-to-Device
    clearIn.bRequest = 0x01;     // CLEAR_FEATURE
    clearIn.wValue = 0;          // ENDPOINT_HALT
    clearIn.wIndex = endpointIn;
    clearIn.wLength = 0;
    clearIn.timeout = 5000;
    if (ioctl(fd, USBDEVFS_CONTROL, &clearIn) < 0) {
        LOGE("Clear IN endpoint failed during init: %s", strerror(errno));
    }

    // 3. Clear halt on OUT endpoint
    struct usbdevfs_ctrltransfer clearOut = {};
    clearOut.bRequestType = 0x02; // Standard, Endpoint, Host-to-Device
    clearOut.bRequest = 0x01;     // CLEAR_FEATURE
    clearOut.wValue = 0;          // ENDPOINT_HALT
    clearOut.wIndex = endpointOut;
    clearOut.wLength = 0;
    clearOut.timeout = 5000;
    if (ioctl(fd, USBDEVFS_CONTROL, &clearOut) < 0) {
        LOGE("Clear OUT endpoint failed during init: %s", strerror(errno));
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_bitperfect_driver_ScsiDriver_executeScsiCommand(JNIEnv* env, jobject /* this */, jint fd, jbyteArray command, jint expectedResponseLength, jint endpointIn, jint endpointOut, jint timeout) {
    jsize cmdLen = env->GetArrayLength(command);
    std::vector<uint8_t> cmdBuffer(cmdLen);
    env->GetByteArrayRegion(command, 0, cmdLen, reinterpret_cast<jbyte*>(cmdBuffer.data()));

    uint8_t opcode = cmdBuffer[0];
    LOGI("Executing SCSI command: 0x%02X, fd: %d, epIn: %d, epOut: %d", opcode, fd, endpointIn, endpointOut);

    // If fd is -1, we use mock responses for testing
    if (fd == -1) {
        std::vector<uint8_t> response;
        switch (opcode) {
        case SCSI_INQUIRY: {
            LOGI("Handling INQUIRY");
            response = {
                0x05, 0x80, 0x05, 0x02, 0x1F, 0x00, 0x00, 0x00
            };
            // Ensure size is 36
            response.resize(36, 0);
            std::memcpy(&response[8], "BitPerf ", 8);
            std::memcpy(&response[16], "ect Virtual Driv", 16);
            std::memcpy(&response[32], "1.00", 4);
            break;
        }
        case SCSI_MODE_SENSE_10:
        case SCSI_MODE_SENSE_6: {
            uint8_t pageCode = cmdBuffer[2] & 0x3F;
            if (pageCode == 0x2A) {
                response.resize(expectedResponseLength, 0);
                int offset = (opcode == SCSI_MODE_SENSE_10) ? 8 : 4;
                if (response.size() > offset + 2) {
                    response[offset] = 0x2A;
                    response[offset+1] = 0x12;
                    response[offset+2] = 0x01;
                }
            } else {
                response.resize(expectedResponseLength, 0);
            }
            break;
        }
        case SCSI_READ_TOC: {
            response = {
                0x00, 0x12, 0x01, 0x02,
                0x00, 0x14, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00,
                0x00, 0x14, 0xAA, 0x00, 0x00, 0x00, 0x32, 0x00
            };
            break;
        }
        case SCSI_READ_CD: {
            response.resize(expectedResponseLength, 0xAA);
            break;
        }
        default:
            LOGE("Unsupported mock SCSI command: 0x%02X", opcode);
            return nullptr;
        }
        jbyteArray result = env->NewByteArray(response.size());
        env->SetByteArrayRegion(result, 0, response.size(), reinterpret_cast<const jbyte*>(response.data()));
        return result;
    }

    int retries = 2;
    while (retries >= 0) {
        uint32_t current_tag = g_cbw_tag++;
        std::vector<uint8_t> cbw(31, 0);
        cbw[0] = 0x55; cbw[1] = 0x53; cbw[2] = 0x42; cbw[3] = 0x43;
        cbw[4] = current_tag & 0xFF;
        cbw[5] = (current_tag >> 8) & 0xFF;
        cbw[6] = (current_tag >> 16) & 0xFF;
        cbw[7] = (current_tag >> 24) & 0xFF;
        cbw[8] = expectedResponseLength & 0xFF;
        cbw[9] = (expectedResponseLength >> 8) & 0xFF;
        cbw[10] = (expectedResponseLength >> 16) & 0xFF;
        cbw[11] = (expectedResponseLength >> 24) & 0xFF;
        cbw[12] = (expectedResponseLength == 0) ? 0x00 : 0x80;
        cbw[14] = cmdLen;
        std::memcpy(&cbw[15], cmdBuffer.data(), cmdLen);

        struct usbdevfs_bulktransfer bulk;
        bulk.ep = endpointOut;
        bulk.len = cbw.size();
        bulk.data = cbw.data();
        bulk.timeout = timeout;

        if (ioctl(fd, USBDEVFS_BULK, &bulk) < 0) {
            LOGE("CBW failed: %s", strerror(errno));
            clear_halt(fd, endpointOut);
            retries--; continue;
        }

        std::vector<uint8_t> response(expectedResponseLength);
        if (expectedResponseLength > 0) {
            bulk.ep = endpointIn;
            bulk.len = expectedResponseLength;
            bulk.data = response.data();
            bulk.timeout = timeout;
            if (ioctl(fd, USBDEVFS_BULK, &bulk) < 0) {
                LOGE("Data phase failed: %s", strerror(errno));
                clear_halt(fd, endpointIn);
                // We MUST try to read CSW anyway after a stall in data phase
            }
        }

        std::vector<uint8_t> csw(13, 0);
        bulk.ep = endpointIn;
        bulk.len = csw.size();
        bulk.data = csw.data();
        bulk.timeout = timeout;
        if (ioctl(fd, USBDEVFS_BULK, &bulk) < 0) {
            LOGE("CSW failed: %s", strerror(errno));
            clear_halt(fd, endpointIn);
            retries--; continue;
        }

        if (csw[0] != 0x55 || csw[1] != 0x53 || csw[2] != 0x42 || csw[3] != 0x53) {
            LOGE("CSW Sig error");
            reset_bot(fd, 0);
            clear_halt(fd, endpointIn);
            clear_halt(fd, endpointOut);
            retries--; continue;
        }

        uint32_t csw_tag = csw[4] | (csw[5] << 8) | (csw[6] << 16) | (csw[7] << 24);
        if (csw_tag != current_tag) {
            LOGE("CSW Tag error");
            retries--; continue;
        }

        if (csw[12] == 0x02) { // Phase Error
            LOGE("Phase Error");
            reset_bot(fd, 0);
            clear_halt(fd, endpointIn);
            clear_halt(fd, endpointOut);
            retries--; continue;
        }

        if (csw[12] != 0x00) {
            LOGE("Command failed: %02X", csw[12]);
            return nullptr;
        }

        jbyteArray result = env->NewByteArray(response.size());
        env->SetByteArrayRegion(result, 0, response.size(), reinterpret_cast<const jbyte*>(response.data()));
        return result;
    }
    return nullptr;
}
