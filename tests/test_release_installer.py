"""Exercise installation with signed fixtures and a controlled process query.

Only /bin/ps is replaced in a temporary installer copy. Native process detection
has a separate test, skipped when the host denies process inspection.
"""
import hashlib
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/install-macos.sh"


@unittest.skipUnless(sys.platform == "darwin", "Requires macOS signing tools")
class InstallerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory(prefix="document-studio-installer-")
        cls.root = Path(cls.workspace.name)
        cls.process_listing = cls.root / "process-listing"
        cls.process_listing.write_text("")
        cls.process_query = cls.root / "fixture-ps"
        cls.process_query.write_text('#!/bin/bash\nexec /bin/cat "' + str(cls.process_listing) + '"\n')
        cls.process_query.chmod(0o755)
        original = SCRIPT.read_text()
        if original.count("/bin/ps ") != 1:
            raise AssertionError("Expected exactly one native process query")
        cls.script = cls.root / "install-macos-fixture.sh"
        cls.script.write_text(original.replace("/bin/ps ", '"' + str(cls.process_query) + '" '))
        cls.v1 = cls.fixture("1.6.0")
        cls.v2 = cls.fixture("1.6.1")
        cls.wrong_id = cls.fixture("1.6.0", "example.another-app")

    @classmethod
    def tearDownClass(cls):
        cls.workspace.cleanup()

    @classmethod
    def fixture(cls, version, bundle_id="local.nahw.hwpxstudio"):
        folder = Path(tempfile.mkdtemp(dir=cls.root))
        app = folder / "Document Studio.app"
        contents = app / "Contents"
        resources = contents / "Resources"
        (contents / "MacOS").mkdir(parents=True)
        (resources / "Runtime/bin").mkdir(parents=True)
        (resources / "Engine").mkdir()
        shutil.copyfile("/bin/sleep", contents / "MacOS/DocumentStudio")
        shutil.copyfile("/usr/bin/true", resources / "Runtime/bin/java")
        (contents / "MacOS/DocumentStudio").chmod(0o755)
        (resources / "Runtime/bin/java").chmod(0o755)
        shutil.copyfile(SCRIPT, resources / "install-macos.sh")
        (resources / "Engine/engine.jar").write_bytes(b"fixture-only")
        (contents / "Info.plist").write_bytes(plistlib.dumps({
            "CFBundleIdentifier": bundle_id,
            "CFBundleExecutable": "DocumentStudio",
            "CFBundlePackageType": "APPL",
            "CFBundleShortVersionString": version,
            "CFBundleVersion": "14",
        }))
        subprocess.run(["/usr/bin/codesign", "--force", "--deep", "--sign", "-", str(app)],
                       check=True, capture_output=True)
        archive = folder / "fixture.zip"
        subprocess.run(["/usr/bin/ditto", "-c", "-k", "--norsrc", "--keepParent", str(app), str(archive)],
                       check=True, capture_output=True)
        return archive

    def setUp(self):
        self.process_listing.write_text("")
        self.case = Path(tempfile.mkdtemp(dir=self.root))
        self.apps = self.case / "Applications with spaces"
        self.bin = self.case / "bin with spaces"
        self.app = self.apps / "Document Studio.app"

    def run_install(self, archive=None, version="1.6.0", checksum=None, native=False):
        archive = archive or self.v1
        checksum = checksum or hashlib.sha256(archive.read_bytes()).hexdigest()
        return subprocess.run([
            "/bin/bash", str(SCRIPT if native else self.script), "--repo", "example/DocumentStudio",
            "--archive", str(archive), "--version", version, "--sha256", checksum,
            "--app-dir", str(self.apps), "--bin-dir", str(self.bin),
        ], text=True, capture_output=True)

    def version(self):
        return plistlib.loads((self.app / "Contents/Info.plist").read_bytes())["CFBundleShortVersionString"]

    def install_initial(self):
        result = self.run_install()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.version(), "1.6.0")

    def test_install_update_launcher_and_backup(self):
        self.install_initial()
        result = self.run_install(self.v2, version="1.6.1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.version(), "1.6.1")
        backups = list(self.apps.glob(".document-studio-backup.*/Document Studio.app/Contents/Info.plist"))
        self.assertEqual(len(backups), 1)
        self.assertEqual(plistlib.loads(backups[0].read_bytes())["CFBundleShortVersionString"], "1.6.0")
        launcher = subprocess.run([str(self.bin / "document-studio"), "version"], text=True, capture_output=True)
        self.assertEqual(launcher.returncode, 0, launcher.stderr)
        self.assertEqual(launcher.stdout.strip(), "1.6.1")
        self.assertFalse((self.apps / ".document-studio-install.lock").exists())

    def test_corrupt_checksum_preserves_existing_app(self):
        self.install_initial()
        result = self.run_install(self.v2, version="1.6.1", checksum="0" * 64)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SHA-256", result.stderr)
        self.assertEqual(self.version(), "1.6.0")
        self.assertFalse(list(self.apps.glob(".document-studio-backup.*")))

    def test_wrong_bundle_id_preserves_existing_app(self):
        self.install_initial()
        result = self.run_install(self.wrong_id)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.version(), "1.6.0")

    def test_version_mismatch_preserves_existing_app(self):
        self.install_initial()
        result = self.run_install(self.v2)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.version(), "1.6.0")

    def test_unmanaged_launcher_is_not_replaced(self):
        self.bin.mkdir(parents=True)
        target = self.bin / "document-studio"
        target.write_text("user-owned launcher\n")
        result = self.run_install()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(target.read_text(), "user-owned launcher\n")
        self.assertFalse(self.app.exists())

    def test_existing_lock_is_not_removed(self):
        lock = self.apps / ".document-studio-install.lock"
        lock.mkdir(parents=True)
        result = self.run_install()
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(lock.is_dir())
        self.assertFalse(self.app.exists())

    def test_traversal_zip_is_rejected(self):
        archive = self.case / "traversal.zip"
        with zipfile.ZipFile(archive, "w") as z:
            z.writestr("../outside", "must not be written")
        result = self.run_install(archive)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.apps / "outside").exists())
        self.assertFalse(self.app.exists())

    def test_reported_running_target_is_not_replaced(self):
        self.install_initial()
        self.process_listing.write_text(str(self.app / "Contents/MacOS/DocumentStudio") + "\n")
        result = self.run_install(self.v2, version="1.6.1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("종료", result.stderr)
        self.assertEqual(self.version(), "1.6.0")

    def test_native_running_target_is_not_replaced_or_stopped(self):
        try:
            probe = subprocess.run(["/bin/ps", "-axo", "comm="], text=True, capture_output=True)
        except PermissionError as error:
            self.skipTest("Native process inspection unavailable: " + str(error))
        if probe.returncode:
            self.skipTest("Native process inspection unavailable: " + probe.stderr.strip())
        self.install_initial()
        process = subprocess.Popen([str(self.app / "Contents/MacOS/DocumentStudio"), "30"])
        try:
            for _ in range(20):
                listing = subprocess.check_output(["/bin/ps", "-axo", "comm="], text=True)
                if str(self.app / "Contents/MacOS/DocumentStudio") in listing:
                    break
                time.sleep(0.05)
            self.assertIn(str(self.app / "Contents/MacOS/DocumentStudio"), listing)
            result = self.run_install(self.v2, version="1.6.1", native=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIsNone(process.poll())
            self.assertEqual(self.version(), "1.6.0")
        finally:
            process.terminate()
            process.wait(timeout=5)


if __name__ == "__main__":
    unittest.main(verbosity=2)
