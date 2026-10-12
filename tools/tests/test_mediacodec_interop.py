import os
from pathlib import Path
import shutil
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/check_mediacodec_interop.py"


def opennow_checkout():
    for candidate in (os.environ.get("OPENNOW_DIR"), str(ROOT.parent / "opennow")):
        if candidate and (Path(candidate) / "native/opennow-streamer/Cargo.toml").is_file():
            return candidate
    return None


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "needs a JDK")
@unittest.skipUnless(shutil.which("cargo"), "needs cargo")
@unittest.skipUnless(opennow_checkout(), "needs an OpenNOW checkout (OPENNOW_DIR)")
class MediaCodecInteropTest(unittest.TestCase):
    def test_java_handler_and_rust_client_interoperate(self):
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--opennow", opennow_checkout()],
            capture_output=True, text=True, timeout=2400)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("PASS:", result.stdout)


if __name__ == "__main__":
    unittest.main()
