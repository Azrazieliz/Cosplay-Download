#!/usr/bin/env python3
"""Verify real Android navigation on emulator, capture screenshots and crash state."""
import subprocess, time, re, xml.etree.ElementTree as ET
from pathlib import Path

def adb(*args, binary=False):
    p=subprocess.run(["adb",*args],capture_output=True,check=True)
    return p.stdout if binary else p.stdout.decode("utf-8","replace")

def screenshot(name):
    Path(name).write_bytes(adb("exec-out","screencap","-p",binary=True))

def nodes():
    adb("shell","uiautomator","dump","/sdcard/window.xml")
    xml=adb("exec-out","cat","/sdcard/window.xml")
    return list(ET.fromstring(xml).iter("node"))

def tap_text(label):
    for attempt in range(3):
        for el in nodes():
            value=el.attrib.get("text","")
            if label.lower() in value.lower():
                b=[int(x) for x in re.findall(r"\d+",el.attrib["bounds"])]
                adb("shell","input","tap",str((b[0]+b[2])//2),str((b[1]+b[3])//2))
                time.sleep(1)
                return
        time.sleep(1)
    raise RuntimeError("Control not found: "+label)

adb("install","-r","app/build/outputs/apk/debug/app-debug.apk")
adb("logcat","-c")
adb("shell","am","start","-W","-n","com.asterion.videoflow/.MainActivity")
time.sleep(4)
screenshot("ui-home.png")
tap_text("Models")
screenshot("ui-models.png")
tap_text("FOLLOW PERFORMER")
screenshot("ui-performer-dialog.png")
adb("shell","input","keyevent","4")
time.sleep(1)
tap_text("Downloads")
screenshot("ui-downloads.png")
tap_text("Sources")
screenshot("ui-sources.png")
pid=adb("shell","pidof","-s","com.asterion.videoflow").strip()
assert pid.isdigit(), "VideoFlow process terminated"
crashes=adb("logcat","-b","crash","-d")
Path("crash.log").write_text(crashes)
assert "FATAL EXCEPTION" not in crashes, "App crashed"
print("Emulator UI navigation + crash smoke passed")
