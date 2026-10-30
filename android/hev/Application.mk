APP_ABI := arm64-v8a armeabi-v7a
APP_PLATFORM := android-26
APP_CFLAGS := -O3 -DPKGNAME=ru/mayak/pulsar -DCLSNAME=TProxyService
APP_CFLAGS += -Wno-unused-parameter
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
