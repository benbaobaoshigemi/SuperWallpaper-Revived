#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#include <atomic>
#include <cstdint>
#include <cstring>

namespace {

constexpr char kLogTag[] = "SuperWallpaperRevived";
constexpr char kSaturnProcess[] =
        "com.miui.miwallpaper.saturn:saturnSuperWallpaper";
constexpr uintptr_t kIl2CppInitRva = 0x8f4070;
constexpr uintptr_t kSwitchToRva = 0x9992c8;

constexpr uintptr_t kStateOffset = 0x28;
constexpr uintptr_t kCameraIndexOffset = 0x140;
constexpr uintptr_t kForceCameraIndexOffset = 0x144;
constexpr uintptr_t kLandIndexOffset = 0x148;
constexpr uintptr_t kAodToLandOffset = 0x274;

constexpr int32_t kLockState = 1;
constexpr int32_t kLandState = 2;
constexpr int32_t kAutomaticCamera = -1;

using HookFunction = int (*)(void *, void *, void **);
using UnhookFunction = int (*)(void *);
using SwitchTo = bool (*)(void *, int32_t, int32_t, const void *);

struct NativeApiEntries {
    uint32_t version;
    HookFunction hook_func;
    UnhookFunction unhook_func;
};

using NativeOnModuleLoaded = void (*)(const char *, void *);

HookFunction g_hook_function = nullptr;
SwitchTo g_original_switch_to = nullptr;
std::atomic_bool g_hook_installed = false;

template <typename T>
T &field(void *instance, uintptr_t offset) {
    return *reinterpret_cast<T *>(reinterpret_cast<uintptr_t>(instance) + offset);
}

bool is_saturn_process() {
    char command_line[128] = {};
    const int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    const ssize_t count = read(fd, command_line, sizeof(command_line) - 1);
    close(fd);
    return count > 0 && std::strcmp(command_line, kSaturnProcess) == 0;
}

uint32_t next_random() {
    static thread_local uint32_t state = [] {
        timespec now{};
        clock_gettime(CLOCK_MONOTONIC, &now);
        return static_cast<uint32_t>(now.tv_nsec)
                ^ static_cast<uint32_t>(syscall(__NR_gettid));
    }();
    state ^= state << 13;
    state ^= state >> 17;
    state ^= state << 5;
    return state;
}

int32_t select_camera_index(int32_t current, int32_t land_index) {
    const int32_t first = land_index == 0 ? 3 : 0;
    if (current >= first && current < first + 3) {
        return first + (current - first + 1 + static_cast<int32_t>(next_random() & 1U)) % 3;
    }
    return first + static_cast<int32_t>(next_random() % 3U);
}

bool hooked_switch_to(void *instance, int32_t state, int32_t aod_offset,
                      const void *method) {
    if (instance == nullptr || state != kLockState || aod_offset != kAutomaticCamera
            || field<int32_t>(instance, kStateOffset) != kLandState) {
        return g_original_switch_to(instance, state, aod_offset, method);
    }

    const int32_t old_camera = field<int32_t>(instance, kCameraIndexOffset);
    const int32_t old_forced_camera = field<int32_t>(instance, kForceCameraIndexOffset);
    const uint8_t old_aod_to_land = field<uint8_t>(instance, kAodToLandOffset);
    const int32_t selected_camera = select_camera_index(
            old_camera, field<int32_t>(instance, kLandIndexOffset));

    field<int32_t>(instance, kCameraIndexOffset) = selected_camera;
    field<int32_t>(instance, kForceCameraIndexOffset) = selected_camera;
    field<uint8_t>(instance, kAodToLandOffset) = 1;
    const bool changed = g_original_switch_to(instance, state, aod_offset, method);
    field<int32_t>(instance, kForceCameraIndexOffset) = old_forced_camera;
    field<uint8_t>(instance, kAodToLandOffset) = old_aod_to_land;

    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "saturn: Land->Lock camera style %d -> %d changed=%d",
                        old_camera, field<int32_t>(instance, kCameraIndexOffset), changed);
    return changed;
}

bool has_expected_switch_to_signature(const void *target) {
    constexpr uint32_t expected[] = {
            0xd10443ffU, 0x6d0a2bebU, 0x6d0b23e9U, 0xf90063fdU};
    return std::memcmp(target, expected, sizeof(expected)) == 0;
}

void on_library_loaded(const char *name, void *handle) {
    if (name == nullptr || handle == nullptr || std::strstr(name, "libil2cpp.so") == nullptr
            || !is_saturn_process() || g_hook_installed.exchange(true)) {
        return;
    }

    void *il2cpp_init = dlsym(handle, "il2cpp_init");
    if (il2cpp_init == nullptr) {
        g_hook_installed = false;
        return;
    }
    const uintptr_t base = reinterpret_cast<uintptr_t>(il2cpp_init) - kIl2CppInitRva;
    void *target = reinterpret_cast<void *>(base + kSwitchToRva);
    if (!has_expected_switch_to_signature(target)) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "saturn: unsupported libil2cpp build; style hook skipped");
        return;
    }

    const int result = g_hook_function(target, reinterpret_cast<void *>(hooked_switch_to),
                                       reinterpret_cast<void **>(&g_original_switch_to));
    if (result != 0 || g_original_switch_to == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "saturn: switchTo hook failed result=%d", result);
        return;
    }
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "saturn: native Land->Lock style hook installed");
}

}  // namespace

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeApiEntries *entries) {
    if (entries == nullptr || entries->hook_func == nullptr) return nullptr;
    g_hook_function = entries->hook_func;
    return on_library_loaded;
}
