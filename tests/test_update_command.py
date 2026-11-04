"""Exercise the exact copied command without network or real installation."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

COMMAND = Path(sys.argv.pop(1)).read_text()


class UpdateCommandTests(unittest.TestCase):
    def run_command(self, existing=False, download_fails=False):
        with tempfile.TemporaryDirectory(prefix="document-studio-command-") as folder:
            root = Path(folder)
            home = root / "home with spaces"
            launcher = home / ".local/bin/document-studio"
            launcher.parent.mkdir(parents=True)
            fake_bin = root / "bin"
            fake_bin.mkdir()
            temporary = root / "temporary"
            temporary.mkdir()
            log = root / "calls"
            if existing:
                launcher.write_text('#!/bin/bash\nprintf "launcher:%s\\n" "$*" >> "$TEST_LOG"\n')
                launcher.chmod(0o755)
            curl = fake_bin / "curl"
            curl.write_text('''#!/bin/bash
printf 'download\n' >> "$TEST_LOG"
[[ "$DOWNLOAD_FAILS" == 0 ]] || exit 22
while [[ "$1" != -o ]]; do shift; done
printf '#!/bin/bash\nprintf "install:%%s\\n" "$*" >> "$TEST_LOG"\n' > "$2"
''')
            curl.chmod(0o755)
            env = dict(os.environ, HOME=str(home), TMPDIR=str(temporary),
                       PATH=f"{fake_bin}:/usr/bin:/bin", TEST_LOG=str(log),
                       DOWNLOAD_FAILS="1" if download_fails else "0")
            result = subprocess.run(["/bin/bash", "-c", COMMAND], env=env,
                                    capture_output=True, text=True)
            calls = log.read_text().splitlines() if log.exists() else []
            self.assertEqual(list(temporary.iterdir()), [], "Download staging must be removed")
            return result.returncode, calls

    def test_existing_launcher_preserves_its_configuration(self):
        status, calls = self.run_command(existing=True)
        self.assertEqual(status, 0)
        self.assertEqual(calls, ["launcher:update"])

    def test_new_install_uses_official_repository(self):
        status, calls = self.run_command()
        self.assertEqual(status, 0)
        self.assertEqual(calls, ["download", "install:--repo HyeonWooNa0861/DocumentStudio"])

    def test_failed_download_never_runs_installer(self):
        status, calls = self.run_command(download_fails=True)
        self.assertEqual(status, 22)
        self.assertEqual(calls, ["download"])


if __name__ == "__main__":
    unittest.main()
