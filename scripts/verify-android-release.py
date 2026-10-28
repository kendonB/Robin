#!/usr/bin/env python3
"""Fail closed on security regressions in the actual unsigned/signed Release APK.

Uses Android SDK's apkanalyzer, not a source manifest: dependency manifests and
Release configuration must pass the same checks as first-party declarations.
No app or APK code is executed, and no network or credentials are required.
"""

import argparse
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


ANDROID = "{http://schemas.android.com/apk/res/android}"
PACKAGE = "org.evergreenlabs.robin"
DATA_DOMAINS = {
    "root", "file", "database", "sharedpref", "external",
    "device_root", "device_file", "device_database", "device_sharedpref",
}
BASE_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.VIBRATE",
    "android.permission.HIDE_OVERLAY_WINDOWS",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def decode_xml(analyzer, apk, resource=None):
    command = [str(analyzer)]
    if resource is None:
        command += ["manifest", "print", str(apk)]
    else:
        command += ["resources", "xml", "--file", resource, str(apk)]
    result = subprocess.run(command, check=True, text=True, capture_output=True, timeout=60)
    return ET.fromstring(result.stdout)


def qualified(package, name):
    if name.startswith("."):
        return package + name
    if "." not in name:
        return package + "." + name
    return name


def contains_known_marker(stream, markers):
    """Scan bounded chunks while retaining markers split across read boundaries."""
    overlap = max(len(marker) for marker in markers) - 1
    tail = b""
    while chunk := stream.read(64 * 1024):
        data = tail + chunk
        if any(marker in data for marker in markers):
            return True
        tail = data[-overlap:] if overlap else b""
    return False


def policy_files(aapt2, apk, app):
    """Bind checked XML bytes to the manifest references after resource shrinking."""
    result = subprocess.run([str(aapt2), "dump", "resources", str(apk)],
                            check=True, text=True, capture_output=True, timeout=60)
    expected = {
        "network_security_config": "networkSecurityConfig",
        "data_extraction_rules": "dataExtractionRules",
    }
    records = {name: {"ids": set(), "values": []} for name in expected}
    current = None
    for line in result.stdout.splitlines():
        resource = re.match(r"\s*resource\s+(0x[0-9a-fA-F]+)\s+(\S+)", line)
        if resource:
            resource_id, full_name = resource.groups()
            name = full_name.removeprefix("xml/")
            current = name if full_name.startswith("xml/") and name in expected else None
            if current:
                records[current]["ids"].add(resource_id.lower())
        elif current:
            # Resource values can also be aliases, including qualified aliases.
            # Ignoring those would inspect a safe default file while Android
            # selects a different policy on some devices.
            value = re.match(r"\s*\(([^)]*)\)\s+(.+)", line)
            if value:
                records[current]["values"].append(value.groups())
    files = {}
    for name, attribute in expected.items():
        record = records[name]
        require(len(record["ids"]) == 1 and bool(record["values"]),
                "Cannot resolve the packaged " + name + " policy")
        require(all(config == "" for config, _ in record["values"]),
                "Review configuration-specific " + name + " overrides")
        require(len(record["values"]) == 1, "Expected one packaged " + name + " policy")
        filename = re.fullmatch(r"\(file\)\s+(\S+)\s+type=XML", record["values"][0][1])
        require(filename is not None, "Review aliases or unresolved values for " + name)
        resource_id = next(iter(record["ids"]))
        reference = app.get(ANDROID + attribute, "")
        require(reference in {"@ref/" + resource_id, "@" + resource_id, "@xml/" + name},
                "Manifest does not use the inspected " + name + " policy")
        files[name] = filename.group(1)
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--apkanalyzer", type=Path, required=True)
    parser.add_argument("--aapt2", type=Path,
                        help="Defaults to build-tools/36.0.0/aapt2 in apkanalyzer's SDK")
    parser.add_argument("--forbidden-token", default="ROBIN_RELEASE_TOKEN_MUST_NOT_SHIP")
    args = parser.parse_args()
    require(args.apk.is_file(), "Release APK does not exist")
    require(args.apkanalyzer.is_file(), "Android SDK apkanalyzer does not exist")
    aapt2 = args.aapt2 or args.apkanalyzer.resolve().parents[3] / "build-tools/36.0.0/aapt2"
    require(aapt2.is_file(), "Android SDK aapt2 does not exist; pass --aapt2")
    require(len(args.forbidden_token) >= 16, "Use a distinctive non-secret test token")

    manifest = decode_xml(args.apkanalyzer, args.apk)
    require(manifest.tag == "manifest", "APK manifest has an unexpected root")
    package = manifest.get("package")
    require(package == PACKAGE, "APK package is not " + PACKAGE)
    sdk = manifest.find("uses-sdk")
    require(sdk is not None, "APK does not declare its supported Android versions")
    minimum_sdk = sdk.get(ANDROID + "minSdkVersion", "")
    target_sdk = sdk.get(ANDROID + "targetSdkVersion", "")
    require(minimum_sdk.isdecimal() and int(minimum_sdk) >= 33,
            "Release APK must require Android API 33 or newer")
    require(target_sdk.isdecimal() and int(target_sdk) >= 36,
            "Release APK must retain its reviewed target SDK of at least 36")
    require(len(manifest.findall("application")) == 1, "APK must declare one application")
    app = manifest.find("application")
    require(app is not None, "APK has no application")
    require(app.get(ANDROID + "debuggable", "false") == "false", "Release APK is debuggable")
    require(app.get(ANDROID + "testOnly", "false") == "false", "Release APK is test-only")
    require(app.get(ANDROID + "allowBackup") == "false", "Backups are enabled")
    require(app.get(ANDROID + "fullBackupContent") == "false", "Legacy backups are not explicitly disabled")
    require(app.get(ANDROID + "usesCleartextTraffic") == "false", "Cleartext traffic is not explicitly disabled")
    require(app.get(ANDROID + "allowCrossUidActivitySwitchFromBelow") == "false",
            "Cross-UID activity-switch opt-in is missing")
    require(app.get(ANDROID + "networkSecurityConfig") is not None, "Missing network security policy")
    require(app.get(ANDROID + "dataExtractionRules") is not None, "Missing device-transfer exclusion policy")

    private_permission = package + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    permissions = {
        item.get(ANDROID + "name")
        for item in manifest
        if item.tag in {"uses-permission", "uses-permission-sdk-23"}
    }
    require(permissions <= BASE_PERMISSIONS | {private_permission},
            "Unexpected merged permissions: " + str(sorted(permissions - BASE_PERMISSIONS - {private_permission})))
    if private_permission in permissions:
        declaration = next((p for p in manifest.findall("permission")
                            if p.get(ANDROID + "name") == private_permission), None)
        require(declaration is not None and declaration.get(ANDROID + "protectionLevel") in {"signature", "0x2", "2"},
                "Private dynamic receiver permission is not signature protected")

    exported = []
    for component in app:
        if component.tag not in {"activity", "activity-alias", "service", "receiver", "provider"}:
            continue
        raw_name = component.get(ANDROID + "name", "")
        require(bool(raw_name) and not raw_name.startswith("@"), "Component name must be a literal class name")
        name = qualified(package, raw_name)
        require(name != "com.google.firebase.provider.FirebaseInitProvider", "Firebase auto-initialises on app start")
        require(not name.startswith(("io.sentry.", "com.aptabase.")), "Telemetry component is packaged")
        implicit_export = component.find("intent-filter") is not None
        explicit_export = component.get(ANDROID + "exported")
        require(explicit_export in {None, "true", "false"},
                "Component exported flag must be a literal boolean: " + name)
        is_exported = explicit_export == "true" if explicit_export is not None else implicit_export
        if is_exported:
            exported.append(name)
            # AndroidX's profile installer is restricted by a platform signature
            # permission; it is not available to another ordinary app.
            protected_profile_receiver = (
                component.tag == "receiver"
                and name == "androidx.profileinstaller.ProfileInstallReceiver"
                and component.get(ANDROID + "permission") == "android.permission.DUMP"
            )
            require((component.tag == "activity" and name == PACKAGE + ".MainActivity")
                    or protected_profile_receiver,
                    "Unexpected exported component: " + name)

    policies = policy_files(aapt2, args.apk, app)
    with zipfile.ZipFile(args.apk) as apk:
        names = apk.namelist()
        require(len(names) == len(set(names)), "APK contains duplicate archive entries")
        require(policies["network_security_config"] in names, "Cannot inspect packaged TLS rules")
        require(policies["data_extraction_rules"] in names, "Cannot inspect packaged backup rules")
        dex_files = [name for name in names if re.fullmatch(r"classes\d*\.dex", name)]
        require(bool(dex_files), "APK has no DEX code")
        markers = (
            args.forbidden_token, "Lio/sentry/", "Lcom/aptabase/",
            "Lcom/google/firebase/appcheck/debug/",
            "-----BEGIN PRIVATE KEY-----", "-----BEGIN RSA PRIVATE KEY-----",
            "-----BEGIN EC PRIVATE KEY-----", "-----BEGIN OPENSSH PRIVATE KEY-----",
            "-----BEGIN ENCRYPTED PRIVATE KEY-----",
        )
        forbidden = tuple(dict.fromkeys(
            marker.encode(encoding)
            for marker in markers for encoding in ("utf-8", "utf-16le", "utf-16be")
        ))
        for name in names:
            require(not name.lower().endswith((".jks", ".keystore", ".p12", ".pfx", ".pem", ".key")),
                    "Private credential file is packaged: " + name)
            # Raw resources, native libraries and root-level ZIP entries can
            # also carry tokens or private keys. Inspect every packaged file.
            if not name.endswith("/"):
                with apk.open(name) as entry:
                    require(not contains_known_marker(entry, forbidden),
                            "Debug token, telemetry code or private-key material found in " + name)

    network = decode_xml(args.apkanalyzer, args.apk, policies["network_security_config"])
    require(network.tag == "network-security-config", "Packaged TLS policy has an unexpected root")
    base = network.find("base-config")
    require(base is not None and base.get("cleartextTrafficPermitted") == "false", "Packaged TLS policy permits cleartext")
    require(network.find("debug-overrides") is None, "Release TLS policy has debug trust overrides")
    anchors = base.findall("trust-anchors/certificates")
    require(len(anchors) == 1 and anchors[0].get("src") == "system", "TLS trust is not system-CA-only")
    require(network.find("domain-config") is None, "Review new per-domain TLS exceptions explicitly")

    extraction = decode_xml(args.apkanalyzer, args.apk, policies["data_extraction_rules"])
    require(extraction.tag == "data-extraction-rules", "Packaged backup policy has an unexpected root")
    for kind in ("cloud-backup", "device-transfer"):
        section = extraction.find(kind)
        require(section is not None, "Missing " + kind + " rules")
        excluded = {rule.get("domain") for rule in section.findall("exclude") if rule.get("path") == "."}
        require(DATA_DOMAINS <= excluded, kind + " does not exclude every data domain")
        require(section.find("include") is None, kind + " unexpectedly includes app data")

    print(json.dumps({
        "result": "PASS",
        "apk": str(args.apk),
        "package": package,
        "permissions": sorted(permissions),
        "exported_components": sorted(exported),
        "dex_files_inspected": len(dex_files),
        "checks": ["package and SDK levels", "release flags", "activity-switch opt-in",
                   "merged permissions/components", "no cloud auto-init",
                   "no test token/known telemetry or private-key markers", "packaged TLS policy", "packaged backup exclusions"],
    }, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError, ET.ParseError, zipfile.BadZipFile) as error:
        print("Android release verification FAILED: " + str(error), file=sys.stderr)
        sys.exit(1)
