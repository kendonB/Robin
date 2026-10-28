#!/usr/bin/env python3
"""Exercise the release verifier against real, offline AAPT2 resource packages.

The fixtures contain a DEX marker only; no fixture code is installed or executed.
Android's own tools compile and decode the manifest, resource references and
configuration variants so tests cover their packaged representations.
"""

import argparse
import json
import os
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


ANDROID = "{http://schemas.android.com/apk/res/android}"
ET.register_namespace("android", ANDROID[1:-1])
VERIFIER = Path(__file__).with_name("verify-android-release.py")
TOKEN = "ROBIN_RELEASE_TOKEN_MUST_NOT_SHIP"
SDK_ROOT = None

MANIFEST = """<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="org.evergreenlabs.robin">
  <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="36" />
  <uses-permission android:name="android.permission.INTERNET" />
  <application android:allowBackup="false" android:fullBackupContent="false"
      android:allowCrossUidActivitySwitchFromBelow="false"
      android:usesCleartextTraffic="false"
      android:networkSecurityConfig="@xml/network_security_config"
      android:dataExtractionRules="@xml/data_extraction_rules">
    <activity android:name=".MainActivity" android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity>
  </application>
</manifest>"""

NETWORK_POLICY = """<network-security-config>
  <base-config cleartextTrafficPermitted="false">
    <trust-anchors><certificates src="system" /></trust-anchors>
  </base-config>
</network-security-config>"""

UNSAFE_NETWORK_POLICY = """<network-security-config>
  <base-config cleartextTrafficPermitted="true">
    <trust-anchors><certificates src="user" /></trust-anchors>
  </base-config>
</network-security-config>"""

EXCLUSIONS = """
    <exclude domain="root" path="." />
    <exclude domain="file" path="." />
    <exclude domain="database" path="." />
    <exclude domain="sharedpref" path="." />
    <exclude domain="external" path="." />
    <exclude domain="device_root" path="." />
    <exclude domain="device_file" path="." />
    <exclude domain="device_database" path="." />
    <exclude domain="device_sharedpref" path="." />
"""
BACKUP_POLICY = (
    "<data-extraction-rules><cloud-backup>" + EXCLUSIONS
    + "</cloud-backup><device-transfer>" + EXCLUSIONS
    + "</device-transfer></data-extraction-rules>"
)


def run(command):
    return subprocess.run(command, capture_output=True, text=True, timeout=90)


class ReleaseVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.aapt2 = SDK_ROOT / "build-tools/36.0.0/aapt2"
        cls.analyzer = SDK_ROOT / "cmdline-tools/latest/bin/apkanalyzer"
        cls.android_jar = SDK_ROOT / "platforms/android-36/android.jar"
        for path in (cls.aapt2, cls.analyzer, cls.android_jar):
            if not path.is_file():
                raise RuntimeError("Required Android SDK fixture tool is missing: " + str(path))

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="robin-release-verifier-test-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def build_fixture(self, manifest=None, resources=None, extra_entries=()):
        manifest = ET.fromstring(MANIFEST) if manifest is None else manifest
        resource_files = {
            "xml/network_security_config.xml": NETWORK_POLICY,
            "xml/data_extraction_rules.xml": BACKUP_POLICY,
        }
        resource_files.update(resources or {})
        for name, content in resource_files.items():
            if content is None:
                continue
            path = self.root / "res" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            if isinstance(content, bytes):
                path.write_bytes(content)
            else:
                path.write_text(content, encoding="utf-8")
        manifest_path = self.root / "AndroidManifest.xml"
        ET.ElementTree(manifest).write(manifest_path, encoding="utf-8", xml_declaration=True)
        compiled = self.root / "resources.zip"
        apk = self.root / "fixture.apk"
        commands = (
            [str(self.aapt2), "compile", "--dir", str(self.root / "res"), "-o", str(compiled)],
            [str(self.aapt2), "link", "-I", str(self.android_jar), "--manifest", str(manifest_path),
             "-o", str(apk), str(compiled)],
        )
        for command in commands:
            result = run(command)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        with zipfile.ZipFile(apk, "a") as archive:
            archive.writestr("classes.dex", b"dex\n035\0")
            for name, content in extra_entries:
                archive.writestr(name, content)
        return apk

    def verify(self, apk):
        return run([sys.executable, str(VERIFIER), "--apk", str(apk),
                    "--apkanalyzer", str(self.analyzer), "--aapt2", str(self.aapt2)])

    def assert_rejected(self, apk, reason):
        result = self.verify(apk)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn(reason, result.stderr)
        self.assertNotIn('"result": "PASS"', result.stdout)

    def test_safe_packaged_policies_pass(self):
        result = self.verify(self.build_fixture())
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(json.loads(result.stdout)["result"], "PASS")

    def test_unexpected_exported_service_is_rejected(self):
        manifest = ET.fromstring(MANIFEST)
        ET.SubElement(manifest.find("application"), "service", {
            ANDROID + "name": "org.example.ExportedService", ANDROID + "exported": "true",
        })
        self.assert_rejected(self.build_fixture(manifest), "Unexpected exported component")

    def test_resource_boolean_cannot_hide_exported_service(self):
        manifest = ET.fromstring(MANIFEST)
        ET.SubElement(manifest.find("application"), "service", {
            ANDROID + "name": "org.example.ExportedService", ANDROID + "exported": "@bool/is_exported",
        })
        resources = {"values/bools.xml": '<resources><bool name="is_exported">true</bool></resources>'}
        self.assert_rejected(self.build_fixture(manifest, resources), "exported flag must be a literal boolean")

    def test_version_qualified_tls_alias_is_rejected(self):
        resources = {
            "values-v35/policies.xml": '<resources><item name="network_security_config" type="xml">'
                                       '@xml/alternate_network_config</item></resources>',
            "xml/alternate_network_config.xml": UNSAFE_NETWORK_POLICY,
        }
        self.assert_rejected(self.build_fixture(resources=resources), "configuration-specific network_security_config")

    def test_default_tls_alias_requires_explicit_review(self):
        resources = {
            "xml/network_security_config.xml": None,
            "values/policies.xml": '<resources><item name="network_security_config" type="xml">'
                                   '@xml/alternate_network_config</item></resources>',
            "xml/alternate_network_config.xml": NETWORK_POLICY,
        }
        self.assert_rejected(self.build_fixture(resources=resources), "aliases or unresolved values")

    def test_version_qualified_backup_alias_is_rejected(self):
        resources = {
            "values-v35/policies.xml": '<resources><item name="data_extraction_rules" type="xml">'
                                       '@xml/alternate_backup_rules</item></resources>',
            "xml/alternate_backup_rules.xml": '<data-extraction-rules><cloud-backup />'
                                              '<device-transfer /></data-extraction-rules>',
        }
        self.assert_rejected(self.build_fixture(resources=resources), "configuration-specific data_extraction_rules")

    def test_version_qualified_tls_file_is_rejected(self):
        resources = {"xml-v35/network_security_config.xml": UNSAFE_NETWORK_POLICY}
        self.assert_rejected(self.build_fixture(resources=resources), "configuration-specific network_security_config")

    def test_manifest_must_reference_inspected_policy(self):
        manifest = ET.fromstring(MANIFEST)
        manifest.find("application").set(ANDROID + "networkSecurityConfig", "@xml/alternate_network_config")
        resources = {"xml/alternate_network_config.xml": UNSAFE_NETWORK_POLICY}
        self.assert_rejected(self.build_fixture(manifest, resources), "Manifest does not use the inspected")

    def test_cleartext_tls_policy_is_rejected(self):
        resources = {"xml/network_security_config.xml": UNSAFE_NETWORK_POLICY}
        self.assert_rejected(self.build_fixture(resources=resources), "Packaged TLS policy permits cleartext")

    def test_user_ca_trust_is_rejected(self):
        resources = {"xml/network_security_config.xml": NETWORK_POLICY.replace('src="system"', 'src="user"')}
        self.assert_rejected(self.build_fixture(resources=resources), "TLS trust is not system-CA-only")

    def test_missing_backup_domain_is_rejected(self):
        resources = {"xml/data_extraction_rules.xml": BACKUP_POLICY.replace('<exclude domain="database" path="." />', '')}
        self.assert_rejected(self.build_fixture(resources=resources), "does not exclude every data domain")

    def test_debuggable_release_is_rejected(self):
        manifest = ET.fromstring(MANIFEST)
        manifest.find("application").set(ANDROID + "debuggable", "true")
        self.assert_rejected(self.build_fixture(manifest), "Release APK is debuggable")

    def test_missing_activity_switch_opt_in_is_rejected(self):
        manifest = ET.fromstring(MANIFEST)
        del manifest.find("application").attrib[ANDROID + "allowCrossUidActivitySwitchFromBelow"]
        self.assert_rejected(self.build_fixture(manifest), "Cross-UID activity-switch opt-in")

    def test_lower_target_sdk_is_rejected(self):
        manifest = ET.fromstring(MANIFEST)
        manifest.find("uses-sdk").set(ANDROID + "targetSdkVersion", "32")
        self.assert_rejected(self.build_fixture(manifest), "reviewed target SDK")

    def test_unexpected_permission_is_rejected(self):
        manifest = ET.fromstring(MANIFEST)
        ET.SubElement(manifest, "uses-permission", {ANDROID + "name": "android.permission.READ_CONTACTS"})
        self.assert_rejected(self.build_fixture(manifest), "Unexpected merged permissions")

    def test_firebase_automatic_initialization_is_rejected(self):
        manifest = ET.fromstring(MANIFEST)
        ET.SubElement(manifest.find("application"), "provider", {
            ANDROID + "name": "com.google.firebase.provider.FirebaseInitProvider",
            ANDROID + "authorities": "org.evergreenlabs.robin.firebaseinitprovider",
            ANDROID + "exported": "false",
        })
        self.assert_rejected(self.build_fixture(manifest), "Firebase auto-initialises")

    def test_raw_resource_token_is_rejected(self):
        self.assert_rejected(self.build_fixture(resources={"raw/test_token.txt": TOKEN}),
                             "Debug token, telemetry code or private-key material")

    def test_root_entry_private_key_marker_is_rejected(self):
        entries = [("private-config.txt", b"-----BEGIN PRIVATE KEY-----\nsynthetic-fixture\n")]
        self.assert_rejected(self.build_fixture(extra_entries=entries), "private-key material")

    def test_raw_resource_utf16le_private_key_marker_is_rejected(self):
        resources = {"raw/private_config.bin": "-----BEGIN PRIVATE KEY-----".encode("utf-16le")}
        self.assert_rejected(self.build_fixture(resources=resources), "private-key material")

    def test_raw_resource_utf16be_private_key_marker_is_rejected(self):
        resources = {"raw/private_config.bin": "-----BEGIN PRIVATE KEY-----".encode("utf-16be")}
        self.assert_rejected(self.build_fixture(resources=resources), "private-key material")

    def test_private_key_marker_across_scan_chunks_is_rejected(self):
        content = b"x" * (64 * 1024 - 10) + b"-----BEGIN PRIVATE KEY-----"
        self.assert_rejected(self.build_fixture(extra_entries=[("large-config.bin", content)]),
                             "private-key material")

    def test_credential_filename_checks_are_case_insensitive(self):
        self.assert_rejected(self.build_fixture(extra_entries=[("private-config.PEM", b"synthetic-fixture")]),
                             "Private credential file is packaged")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path,
                        default=os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME"))
    args = parser.parse_args()
    if args.sdk is None:
        parser.error("Pass --sdk or set ANDROID_SDK_ROOT to the Android SDK directory")
    SDK_ROOT = args.sdk.resolve()
    unittest.main(argv=[sys.argv[0]], verbosity=2)
