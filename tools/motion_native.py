"""Build the Android motion JNI entry points for an existing host JVM regression."""
from pathlib import Path
import platform
import shutil
import subprocess
import sys


def build_motion_library(directory):
    src = Path("crates/folia-spatial/src").resolve()
    harness = Path(directory, "motion.rs")
    # Rust accepts forward slashes in attribute paths on all supported hosts.
    harness.write_text(f'#[path = "{src.as_posix()}/jni.rs"] mod jni;\n'
                       f'#[path = "{src.as_posix()}/ui_motion.rs"] mod ui_motion;\n')
    system = platform.system()
    lib = {"Darwin": "libfolia_bridge.dylib", "Windows": "folia_bridge.dll"}.get(system, "libfolia_bridge.so")
    rustc = shutil.which("rustc") or ("/opt/homebrew/opt/rustup/bin/rustc" if sys.platform == "darwin" else "rustc")
    subprocess.run([rustc, "--edition=2021", "--crate-type=cdylib", "-O", "-Adead_code",
                    str(harness), "-o", str(Path(directory, lib))], check=True)
