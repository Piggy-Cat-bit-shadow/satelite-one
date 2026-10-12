# VPN, services and network

BoxService owns lifecycle; VPNService owns Android VPN establishment and passes fd to libbox; ProxyService is headless/no-TUN. Destroyed service instances are terminal; starts, stops and notifications are generation/session-bound (`5d9f4f2`, `24c8372`, `74ff3ce`, `bdb4ced`). onRevoke must not block main (`cf3dc09`).

Platform interfaces protect sockets and provide interface data. DefaultNetworkMonitor filters unsuitable/VPN networks; native listeners are owner-scoped and resolution runs off-main under an authoritative epoch (`8432caf`, `ed014c6`). It is connectivity integration, not route or wake policy. Real cellular transitions, OEM killing and actual revoke remain NEEDS_REAL_DEVICE.
