// IUnifiedService.aidl
package dev.faron.flux;

interface IUnifiedService {
    boolean isVpnRunning();
    void    stopVpn();

    boolean isFServiceRunning();
    void    stopOpenFluxNative();
    void    startOpenFluxNative(String transport, in String[] args);
    void    startTun2Socks();
    int     getFd();
}
