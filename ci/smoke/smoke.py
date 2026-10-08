#!/usr/bin/env python3
"""Runs the app on a connected emulator and walks through bookmark folders and notes.

Not an instrumentation test: it drives the installed APK from outside with adb, the way a
person would, and leaves a screenshot and the list of visible texts after every step in
the output folder. It fails when an expected element never shows up or the app crashed.

Usage: smoke.py <dir with the built APKs> <output dir>
"""
import glob
import os
import re
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET

PACKAGE = "io.github.usretwaq.ossdict"
MAIN = PACKAGE + "/itkach.aard2.MainActivity"
ARTICLE = PACKAGE + "/itkach.aard2.article.ArticleCollectionActivity"
DICTIONARY_DIR = "/sdcard/Android/data/%s/files/dictionaries" % PACKAGE
HERE = os.path.dirname(os.path.abspath(__file__))

out_dir = "smoke-out"
shot_count = 0
failures = []
report_lines = []


def log(message):
    print(message, flush=True)
    report_lines.append(message)


def adb(*args, check=True, timeout=180, binary=False):
    result = subprocess.run(["adb"] + list(args), stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=timeout)
    if check and result.returncode != 0:
        raise RuntimeError("adb %s failed: %s %s" % (
            " ".join(args), result.stdout.decode("utf-8", "replace")[-400:],
            result.stderr.decode("utf-8", "replace")[-400:]))
    return result.stdout if binary else result.stdout.decode("utf-8", "replace")


def shell(command, check=True, timeout=180):
    return adb("shell", command, check=check, timeout=timeout)


# ---- looking at the screen ----------------------------------------------------

def dump():
    """Returns the view hierarchy of the window in front."""
    last = ""
    for _ in range(8):
        shell("rm -f /sdcard/window_dump.xml", check=False)
        last = shell("uiautomator dump /sdcard/window_dump.xml", check=False)
        xml = adb("exec-out", "cat", "/sdcard/window_dump.xml", check=False)
        if xml.lstrip().startswith("<?xml"):
            try:
                return ET.fromstring(xml)
            except ET.ParseError:
                pass
        time.sleep(1)
    raise RuntimeError("could not dump the window: " + last.strip())


def label(node):
    return node.get("text") or node.get("content-desc") or ""


def find(root, text=None, contains=None, cls=None):
    """Nodes whose text or content description equals `text` / contains `contains`."""
    matches = []
    for node in root.iter("node"):
        if text is not None and node.get("text") != text and node.get("content-desc") != text:
            continue
        if contains is not None and contains not in label(node):
            continue
        if cls is not None and not node.get("class", "").endswith(cls):
            continue
        matches.append(node)
    return matches


def texts(root):
    seen = []
    for node in root.iter("node"):
        value = label(node)
        if value and value not in seen:
            seen.append(value)
    return seen


def wait_for(text=None, contains=None, cls=None, timeout=30):
    deadline = time.time() + timeout
    while True:
        root = dump()
        found = find(root, text=text, contains=contains, cls=cls)
        if found:
            return found[0]
        if time.time() > deadline:
            raise AssertionError("never appeared: text=%r contains=%r class=%r; on screen: %s" % (
                text, contains, cls, texts(root)[:60]))
        time.sleep(1)


def wait_gone(text=None, contains=None, timeout=20):
    deadline = time.time() + timeout
    while True:
        root = dump()
        if not find(root, text=text, contains=contains):
            return
        if time.time() > deadline:
            raise AssertionError("still there: text=%r contains=%r; on screen: %s" % (
                text, contains, texts(root)[:60]))
        time.sleep(1)


def expect(*expected, timeout=30):
    """Waits until every given string is part of some text on screen."""
    deadline = time.time() + timeout
    while True:
        visible = texts(dump())
        missing = [item for item in expected if not any(item in value for value in visible)]
        if not missing:
            return
        if time.time() > deadline:
            raise AssertionError("missing %s; on screen: %s" % (missing, visible[:60]))
        time.sleep(1)


def shot(name):
    """Saves a screenshot plus the texts on screen under the next number."""
    global shot_count
    shot_count += 1
    base = os.path.join(out_dir, "%02d_%s" % (shot_count, name))
    try:
        with open(base + ".png", "wb") as handle:
            handle.write(adb("exec-out", "screencap", "-p", binary=True))
    except Exception as error:  # the report matters more than one picture
        log("  (no screenshot: %r)" % (error,))
    try:
        visible = texts(dump())
        with open(base + ".txt", "w", encoding="utf-8") as handle:
            handle.write("\n".join(visible))
    except Exception as error:
        log("  (no text dump: %r)" % (error,))
    log("  [%02d] %s" % (shot_count, name))


# ---- acting on the screen -----------------------------------------------------

def center(node):
    left, top, right, bottom = [int(value) for value in re.match(
        r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds")).groups()]
    return (left + right) // 2, (top + bottom) // 2


def tap(node):
    x, y = center(node)
    shell("input tap %d %d" % (x, y))
    time.sleep(0.8)


def long_press(node):
    x, y = center(node)
    shell("input swipe %d %d %d %d 900" % (x, y, x, y))
    time.sleep(0.8)


def tap_text(text=None, contains=None, cls=None, timeout=30):
    tap(wait_for(text=text, contains=contains, cls=cls, timeout=timeout))


def type_text(text):
    # `input text` takes %s for a space; the tests only type plain ASCII
    shell("input text '%s'" % text.replace(" ", "%s"))
    time.sleep(0.8)


def back():
    shell("input keyevent 4")
    time.sleep(0.8)


def start_main():
    shell("am start -W -n %s" % MAIN)
    time.sleep(1.5)


def lookup(word):
    shell("am start -W -n %s -a aard2.lookup -e query %s" % (ARTICLE, word))
    wait_for(text="Folders & note", timeout=60)
    time.sleep(1.5)


def open_tab(name):
    """Taps an entry of the bottom navigation, told from the toolbar title by its position."""
    wait_for(text=name)
    candidates = find(dump(), text=name)
    tap(max(candidates, key=lambda node: center(node)[1]))
    time.sleep(1.0)


# ---- steps --------------------------------------------------------------------

def step(name, function):
    log("== " + name)
    try:
        function()
        log("   ok")
        return True
    except Exception as error:
        failures.append(name)
        log("   FAILED: %s" % (error,))
        log(traceback.format_exc())
        shot("FAILED_" + re.sub(r"\W+", "_", name))
        return False


def find_apks(apk_dir):
    apks = sorted(glob.glob(os.path.join(apk_dir, "*.apk")))
    debug = [path for path in apks if path.endswith("-debug.apk")]
    release = [path for path in apks if not path.endswith("-debug.apk")]
    if not debug or not release:
        raise RuntimeError("need a debug and a release APK in %s, found %s" % (apk_dir, apks))
    return debug[0], release[0]


def install_dictionaries():
    """Puts the test dictionaries where the app picks dictionaries up on its own at start.

    That folder belongs to the app, so depending on the Android version adb may not be
    allowed to write there. The debug build lets `run-as` do it with the app's identity.
    """
    work = os.path.join(out_dir, "dictionaries")
    subprocess.run([sys.executable, os.path.join(HERE, "make_dictionary.py"), work], check=True)
    files = [os.path.join(work, "smoke-nl-en.zip"),
             os.path.join(HERE, "..", "..", "src/test/resources/itkach/aard2/dictionary/dsl/test.dsl")]
    for path in files:
        name = os.path.basename(path)
        adb("push", path, "/data/local/tmp/" + name)
        shell("chmod 644 /data/local/tmp/" + name)
        pushed = adb("push", path, DICTIONARY_DIR + "/" + name, check=False)
        listing = shell("ls %s" % DICTIONARY_DIR, check=False)
        if name not in listing:
            log("   adb push refused (%s), copying with run-as" % pushed.strip()[-120:])
            shell("run-as %s mkdir -p %s" % (PACKAGE, DICTIONARY_DIR), check=False)
            shell("run-as %s cp /data/local/tmp/%s %s/%s" % (PACKAGE, name, DICTIONARY_DIR, name))
    log("   in the dictionary folder: " + shell("ls -la %s" % DICTIONARY_DIR, check=False).strip())


def setup(debug_apk, release_apk):
    shell("settings put secure show_ime_with_hard_keyboard 0", check=False)
    log("   " + shell("getprop ro.build.version.release").strip() + " / API "
        + shell("getprop ro.build.version.sdk").strip() + " / " + shell("wm size").strip())
    adb("uninstall", PACKAGE, check=False)
    # Debug build first: only a debuggable app lets run-as place files in its folders
    adb("install", "-r", "-g", debug_apk, timeout=300)
    start_main()
    shot("first_start_no_dictionaries")
    install_dictionaries()
    shell("am force-stop " + PACKAGE)
    start_main()
    open_tab("Dictionaries")
    expect("Smoke NL-EN", "Test DSL", timeout=90)
    shot("dictionaries_loaded_debug_build")


def update_to_release(release_apk):
    shell("am force-stop " + PACKAGE)
    # The build people install, on top of the debug one: also proves an update keeps the data
    adb("install", "-r", "-g", release_apk, timeout=300)
    start_main()
    open_tab("Dictionaries")
    expect("Smoke NL-EN", "Test DSL", timeout=90)
    shot("dictionaries_after_update_to_release")


def article_actions():
    lookup("lopen")
    expect("Bookmark", "Folders & note")
    shot("article_not_bookmarked")


def file_article_with_note():
    tap_text(text="Folders & note")
    wait_for(text="New folder")
    expect("No folders yet", "Save", "Cancel")
    shot("edit_dialog_no_folders_yet")
    tap_text(text="New folder")
    wait_for(cls="EditText")
    shot("new_folder_prompt")
    type_text("Verbs")
    tap_text(text="Save")
    # The tick box, not the text field of the prompt that is going away
    wait_for(text="Verbs", cls="CheckBox")
    shot("edit_dialog_folder_created_and_ticked")
    tap(wait_for(cls="EditText"))
    type_text("to walk, irregular past tense")
    shot("edit_dialog_note_typed")
    tap_text(text="Save")
    expect("to walk, irregular past tense", "Folders: Verbs")
    shot("article_with_note_bar")


def bookmark_plain_and_second_folder():
    lookup("huis")
    tap_text(text="Bookmark")
    shot("article_plain_bookmark")
    lookup("fiets")
    tap_text(text="Folders & note")
    wait_for(text="New folder")
    expect("Verbs")
    tap_text(text="New folder")
    wait_for(cls="EditText")
    type_text("Things")
    tap_text(text="Save")
    wait_for(text="Things", cls="CheckBox")
    shot("edit_dialog_second_folder")
    tap_text(text="Save")
    expect("Folders: Things")
    shot("article_folder_only")
    lookup("gezellig")
    tap_text(text="Folders & note")
    wait_for(text="New folder")
    shot("edit_dialog_cancelled_nothing_saved")
    tap_text(text="Cancel")


def bookmarks_tab():
    start_main()
    open_tab("Bookmarks")
    expect("All (3)", "Things (1)", "Verbs (1)", "No folder (1)", "New folder",
           "lopen", "huis", "fiets", "to walk, irregular past tense")
    shot("bookmarks_all")
    if find(dump(), contains="gezellig"):
        raise AssertionError("cancelling the dialog bookmarked the article")


def filter_by_folder():
    tap_text(contains="Verbs (1)")
    wait_gone(text="huis")
    expect("lopen")
    shot("bookmarks_folder_verbs")
    tap_text(contains="No folder (1)")
    wait_gone(text="lopen")
    expect("huis")
    shot("bookmarks_no_folder")
    tap_text(contains="All (3)")
    expect("lopen", "huis", "fiets")


def file_several_at_once():
    long_press(wait_for(text="lopen"))
    wait_for(text="Folders & note")
    tap_text(text="huis")
    expect("2 selected")
    shot("bookmarks_two_selected")
    tap_text(text="Folders & note")
    wait_for(text="New folder")
    expect("Things", "Verbs")
    if find(dump(), cls="EditText"):
        raise AssertionError("the note field is offered for several bookmarks")
    shot("edit_dialog_two_bookmarks_verbs_indeterminate")
    tap_text(text="Things")
    tap_text(text="Save")
    expect("Things (3)", "Verbs (1)")
    shot("bookmarks_after_filing_two")


def edit_single_from_list():
    long_press(wait_for(text="lopen"))
    tap_text(text="Folders & note")
    wait_for(text="New folder")
    expect("to walk, irregular past tense")
    shot("edit_dialog_single_from_list")
    tap_text(text="Cancel")
    back()


def manage_folders():
    tap_text(text="More options")
    tap_text(text="Manage folders")
    expect("Things", "Verbs", "New folder")
    shot("manage_folders")
    tap_text(text="Verbs")
    expect("Rename", "Delete")
    shot("folder_actions")
    tap_text(text="Rename")
    wait_for(cls="EditText")
    type_text("2")
    shot("rename_prompt")
    tap_text(text="Save")
    expect("Verbs2 (1)")
    shot("bookmarks_after_rename")
    long_press(wait_for(contains="Things (3)"))
    expect("Rename", "Delete")
    tap_text(text="Delete")
    expect("The bookmarks in it are kept")
    shot("delete_folder_confirmation")
    tap_text(text="Delete")
    wait_gone(contains="Things (")
    expect("All (3)", "Verbs2 (1)", "No folder (2)")
    shot("bookmarks_after_delete_folder")


def empty_folder_and_filter_text():
    tap_text(text="New folder")
    wait_for(cls="EditText")
    type_text("Empty one")
    tap_text(text="Save")
    expect("Empty one (0)")
    tap_text(contains="Empty one (0)")
    expect("No bookmarks in this folder")
    shot("bookmarks_empty_folder")
    tap_text(contains="All (3)")
    expect("lopen", "huis", "fiets")


def article_from_bookmarks_and_removal():
    tap_text(text="lopen")
    wait_for(text="Folders & note", timeout=60)
    expect("to walk, irregular past tense", "Folders: Verbs2")
    shot("article_opened_from_bookmarks")
    tap(wait_for(contains="to walk, irregular past tense"))
    wait_for(text="New folder")
    shot("edit_dialog_opened_from_note_bar")
    tap_text(text="Cancel")
    tap_text(text="Bookmark")
    expect("Its note and folders will be lost")
    shot("remove_bookmark_confirmation")
    tap_text(text="No")
    expect("to walk, irregular past tense")


def restart_keeps_everything():
    shell("am force-stop " + PACKAGE)
    start_main()
    open_tab("Bookmarks")
    expect("All (3)", "Verbs2 (1)", "Empty one (0)", "No folder (2)",
           "lopen", "to walk, irregular past tense")
    shot("bookmarks_after_restart")


def dark_theme():
    shell("cmd uimode night yes", check=False)
    time.sleep(2)
    shell("am force-stop " + PACKAGE)
    start_main()
    open_tab("Bookmarks")
    expect("All (3)")
    shot("dark_bookmarks")
    tap_text(text="lopen")
    wait_for(text="Folders & note", timeout=60)
    time.sleep(2)
    shot("dark_article_with_note_bar")
    tap_text(text="Folders & note")
    wait_for(text="New folder")
    shot("dark_edit_dialog")
    tap_text(text="Cancel")
    shell("cmd uimode night no", check=False)


def check_crashes():
    logcat = adb("logcat", "-d", "-v", "threadtime", timeout=120)
    with open(os.path.join(out_dir, "logcat.txt"), "w", encoding="utf-8") as handle:
        handle.write(logcat)
    # Only this app: other processes of a fresh emulator image crash on their own now and then
    crashes = [line for line in logcat.splitlines()
               if ("Process: " + PACKAGE) in line or ("ANR in " + PACKAGE) in line]
    if crashes:
        raise AssertionError("the app crashed or froze: " + " | ".join(crashes[:5]))


def main():
    global out_dir
    apk_dir = sys.argv[1]
    out_dir = sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    debug_apk, release_apk = find_apks(apk_dir)
    log("debug: %s\nrelease: %s" % (debug_apk, release_apk))
    adb("logcat", "-c", check=False)

    ready = step("install, load the test dictionaries", lambda: setup(debug_apk, release_apk))
    ready = ready and step("update to the release build", lambda: update_to_release(release_apk))
    if ready:
        steps = [
            ("article shows the new action", article_actions),
            ("file an article under a new folder with a note", file_article_with_note),
            ("plain bookmark, second folder, cancelled edit", bookmark_plain_and_second_folder),
            ("bookmarks tab lists folders, notes and counts", bookmarks_tab),
            ("pick a folder", filter_by_folder),
            ("file several bookmarks at once", file_several_at_once),
            ("edit one bookmark from the list", edit_single_from_list),
            ("rename and delete folders", manage_folders),
            ("empty folder", empty_folder_and_filter_text),
            ("article opened from bookmarks, guarded removal", article_from_bookmarks_and_removal),
            ("restart keeps folders and notes", restart_keeps_everything),
            ("dark theme", dark_theme),
        ]
        for name, function in steps:
            if not step(name, function):
                # Get back to a known place so the following steps still tell something
                shell("am force-stop " + PACKAGE, check=False)
                step("recover after: " + name, lambda: (start_main(), open_tab("Bookmarks")))
    step("no crash in the log", check_crashes)

    log("RESULT: %s" % ("FAILED: " + "; ".join(failures) if failures else "OK"))
    with open(os.path.join(out_dir, "report.txt"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(report_lines) + "\n")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
