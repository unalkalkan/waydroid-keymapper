/*
 * kmpad: a virtual HID gamepad for testing keymapper without hardware.
 *
 * Creates a gamepad through /dev/uhid (needs root on the device), so the kernel's own HID input
 * driver turns it into a real evdev node, exactly like a USB pad. Commands come from stdin:
 *
 *   press N | release N | tap N [MS]    button 1..16 (1=A/south 2=B/east 4=north 5=west 7=LB 8=RB
 *                                        9=LT 10=RT 11=select 12=start 13=mode 14=L3 15=R3)
 *   axis x|y|z|rz V                     stick axis, V in -1..1 (z/rz = right stick, HID style)
 *   hat up|down|left|right|center|upright|downright|downleft|upleft
 *   sleep MS
 *   quit
 */
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <linux/uhid.h>

static const uint8_t descriptor[] = {
    0x05, 0x01, 0x09, 0x05, 0xA1, 0x01,             /* Generic Desktop / Game Pad / Application */
    0x85, 0x01,                                     /* Report ID 1 */
    0x05, 0x09, 0x19, 0x01, 0x29, 0x10,             /* Buttons 1..16 */
    0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x10, 0x81, 0x02,
    0x05, 0x01, 0x09, 0x39,                         /* Hat switch, 0..7, null when out of range */
    0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14,
    0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
    0x65, 0x00, 0x75, 0x04, 0x95, 0x01, 0x81, 0x03, /* padding */
    0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, /* X Y Z Rz */
    0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02,
    0xC0,
};

static int fd = -1;
static uint16_t buttons;
static uint8_t hat = 8; /* null = centred */
static uint8_t axes[4] = {128, 128, 128, 128};

static int uhid_write(const struct uhid_event *ev) {
    ssize_t n = write(fd, ev, sizeof *ev);
    if (n != (ssize_t) sizeof *ev) {
        fprintf(stderr, "kmpad: write: %s\n", strerror(errno));
        return -1;
    }
    return 0;
}

static void drain(void) {
    struct pollfd p = {.fd = fd, .events = POLLIN};
    struct uhid_event ev;
    while (poll(&p, 1, 0) > 0 && (p.revents & POLLIN)) {
        if (read(fd, &ev, sizeof ev) <= 0) break;
    }
}

static int send_report(void) {
    struct uhid_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = UHID_INPUT2;
    uint8_t *d = ev.u.input2.data;
    d[0] = 1;
    d[1] = buttons & 0xff;
    d[2] = buttons >> 8;
    d[3] = hat & 0x0f;
    memcpy(d + 4, axes, 4);
    ev.u.input2.size = 8;
    drain();
    return uhid_write(&ev);
}

static void sleep_ms(long ms) {
    struct timespec ts = {ms / 1000, (ms % 1000) * 1000000L};
    nanosleep(&ts, NULL);
}

static int hat_value(const char *s) {
    static const char *names[] = {"up", "upright", "right", "downright", "down", "downleft", "left", "upleft"};
    for (int i = 0; i < 8; i++) if (strcmp(s, names[i]) == 0) return i;
    return 8;
}

int main(int argc, char **argv) {
    const char *name = argc > 1 ? argv[1] : "kmpad virtual gamepad";
    fd = open("/dev/uhid", O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        fprintf(stderr, "kmpad: /dev/uhid: %s (needs root)\n", strerror(errno));
        return 1;
    }
    struct uhid_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = UHID_CREATE2;
    snprintf((char *) ev.u.create2.name, sizeof ev.u.create2.name, "%s", name);
    ev.u.create2.rd_size = sizeof descriptor;
    memcpy(ev.u.create2.rd_data, descriptor, sizeof descriptor);
    ev.u.create2.bus = 0x03; /* BUS_USB */
    ev.u.create2.vendor = 0x1209;
    ev.u.create2.product = 0x4b50;
    ev.u.create2.version = 1;
    if (uhid_write(&ev) < 0) return 1;
    printf("kmpad: created '%s' (1209:4b50)\n", name);
    fflush(stdout);
    sleep_ms(200);
    send_report();

    char line[256];
    while (fgets(line, sizeof line, stdin)) {
        char cmd[32] = {0}, a[32] = {0};
        double v = 0;
        int n = sscanf(line, "%31s %31s %lf", cmd, a, &v);
        if (n <= 0) continue;
        if (strcmp(cmd, "quit") == 0) break;
        if (strcmp(cmd, "sleep") == 0) { sleep_ms(atol(a)); continue; }
        if (strcmp(cmd, "press") == 0 || strcmp(cmd, "release") == 0 || strcmp(cmd, "tap") == 0) {
            int b = atoi(a);
            if (b < 1 || b > 16) { fprintf(stderr, "kmpad: button 1..16\n"); continue; }
            if (strcmp(cmd, "release") == 0) buttons &= ~(1u << (b - 1)); else buttons |= 1u << (b - 1);
            send_report();
            if (strcmp(cmd, "tap") == 0) {
                sleep_ms(n >= 3 ? (long) v : 80);
                buttons &= ~(1u << (b - 1));
                send_report();
            }
        } else if (strcmp(cmd, "axis") == 0) {
            int i = strcmp(a, "x") == 0 ? 0 : strcmp(a, "y") == 0 ? 1 : strcmp(a, "z") == 0 ? 2 : strcmp(a, "rz") == 0 ? 3 : -1;
            if (i < 0) { fprintf(stderr, "kmpad: axis x|y|z|rz\n"); continue; }
            if (v < -1) v = -1;
            if (v > 1) v = 1;
            axes[i] = (uint8_t) ((v + 1.0) * 127.5 + 0.5);
            send_report();
        } else if (strcmp(cmd, "hat") == 0) {
            hat = (uint8_t) hat_value(a);
            send_report();
        } else {
            fprintf(stderr, "kmpad: unknown command %s\n", cmd);
        }
    }
    memset(&ev, 0, sizeof ev);
    ev.type = UHID_DESTROY;
    uhid_write(&ev);
    close(fd);
    return 0;
}
