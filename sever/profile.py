import re
import base64
import ipaddress


def validate_profile(profile):
    if not isinstance(profile, dict) or profile.get("version") != 2 or profile.get("protocol") != "sever1" or profile.get("transport") not in ("wss", "obfs4"):
        raise ValueError("profile v2 with wss or obfs4 required; no raw TLS downgrade")
    opaque = profile["transport"] == "obfs4"
    if opaque:
        proxy = profile.get("local_proxy")
        if not isinstance(proxy, dict) or not isinstance(proxy.get("host"), str) or not ipaddress.ip_address(proxy["host"]).is_loopback:
            raise ValueError("obfs4 requires a literal loopback proxy")
        if type(proxy.get("port")) is not int or not 1 <= proxy["port"] <= 65535:
            raise ValueError("invalid proxy port")
    user, token = profile.get("user"), profile.get("token")
    if not isinstance(user, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", user) or not isinstance(token, str) or not 32 <= len(token) <= 256:
        raise ValueError("invalid credentials")
    endpoints = profile.get("endpoints")
    if not isinstance(endpoints, list) or not 1 <= len(endpoints) <= 16:
        raise ValueError("invalid endpoints")
    for endpoint in endpoints:
        if not isinstance(endpoint, dict):
            raise ValueError("invalid endpoint")
        host, port, path = endpoint.get("host"), endpoint.get("port"), endpoint.get("path")
        if not isinstance(host, str):
            raise ValueError("invalid host")
        if opaque:
            ipaddress.ip_address(host)
        elif not re.fullmatch(r"[A-Za-z0-9.-]{1,253}", host):
            raise ValueError("invalid host")
        if type(port) is not int or not 1 <= port <= 65535:
            raise ValueError("invalid port")
        if opaque:
            name, cert, mode = endpoint.get("server_name"), endpoint.get("cert"), endpoint.get("iat_mode")
            if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9.-]{1,253}", name):
                raise ValueError("invalid TLS server name")
            if not isinstance(cert, str) or not re.fullmatch(r"[A-Za-z0-9+/]{70}(==)?", cert):
                raise ValueError("invalid obfs4 cert")
            if len(base64.b64decode(cert + "=" * (-len(cert) % 4), validate=True)) != 52:
                raise ValueError("invalid obfs4 cert size")
            if type(mode) is not int or mode not in (0, 1, 2):
                raise ValueError("invalid obfs4 iat mode")
        elif not isinstance(path, str) or not re.fullmatch(r"/[A-Za-z0-9][A-Za-z0-9/_-]{0,126}", path) or path.startswith("/_"):
            raise ValueError("invalid path")
    return profile
