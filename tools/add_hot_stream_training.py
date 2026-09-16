from pathlib import Path
import runpy
import subprocess

# WebCS v0.9 source is already committed. This build hook now advances the
# current Android source to v0.10 with relative orientation calibration.
runpy.run_path(str(Path("tools/add_orientation_calibration_v010.py")), run_name="__main__")

# The existing workflow stages the two Kotlin sources itself. Stage the other
# Android files changed by v0.10 so the same generated-source commit persists
# the landscape lock and version bump too.
subprocess.run(
    [
        "git",
        "add",
        "android/poc-camera/src/main/AndroidManifest.xml",
        "android/poc-camera/build.gradle.kts",
    ],
    check=True,
)
