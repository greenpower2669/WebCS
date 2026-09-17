from pathlib import Path
import base64

SERVICE = Path("android/poc-camera/src/main/java/online/tek4all/webcs/poc/CameraForegroundService.kt")
RAW_DIR = Path("android/poc-camera/src/main/res/raw")
SFX_DIR = Path("tools/sfx")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old in text:
        return text.replace(old, new, 1)
    if new in text:
        return text
    raise SystemExit(f"Pattern not found for {label}")


# Decode the user-provided audio resources at build time.
RAW_DIR.mkdir(parents=True, exist_ok=True)
for src_name, dst_name in (("tir.ogg.b64", "tir.ogg"), ("reload.ogg.b64", "reload.ogg")):
    src = SFX_DIR / src_name
    dst = RAW_DIR / dst_name
    data = base64.b64decode(src.read_text(encoding="utf-8").strip())
    if not data.startswith(b"OggS"):
        raise SystemExit(f"Invalid OGG payload in {src}")
    dst.write_bytes(data)
    print(f"Decoded {src} -> {dst} ({len(data)} bytes)")


# Keep the source wiring idempotent, without owning the app version anymore.
service = SERVICE.read_text(encoding="utf-8")
service = replace_once(
    service,
    "sfx = SfxEngine().also {",
    "sfx = SfxEngine(this).also {",
    "SfxEngine context",
)
service = replace_once(
    service,
    '''        if (result.hit) {\n            score++\n            prefs.edit().putInt("score", score).apply()\n        }''',
    '''        if (result.hit) {\n            score++\n            prefs.edit().putInt("score", score).apply()\n            sfx.playHitReward()\n        }''',
    "hit reward",
)
SERVICE.write_text(service, encoding="utf-8")

print("User SFX resources prepared")
