/*
 * keymapper native bridge: raw evdev and uinput ioctls for the helper daemon.
 * Written from the Linux input/uinput uapi headers; no third-party code.
 */
#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <android/log.h>

#define TAG "keymapperd"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define JNI(name) Java_games_forgemaster_keymapper_helper_NativeInput_##name

#define TOUCH_MAX 32767

static jint neg_errno(void) { return errno ? -errno : -EIO; }

JNIEXPORT jint JNICALL JNI(open)(JNIEnv *env, jclass cls, jstring jpath, jboolean rw) {
    (void) cls;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return -ENOMEM;
    int fd = open(path, (rw ? O_RDWR : O_RDONLY) | O_NONBLOCK | O_CLOEXEC);
    jint result = fd >= 0 ? fd : neg_errno();
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return result;
}

JNIEXPORT void JNICALL JNI(close)(JNIEnv *env, jclass cls, jint fd) {
    (void) env; (void) cls;
    if (fd >= 0) close(fd);
}

JNIEXPORT jint JNICALL JNI(grab)(JNIEnv *env, jclass cls, jint fd, jboolean on) {
    (void) env; (void) cls;
    return ioctl(fd, EVIOCGRAB, on ? 1 : 0) == 0 ? 0 : neg_errno();
}

JNIEXPORT jstring JNICALL JNI(name)(JNIEnv *env, jclass cls, jint fd) {
    (void) cls;
    char buf[256];
    memset(buf, 0, sizeof buf);
    if (ioctl(fd, EVIOCGNAME(sizeof buf - 1), buf) < 0) return NULL;
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jintArray JNICALL JNI(id)(JNIEnv *env, jclass cls, jint fd) {
    (void) cls;
    struct input_id id;
    if (ioctl(fd, EVIOCGID, &id) < 0) return NULL;
    jint out[4] = {id.bustype, id.vendor, id.product, id.version};
    jintArray arr = (*env)->NewIntArray(env, 4);
    if (arr) (*env)->SetIntArrayRegion(env, arr, 0, 4, out);
    return arr;
}

static jbyteArray bits_result(JNIEnv *env, const unsigned char *buf, int len) {
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (arr) (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *) buf);
    return arr;
}

/* EVIOCGBIT for an event type (0 = the event-type bitmap itself). */
JNIEXPORT jbyteArray JNICALL JNI(bits)(JNIEnv *env, jclass cls, jint fd, jint type) {
    (void) cls;
    int max;
    switch (type) {
        case 0: max = EV_MAX; break;
        case EV_KEY: max = KEY_MAX; break;
        case EV_ABS: max = ABS_MAX; break;
        case EV_REL: max = REL_MAX; break;
        case EV_FF: max = FF_MAX; break;
        default: return NULL;
    }
    unsigned char buf[KEY_MAX / 8 + 1];
    memset(buf, 0, sizeof buf);
    int len = max / 8 + 1;
    int n = ioctl(fd, EVIOCGBIT(type, len), buf);
    if (n < 0) return NULL;
    return bits_result(env, buf, len);
}

JNIEXPORT jbyteArray JNICALL JNI(props)(JNIEnv *env, jclass cls, jint fd) {
    (void) cls;
    unsigned char buf[INPUT_PROP_MAX / 8 + 1];
    memset(buf, 0, sizeof buf);
    if (ioctl(fd, EVIOCGPROP(sizeof buf), buf) < 0) return NULL;
    return bits_result(env, buf, sizeof buf);
}

JNIEXPORT jintArray JNICALL JNI(absInfo)(JNIEnv *env, jclass cls, jint fd, jint code) {
    (void) cls;
    struct input_absinfo info;
    if (code < 0 || code > ABS_MAX) return NULL;
    if (ioctl(fd, EVIOCGABS(code), &info) < 0) return NULL;
    jint out[6] = {info.value, info.minimum, info.maximum, info.fuzz, info.flat, info.resolution};
    jintArray arr = (*env)->NewIntArray(env, 6);
    if (arr) (*env)->SetIntArrayRegion(env, arr, 0, 6, out);
    return arr;
}

/*
 * Waits up to timeoutMs for events and fills `out` with (type, code, value) triples.
 * Returns the number of events, 0 on timeout, or -errno (-ENODEV once the device is gone).
 */
JNIEXPORT jint JNICALL JNI(read)(JNIEnv *env, jclass cls, jint fd, jintArray out, jint timeoutMs) {
    (void) cls;
    struct pollfd pfd = {.fd = fd, .events = POLLIN};
    int pr = poll(&pfd, 1, timeoutMs);
    if (pr == 0) return 0;
    if (pr < 0) return errno == EINTR ? 0 : neg_errno();
    if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) return -ENODEV;

    jsize cap = (*env)->GetArrayLength(env, out) / 3;
    if (cap <= 0) return -EINVAL;
    if (cap > 64) cap = 64;
    struct input_event ev[64];
    ssize_t n = read(fd, ev, sizeof(struct input_event) * (size_t) cap);
    if (n < 0) return (errno == EAGAIN || errno == EINTR) ? 0 : neg_errno();
    int count = (int) (n / (ssize_t) sizeof(struct input_event));
    jint tmp[64 * 3];
    for (int i = 0; i < count; i++) {
        tmp[i * 3] = ev[i].type;
        tmp[i * 3 + 1] = ev[i].code;
        tmp[i * 3 + 2] = ev[i].value;
    }
    (*env)->SetIntArrayRegion(env, out, 0, count * 3, tmp);
    return count;
}

/* Uploads (or updates, when effectId >= 0) a rumble effect and plays it once. Returns the effect id. */
JNIEXPORT jint JNICALL JNI(rumble)(JNIEnv *env, jclass cls, jint fd, jint effectId, jint strong, jint weak, jint ms) {
    (void) env; (void) cls;
    struct ff_effect effect;
    memset(&effect, 0, sizeof effect);
    effect.type = FF_RUMBLE;
    effect.id = (int16_t) effectId;
    effect.u.rumble.strong_magnitude = (uint16_t) strong;
    effect.u.rumble.weak_magnitude = (uint16_t) weak;
    effect.replay.length = (uint16_t) ms;
    if (ioctl(fd, EVIOCSFF, &effect) < 0) return neg_errno();
    struct input_event play;
    memset(&play, 0, sizeof play);
    play.type = EV_FF;
    play.code = (uint16_t) effect.id;
    play.value = 1;
    if (write(fd, &play, sizeof play) < 0) return neg_errno();
    return effect.id;
}

static int abs_setup(int fd, int code, int min, int max) {
#ifdef UI_ABS_SETUP
    struct uinput_abs_setup s;
    memset(&s, 0, sizeof s);
    s.code = (uint16_t) code;
    s.absinfo.minimum = min;
    s.absinfo.maximum = max;
    return ioctl(fd, UI_ABS_SETUP, &s);
#else
    (void) fd; (void) code; (void) min; (void) max;
    errno = ENOSYS;
    return -1;
#endif
}

/* Creates a protocol-B multitouch screen with axes 0..TOUCH_MAX; InputReader scales to the display. */
JNIEXPORT jint JNICALL JNI(uinputCreate)(JNIEnv *env, jclass cls, jstring jname, jint slots) {
    (void) cls;
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return neg_errno();

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    if (!name) { close(fd); return -ENOMEM; }

    int ok = ioctl(fd, UI_SET_EVBIT, EV_SYN) == 0
        && ioctl(fd, UI_SET_EVBIT, EV_KEY) == 0
        && ioctl(fd, UI_SET_EVBIT, EV_ABS) == 0
        && ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH) == 0
        && ioctl(fd, UI_SET_ABSBIT, ABS_MT_SLOT) == 0
        && ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID) == 0
        && ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X) == 0
        && ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y) == 0
        && ioctl(fd, UI_SET_ABSBIT, ABS_MT_PRESSURE) == 0
        && ioctl(fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR) == 0
        && ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT) == 0;
    if (!ok) goto fail;

    int created = 0;
#ifdef UI_DEV_SETUP
    {
        struct uinput_setup setup;
        memset(&setup, 0, sizeof setup);
        setup.id.bustype = BUS_VIRTUAL;
        setup.id.vendor = 0x1209; /* pid.codes open-source vendor id */
        setup.id.product = 0x4b4d;
        setup.id.version = 1;
        strncpy(setup.name, name, UINPUT_MAX_NAME_SIZE - 1);
        if (ioctl(fd, UI_DEV_SETUP, &setup) == 0
            && abs_setup(fd, ABS_MT_SLOT, 0, slots - 1) == 0
            && abs_setup(fd, ABS_MT_TRACKING_ID, 0, 65535) == 0
            && abs_setup(fd, ABS_MT_POSITION_X, 0, TOUCH_MAX) == 0
            && abs_setup(fd, ABS_MT_POSITION_Y, 0, TOUCH_MAX) == 0
            && abs_setup(fd, ABS_MT_PRESSURE, 0, 255) == 0
            && abs_setup(fd, ABS_MT_TOUCH_MAJOR, 0, 255) == 0) {
            created = 1;
        }
    }
#endif
    if (!created) {
        /* Pre-4.5 kernels: describe the device by writing uinput_user_dev. */
        struct uinput_user_dev dev;
        memset(&dev, 0, sizeof dev);
        strncpy(dev.name, name, UINPUT_MAX_NAME_SIZE - 1);
        dev.id.bustype = BUS_VIRTUAL;
        dev.id.vendor = 0x1209;
        dev.id.product = 0x4b4d;
        dev.id.version = 1;
        dev.absmax[ABS_MT_SLOT] = slots - 1;
        dev.absmax[ABS_MT_TRACKING_ID] = 65535;
        dev.absmax[ABS_MT_POSITION_X] = TOUCH_MAX;
        dev.absmax[ABS_MT_POSITION_Y] = TOUCH_MAX;
        dev.absmax[ABS_MT_PRESSURE] = 255;
        dev.absmax[ABS_MT_TOUCH_MAJOR] = 255;
        if (write(fd, &dev, sizeof dev) != (ssize_t) sizeof dev) goto fail;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) goto fail;
    (*env)->ReleaseStringUTFChars(env, jname, name);
    return fd;

fail:
    {
        jint err = neg_errno();
        LOGW("uinput setup failed: %s", strerror(-err));
        (*env)->ReleaseStringUTFChars(env, jname, name);
        close(fd);
        return err;
    }
}

/* Writes `count` (type, code, value) triples as one batch. */
JNIEXPORT jint JNICALL JNI(uinputEmit)(JNIEnv *env, jclass cls, jint fd, jintArray events, jint count) {
    (void) cls;
    if (count <= 0) return 0;
    if (count > 256) return -EINVAL;
    jint tmp[256 * 3];
    (*env)->GetIntArrayRegion(env, events, 0, count * 3, tmp);
    struct input_event ev[256];
    memset(ev, 0, sizeof(struct input_event) * (size_t) count);
    for (int i = 0; i < count; i++) {
        ev[i].type = (uint16_t) tmp[i * 3];
        ev[i].code = (uint16_t) tmp[i * 3 + 1];
        ev[i].value = tmp[i * 3 + 2];
    }
    ssize_t want = (ssize_t) (sizeof(struct input_event) * (size_t) count);
    ssize_t n = write(fd, ev, (size_t) want);
    return n == want ? count : neg_errno();
}

JNIEXPORT void JNICALL JNI(uinputDestroy)(JNIEnv *env, jclass cls, jint fd) {
    (void) env; (void) cls;
    if (fd < 0) return;
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
}

static int signal_pipe[2] = {-1, -1};

static void on_signal(int sig) {
    unsigned char b = (unsigned char) sig;
    ssize_t r = write(signal_pipe[1], &b, 1);
    (void) r;
}

/* Routes SIGTERM/SIGINT/SIGHUP to a pipe so a Java thread can shut down cleanly. Returns the read end. */
JNIEXPORT jint JNICALL JNI(installSignalPipe)(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    if (signal_pipe[0] >= 0) return signal_pipe[0];
    if (pipe2(signal_pipe, O_CLOEXEC) < 0) return neg_errno();
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_signal;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_RESTART;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    return signal_pipe[0];
}

/* Blocks until a routed signal arrives; returns its number. */
JNIEXPORT jint JNICALL JNI(waitSignal)(JNIEnv *env, jclass cls, jint fd) {
    (void) env; (void) cls;
    unsigned char b;
    for (;;) {
        ssize_t n = read(fd, &b, 1);
        if (n == 1) return b;
        if (n < 0 && errno == EINTR) continue;
        return neg_errno();
    }
}
