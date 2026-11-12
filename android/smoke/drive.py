#!/usr/bin/env python3
"""
CI's smoke test of the Android release build (.github/workflows/hush.yml, android-smoke).

It runs the minified APK a phone gets, on an emulator, against a real server. It picks the server, signs up, then
opens every main screen and does the everyday things: sends a message, follows someone, opens a story and a post,
changes a chat theme, restarts the app and leaves it in the background. It fails if Hush crashes or stops responding
anywhere. A screenshot of every step goes to SMOKE_OUT.

Needs adb, and these variables: SMOKE_SERVER (the server's https address), SMOKE_INVITE, SMOKE_SEED (the file the
seeding test writes once the other person is ready), SMOKE_OUT.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "xyz.aezdek.hush"
ACTIVITY = f"{PKG}/.MainActivity"
OUT = os.environ["SMOKE_OUT"]
SERVER = os.environ["SMOKE_SERVER"].removeprefix("https://").rstrip("/")
INVITE = os.environ["SMOKE_INVITE"].strip()
SEED = os.environ["SMOKE_SEED"]
PASSWORD = "ava-smoke-password-1"

crashes: list[str] = []
misses: list[str] = []
shots = 0


class Missing(Exception):
    pass


def log(message: str) -> None:
    print(message, flush=True)


def adb(*args: str, timeout: int = 60) -> str:
    try:
        result = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        log(f"  adb {' '.join(args)} timed out")
        return ""
    return result.stdout.decode("utf-8", "replace")


def shell(command: str, timeout: int = 60) -> str:
    return adb("shell", command, timeout=timeout)


def alive() -> bool:
    return shell(f"pidof {PKG}").strip() != ""


def shot(name: str) -> None:
    global shots
    shots += 1
    path = os.path.join(OUT, f"{shots:02d}-{name}.png")
    with open(path, "wb") as out:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=out, timeout=30)


def check(step: str) -> None:
    """Records a crash or an ANR of Hush since the last check, then clears the buffers for the next step."""
    crash = adb("logcat", "-d", "-b", "crash")
    if PKG in crash:
        crashes.append(f"{step}:\n{crash.strip()}")
        log(f"  CRASH during {step}")
    anr = [line for line in adb("logcat", "-d", "-b", "system").splitlines() if f"ANR in {PKG}" in line]
    if anr:
        crashes.append(f"{step}: not responding\n" + "\n".join(anr))
        log(f"  ANR during {step}")
    adb("logcat", "-b", "crash", "-b", "system", "-c")


last_dump_error = ""


def nodes() -> list[dict]:
    global last_dump_error
    for _ in range(4):
        shell("rm -f /sdcard/ui.xml")
        said = shell("uiautomator dump /sdcard/ui.xml", timeout=45).strip()
        xml = adb("exec-out", "cat", "/sdcard/ui.xml")
        if not xml.lstrip().startswith("<?xml"):
            last_dump_error = said or "no output"
        if xml.lstrip().startswith("<?xml"):
            try:
                root = ET.fromstring(xml)
            except ET.ParseError:
                root = None
            if root is not None:
                found = []
                for n in root.iter("node"):
                    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
                    if not m:
                        continue
                    x1, y1, x2, y2 = map(int, m.groups())
                    if x2 <= x1 or y2 <= y1:
                        continue
                    found.append({
                        "text": n.get("text", ""), "desc": n.get("content-desc", ""), "cls": n.get("class", ""),
                        "pkg": n.get("package", ""), "x": (x1 + x2) // 2, "y": (y1 + y2) // 2,
                    })
                return found
        time.sleep(1.5)
    return []


def on_screen() -> str:
    """What the screen shows, for the log when something can't be found."""
    seen = [n["text"] or n["desc"] for n in nodes() if (n["text"] or n["desc"]) and n["pkg"] == PKG]
    if not seen:
        return f"nothing readable (uiautomator: {last_dump_error})"
    return " | ".join(seen[:40])


def tap_at(node: dict) -> None:
    shell(f"input tap {node['x']} {node['y']}")
    time.sleep(1.2)


def dismiss_dialogs(found: list[dict]) -> bool:
    """A system "isn't responding" dialog over the app: a slow emulator's launcher, usually. Waits it out
    (and counts it if it is Hush's own). True if there was one."""
    stuck = [n for n in found if n["pkg"] == "android" and "isn't responding" in n["text"]]
    if not stuck:
        return False
    if "Hush" in stuck[0]["text"]:
        crashes.append(f"not responding: {stuck[0]['text']}")
        log(f"  ANR: {stuck[0]['text']}")
    else:
        log(f"  dismissed a system dialog: {stuck[0]['text']}")
    wait = [n for n in found if n["pkg"] == "android" and n["text"] == "Wait"]
    if wait:
        tap_at(wait[0])
    else:
        back()
    return True


def find(matches, timeout: float = 20) -> list[dict]:
    end = time.time() + timeout
    while True:
        found = nodes()
        if dismiss_dialogs(found):
            continue
        hits = sorted((n for n in found if matches(n)), key=lambda n: (n["y"], n["x"]))
        if hits or time.time() > end:
            return hits
        time.sleep(1)


def labelled(label: str, exact: bool = True):
    if exact:
        return lambda n: n["text"] == label or n["desc"] == label
    return lambda n: label.lower() in n["text"].lower() or label.lower() in n["desc"].lower()


def tap(label: str, timeout: float = 20, which: int = 0, exact: bool = True) -> None:
    hits = find(labelled(label, exact), timeout)
    try:
        node = hits[which]
    except IndexError:
        raise Missing(label) from None
    shell(f"input tap {node['x']} {node['y']}")
    time.sleep(1.2)


def long_press(label: str, timeout: float = 20) -> None:
    hits = find(labelled(label), timeout)
    if not hits:
        raise Missing(label)
    x, y = hits[0]["x"], hits[0]["y"]
    shell(f"input swipe {x} {y} {x} {y} 900")
    time.sleep(1.2)


def fields(timeout: float = 20) -> list[dict]:
    return find(lambda n: n["cls"] == "android.widget.EditText" and n["pkg"] == PKG, timeout)


def keyboard_shown() -> bool:
    if re.search(r"(mInputShown|mIsInputViewShown|isInputViewShown|inputShown)=true", shell("dumpsys input_method")):
        return True
    return bool(re.search(r"mViewVisibility=0x0\b", shell("dumpsys window InputMethod")))


def type_into(field: dict, text: str) -> None:
    """Types into a field, then puts the keyboard away so it covers nothing the next step looks for."""
    shell(f"input tap {field['x']} {field['y']}")
    time.sleep(0.8)
    escaped = text.replace(" ", "%s").replace("'", "")
    shell(f"input text '{escaped}'")
    time.sleep(0.8)
    if keyboard_shown():
        back()


def visible(label: str, timeout: float = 20, exact: bool = True) -> bool:
    return bool(find(labelled(label, exact), timeout))


def launch() -> None:
    shell(f"am start -W -n {ACTIVITY}", timeout=90)
    time.sleep(3)


def open_screen(target: str) -> None:
    shell(f"am start -n {ACTIVITY} --es open '{target}'")
    time.sleep(3)


def back() -> None:
    shell("input keyevent 4")
    time.sleep(1.2)


def step(name: str, action) -> None:
    log(f"- {name}")
    try:
        action()
    except Missing as e:
        misses.append(f"{name}: couldn't find \"{e}\"")
        log(f"  couldn't find \"{e}\". On screen: {on_screen()}")
    except Exception as e:  # noqa: BLE001 - a broken step shouldn't stop the rest
        misses.append(f"{name}: {e}")
        log(f"  {e}")
    time.sleep(1)
    shot(re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-"))
    check(name)
    if not alive():
        log("  Hush is not running; starting it again")
        launch()


def seeded() -> dict:
    end = time.time() + 300
    while time.time() < end:
        if os.path.exists(SEED):
            values = dict(line.split("=", 1) for line in open(SEED).read().splitlines() if "=" in line)
            if "thread" in values:
                return values
        time.sleep(3)
    raise SystemExit("The seeding test never got ready (see seed.log)")


def main() -> int:
    os.makedirs(OUT, exist_ok=True)
    adb("wait-for-device", timeout=300)
    # A freshly booted emulator is slow for a while; let it settle before Hush is timed against anything.
    time.sleep(25)
    dismiss_dialogs(nodes())
    for permission in ("POST_NOTIFICATIONS", "CAMERA", "RECORD_AUDIO"):
        shell(f"pm grant {PKG} android.permission.{permission}")
    adb("logcat", "-b", "all", "-c")

    # ------------------------------------------------------------------------------------------- first start
    step("first start asks for the server", launch)

    def choose_server():
        boxes = fields(timeout=30)
        if not boxes:
            raise Missing("Server address field")
        type_into(boxes[0], SERVER)
        tap("Continue")
        # The app restarts on the new server.
        if not visible("Create account", timeout=60):
            raise Missing("Create account")
    step("choose the server", choose_server)

    def sign_up():
        tap("Create account", which=0)
        time.sleep(1.5)
        # Invite code, username, password, confirm password: each looked up again once the keyboard is away.
        for i, text in enumerate([INVITE, "ava", PASSWORD, PASSWORD]):
            boxes = fields()
            if len(boxes) <= i:
                raise Missing(f"sign-up field {i + 1} (found {len(boxes)})")
            type_into(boxes[i], text)
        tap("Create account", which=-1)
        if not visible("Home", timeout=180):
            raise Missing("Home (signed in)")
    step("sign up", sign_up)
    if not visible("Home", timeout=5):
        log("Signing up didn't work, so nothing else can be tried.")
        report()
        return 1

    step("home feed", lambda: time.sleep(4))
    seed = seeded()
    log(f"The other person is ready: {seed}")
    thread, maya, post = seed["thread"], seed["maya"], seed["post"]

    # ----------------------------------------------------------------------------------------------- screens
    step("tabs: search", lambda: tap("Search"))
    step("tabs: reels", lambda: tap("Reels"))
    step("tabs: home", lambda: tap("Home"))
    step("create sheet", lambda: tap("Create"))
    back()
    step("activity", lambda: open_screen("activity"))
    step("accept maya's follow request", lambda: tap("Confirm", timeout=30))
    step("maya's profile", lambda: open_screen(f"profile:{maya}"))
    step("follow maya", lambda: tap("Follow", timeout=10))
    step("maya's story", lambda: (open_screen(f"stories:{maya}"), time.sleep(4)))
    back()
    step("maya's post", lambda: open_screen(f"post:{post}"))
    step("like the post", lambda: tap("Like", timeout=10))
    step("hashtag", lambda: open_screen("hashtag:film"))
    step("inbox", lambda: open_screen("inbox"))
    step("chat with a theme", lambda: open_screen(f"chat:{thread}"))

    def send_message():
        boxes = fields()
        if not boxes:
            raise Missing("message field")
        type_into(boxes[-1], "Hello from the smoke test")
        tap("Send")
        time.sleep(4)
    step("send a message", send_message)
    step("message menu", lambda: long_press("Hey! Is this the new app?"))
    back()
    step("chat details", lambda: open_screen(f"chat-info:{thread}"))
    step("theme picker", lambda: tap("Theme"))
    step("pick a theme", lambda: tap("Aurora", timeout=10))
    step("chat in the new theme", lambda: open_screen(f"chat:{thread}"))
    step("new chat", lambda: open_screen("new-chat"))
    step("my profile", lambda: open_screen("profile"))
    step("edit profile", lambda: open_screen("edit-profile"))
    step("followers", lambda: open_screen("followers"))
    step("verify maya", lambda: open_screen(f"verify:{maya}"))
    step("create a note", lambda: open_screen("create:note"))
    step("create a story", lambda: open_screen("create:story"))
    step("create a post (photo picker)", lambda: (open_screen("create:post"), time.sleep(2), back()))
    step("settings", lambda: open_screen("settings"))
    for section in ("notifications", "archive", "privacy", "close-friends", "blocked", "security", "account",
                    "appearance", "updates", "admin"):
        step(f"settings: {section}", lambda s=section: open_screen(f"settings:{s}"))
    step("call readiness", lambda: open_screen("readiness"))
    step("back to home", lambda: open_screen("home"))

    # ----------------------------------------------------------------------------------------- app lifecycle
    def restart():
        shell(f"am force-stop {PKG}")
        time.sleep(2)
        launch()
        if not visible("Home", timeout=60):
            raise Missing("Home after a restart")
    step("restart (signed in from the saved session)", restart)

    def background():
        shell("input keyevent 3")
        time.sleep(15)
        launch()
    step("background and back", background)
    step("chat after coming back", lambda: open_screen(f"chat:{thread}"))
    return report()


def report() -> int:
    with open(os.path.join(OUT, "logcat.txt"), "w") as out:
        out.write(adb("logcat", "-d", "-v", "threadtime", timeout=120))
    log("")
    if misses:
        log("Steps that couldn't be done (the test may need updating):")
        for miss in misses:
            log(f"  {miss}")
    if crashes:
        log(f"\nHush crashed or stopped responding {len(crashes)} time(s):\n")
        for crash in crashes:
            log(crash + "\n")
        return 1
    log("No crashes.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
