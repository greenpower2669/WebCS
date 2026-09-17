from pathlib import Path

p = Path("android/poc-camera/src/main/java/online/tek4all/webcs/poc/MainActivity.kt")
s = p.read_text(encoding="utf-8")
old = "devices.values.getOrNull(which)"
new = "devices.values.elementAtOrNull(which)"
if old in s:
    s = s.replace(old, new, 1)
elif new not in s:
    raise SystemExit("Bluetooth pairing list selection anchor not found")
p.write_text(s, encoding="utf-8")
print("Bluetooth pairing list selection hotfix applied")
