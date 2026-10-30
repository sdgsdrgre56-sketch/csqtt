package ru.mayak.pulsar;

/** Мост к hev-socks5-tunnel (tun → SOCKS5). Библиотека собирается с PKGNAME=ru/mayak/pulsar. */
final class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private TProxyService() {}

    static native boolean TProxyStartService(String configPath, int fd);

    static native boolean TProxyStopService();

    static native long[] TProxyGetStats();

    // объявлены все функции, которые регистрирует библиотека, иначе RegisterNatives падает
    static native boolean TProxyIsRunning();
}
