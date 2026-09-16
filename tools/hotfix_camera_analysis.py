from pathlib import Path

# The CameraX single-session fix is already committed in the current WebCS
# Android source. Keep this workflow hook as an explicit no-op so historical
# CI remains compatible without trying to rewrite newer Preview code.
path = Path("android/poc-camera/src/main/java/online/tek4all/webcs/poc/CameraForegroundService.kt")
text = path.read_text(encoding="utf-8")
if "WEBSC_V090" not in text:
    raise SystemExit("Unexpected pre-v0.9 source: CameraX hotfix baseline missing")
print("CameraX hotfix already integrated in current source")
