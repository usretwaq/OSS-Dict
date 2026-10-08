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
warnings = []
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
    # CLEAR_TOP: an article opened from a list sits on top of the main screen in the same
    # task, and a plain start would only bring that article back to the front
    shell("am start -W --activity-clear-top -n %s" % MAIN)
    time.sleep(1.5)


def lookup(word):
    # NEW_TASK | CLEAR_TOP, as the README says: without the flags a second lookup only
    # brings the article of the first one back to the front
    shell("am start -W -f 335544320 -n %s -a aard2.lookup -e query %s" % (ARTICLE, word))
    wait_for(text=word, timeout=60)
    wait_for(text="Bookmark", timeout=30)
    time.sleep(1.5)


def open_tab(name):
    """Taps an entry of the bottom navigation, told from the toolbar title by its position."""
    wait_for(text=name)
    candidates = find(dump(), text=name)
    tap(max(candidates, key=lambda node: center(node)[1]))
    time.sleep(1.0)


# ---- the folder selector above the bookmark list: a row that scrolls sideways ---

CHIP_LABEL = re.compile(r"^(.+ \(\d+\)|New folder|Manage folders)$")


def visible_chips():
    return [node for node in dump().iter("node") if CHIP_LABEL.match(node.get("text") or "")]


def chip_labels():
    return [node.get("text") for node in visible_chips()]


def at_start(labels):
    return bool(labels) and labels[0].startswith("All (")


def at_end(labels):
    # "Manage folders" closes the row once folders exist, "New folder" before that
    return "Manage folders" in labels or (len(labels) == 2 and labels[1] == "New folder")


def scroll_chips(forward):
    """Swipes the row sideways. Only for a row wider than the screen: a swipe that does not
    start on a chip turns the page of the tabs instead."""
    chips = visible_chips()
    if not chips:
        raise AssertionError("no folder selector on screen: %s" % texts(dump())[:40])
    y = center(chips[0])[1]
    width = int(re.search(r"(\d+)x\d+", shell("wm size")).group(1))
    left, right = int(width * 0.25), int(width * 0.75)
    if forward:
        shell("input swipe %d %d %d %d 400" % (right, y, left, y))
    else:
        shell("input swipe %d %d %d %d 400" % (left, y, right, y))
    time.sleep(1.0)


def all_chips():
    """Labels of the whole selector, in order, scrolling through it when it does not fit."""
    labels = chip_labels()
    for _ in range(8):
        if at_start(labels):
            break
        scroll_chips(forward=False)
        labels = chip_labels()
    collected = list(labels)
    for _ in range(10):
        if at_end(labels):
            break
        scroll_chips(forward=True)
        labels = chip_labels()
        collected += [item for item in labels if item not in collected]
    for _ in range(8):
        if at_start(chip_labels()):
            break
        scroll_chips(forward=False)
    return collected


def expect_chips(*wanted):
    deadline = time.time() + 20
    while True:
        labels = all_chips()
        if labels == list(wanted):
            return
        if time.time() > deadline:
            raise AssertionError("folder selector shows %s, expected %s" % (labels, list(wanted)))
        time.sleep(1)


def find_chip(label):
    for forward in (True, False):
        for _ in range(10):
            chips = visible_chips()
            matches = [node for node in chips if node.get("text") == label]
            if matches:
                return matches[0]
            labels = [node.get("text") for node in chips]
            if at_end(labels) if forward else at_start(labels):
                break
            scroll_chips(forward=forward)
    raise AssertionError("no %r in the folder selector, it shows %s" % (label, all_chips()))


def tap_chip(label):
    tap(find_chip(label))


def open_article_dialog_from_menu():
    tap_text(text="More options")
    tap_text(text="Folders & note")
    wait_for(text="New folder")


# ---- steps --------------------------------------------------------------------

def step(name, function, optional=False):
    """Runs one part of the walk-through. A failing optional step is reported without
    failing the run: it leans on screens of the system, which differ between versions."""
    log("== " + name)
    try:
        function()
        log("   ok")
        return True
    except Exception as error:
        (warnings if optional else failures).append(name)
        log("   %s: %s" % ("WARNING, optional step failed" if optional else "FAILED", error))
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


def article_toolbar_unchanged():
    lookup("lopen")
    # The two actions the toolbar of the original app shows on a phone are still there
    expect("Bookmark", "Full Screen")
    if find(dump(), contains="Add a note or folders"):
        raise AssertionError("the note bar shows for an article that is not bookmarked")
    shot("article_not_bookmarked")
    tap_text(text="More options")
    expect("Folders & note", "Find in page")
    shot("article_overflow_menu")
    back()


def file_article_with_note():
    open_article_dialog_from_menu()
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


def bookmark_then_bar():
    lookup("huis")
    tap_text(text="Bookmark")
    expect("Add a note or folders")
    shot("article_bookmarked_bar_invites")
    lookup("fiets")
    tap_text(text="Bookmark")
    tap_text(text="Add a note or folders")
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


def cancelled_edit_saves_nothing():
    lookup("gezellig")
    open_article_dialog_from_menu()
    tap(wait_for(cls="EditText"))
    type_text("never saved")
    shot("edit_dialog_about_to_cancel")
    tap_text(text="Cancel")
    time.sleep(1)
    if find(dump(), contains="never saved") or find(dump(), contains="Add a note or folders"):
        raise AssertionError("cancelling the dialog bookmarked the article")
    lookup("zuinig")
    open_article_dialog_from_menu()
    tap_text(text="Save")
    time.sleep(1)
    if find(dump(), contains="Add a note or folders"):
        raise AssertionError("saving an empty dialog bookmarked the article")


def bookmarks_tab():
    start_main()
    open_tab("Bookmarks")
    expect("lopen", "huis", "fiets", "to walk, irregular past tense")
    # Filter, sort direction and sort order: the toolbar of the original app
    expect("Filter", "Descending", "By Time")
    expect_chips("All (3)", "Things (1)", "Verbs (1)", "No folder (1)", "New folder", "Manage folders")
    shot("bookmarks_all")
    if find(dump(), contains="gezellig") or find(dump(), contains="zuinig"):
        raise AssertionError("an article that was never saved is in the bookmarks")


def filter_by_folder():
    tap_chip("Verbs (1)")
    wait_gone(text="huis")
    expect("lopen")
    shot("bookmarks_folder_verbs")
    tap_chip("No folder (1)")
    wait_gone(text="lopen")
    expect("huis")
    shot("bookmarks_no_folder")
    tap_chip("All (3)")
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
    expect_chips("All (3)", "Things (3)", "Verbs (1)", "New folder", "Manage folders")
    expect("to walk, irregular past tense")
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
    tap_chip("Manage folders")
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
    expect_chips("All (3)", "Things (3)", "Verbs2 (1)", "New folder", "Manage folders")
    shot("bookmarks_after_rename")
    long_press(find_chip("Things (3)"))
    expect("Rename", "Delete")
    tap_text(text="Delete")
    expect("The bookmarks in it are kept")
    shot("delete_folder_confirmation")
    tap_text(text="Delete")
    expect_chips("All (3)", "Verbs2 (1)", "No folder (2)", "New folder", "Manage folders")
    expect("lopen", "huis", "fiets")
    shot("bookmarks_after_delete_folder")


def empty_folder():
    tap_chip("New folder")
    wait_for(cls="EditText")
    type_text("Empty one")
    tap_text(text="Save")
    expect_chips("All (3)", "Empty one (0)", "Verbs2 (1)", "No folder (2)", "New folder", "Manage folders")
    tap_chip("Empty one (0)")
    expect("No bookmarks in this folder")
    shot("bookmarks_empty_folder")
    tap_chip("All (3)")
    expect("lopen", "huis", "fiets")


def article_from_bookmarks_and_removal():
    tap_text(text="lopen")
    wait_for(text="Bookmark", timeout=60)
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


def other_lists_unchanged():
    start_main()
    open_tab("History")
    expect("lopen", "huis", "fiets", "gezellig")
    if visible_chips():
        raise AssertionError("the history list shows a folder selector")
    if find(dump(), contains="to walk, irregular past tense"):
        raise AssertionError("the history list shows a bookmark note")
    shot("history_unchanged")
    open_tab("Lookup")
    shot("lookup_unchanged")
    open_tab("Bookmarks")
    expect("lopen", "huis", "fiets")


def filter_searches_notes():
    tap_text(text="Filter")
    time.sleep(1)
    type_text("irregular")
    wait_gone(text="huis")
    expect("lopen", "to walk, irregular past tense")
    shot("bookmarks_filter_matches_note")


def restart_keeps_everything():
    shell("am force-stop " + PACKAGE)
    start_main()
    open_tab("Bookmarks")
    expect("lopen", "huis", "fiets", "to walk, irregular past tense")
    expect_chips("All (3)", "Empty one (0)", "Verbs2 (1)", "No folder (2)", "New folder", "Manage folders")
    tap_chip("Verbs2 (1)")
    wait_gone(text="huis")
    shell("am force-stop " + PACKAGE)
    start_main()
    open_tab("Bookmarks")
    # The folder that was being shown is shown again
    expect("lopen")
    if find(dump(), text="huis"):
        raise AssertionError("the folder being shown was forgotten over a restart")
    shot("bookmarks_after_restart_still_in_folder")
    tap_chip("All (3)")
    expect("lopen", "huis", "fiets")


def dark_theme():
    shell("cmd uimode night yes", check=False)
    time.sleep(2)
    shell("am force-stop " + PACKAGE)
    start_main()
    open_tab("Bookmarks")
    expect("lopen")
    shot("dark_bookmarks")
    tap_text(text="lopen")
    wait_for(text="Bookmark", timeout=60)
    time.sleep(2)
    shot("dark_article_with_note_bar")
    tap(wait_for(contains="to walk, irregular past tense"))
    wait_for(text="New folder")
    shot("dark_edit_dialog")
    tap_text(text="Cancel")
    shell("cmd uimode night no", check=False)


def scroll_down_to(text):
    """Swipes a vertical list up until an element with the given text shows."""
    width, height = [int(value) for value in re.search(r"(\d+)x(\d+)", shell("wm size")).groups()]
    for _ in range(12):
        found = find(dump(), text=text)
        if found:
            return found[0]
        shell("input swipe %d %d %d %d 300" % (width // 2, int(height * 0.7), width // 2, int(height * 0.35)))
        time.sleep(0.8)
    raise AssertionError("no %r after scrolling; on screen: %s" % (text, texts(dump())[:40]))


def find_ignoring_case(root, wanted):
    return [node for node in root.iter("node") if label(node).lower() == wanted.lower()]


def backup_round_trip():
    """Exports the backup, deletes a bookmark with its folder, imports the file again.

    The file is picked in the file picker of the system, hence an optional step."""
    start_main()
    open_tab("Settings")
    tap(scroll_down_to("Export"))
    deadline = time.time() + 30
    while True:
        buttons = find_ignoring_case(dump(), "save")
        if buttons:
            break
        if time.time() > deadline:
            raise AssertionError("no Save button in the file picker: %s" % texts(dump())[:40])
        time.sleep(1)
    shot("backup_export_file_picker")
    tap(buttons[-1])
    time.sleep(4)
    found = shell("find /sdcard/ -name 'oss-dict-backup*.json' 2>/dev/null", check=False).split()
    if not found:
        raise AssertionError("the exported file is nowhere on shared storage")
    backup = adb("exec-out", "cat", found[0], check=False)
    with open(os.path.join(out_dir, "exported-backup.json"), "w", encoding="utf-8") as handle:
        handle.write(backup)
    for expected in ('"folders"', "Verbs2", "Empty one", "to walk, irregular past tense"):
        if expected not in backup:
            raise AssertionError("%s is missing from the exported backup" % expected)

    start_main()
    open_tab("Bookmarks")
    long_press(wait_for(text="lopen"))
    tap_text(text="Delete")
    tap_text(text="Yes")
    wait_gone(text="lopen")
    long_press(find_chip("Verbs2 (0)"))
    tap_text(text="Delete")
    tap_text(text="Delete")
    expect_chips("All (2)", "Empty one (0)", "New folder", "Manage folders")
    shot("bookmarks_before_import")

    open_tab("Settings")
    tap(scroll_down_to("Import"))
    time.sleep(3)
    if not find(dump(), contains="oss-dict-backup"):
        # Not among the recent files: look in the folder it was saved to
        tap_text(text="Show roots")
        tap_text(text="Downloads")
    shot("backup_import_file_picker")
    tap_text(contains="oss-dict-backup")
    time.sleep(4)
    start_main()
    open_tab("Bookmarks")
    expect("lopen", "huis", "fiets", "to walk, irregular past tense")
    expect_chips("All (3)", "Empty one (0)", "Verbs2 (1)", "No folder (2)", "New folder", "Manage folders")
    shot("bookmarks_after_import")


def check_crashes():
    logcat = adb("logcat", "-d", "-v", "threadtime", timeout=120)
    # Kept small: what the app's own processes logged, plus crashes and freezes of anything
    pids = set(re.findall(r"Start proc (\d+):%s/" % re.escape(PACKAGE), logcat))
    keep = []
    for line in logcat.splitlines():
        fields = line.split(None, 3)
        from_app = len(fields) > 2 and fields[2] in pids
        if from_app or PACKAGE in line or "AndroidRuntime" in line or "ANR in" in line:
            keep.append(line)
    with open(os.path.join(out_dir, "logcat.txt"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(keep) + "\n")
    log("   %d log lines of the app kept, processes %s" % (len(keep), sorted(pids)))
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
            ("article toolbar as before, new entry in the menu", article_toolbar_unchanged),
            ("file an article under a new folder with a note", file_article_with_note),
            ("bookmark, then file it from the bar under the article", bookmark_then_bar),
            ("cancelled or empty edit saves nothing", cancelled_edit_saves_nothing),
            ("bookmarks tab lists folders, notes and counts", bookmarks_tab),
            ("pick a folder", filter_by_folder),
            ("file several bookmarks at once", file_several_at_once),
            ("edit one bookmark from the list", edit_single_from_list),
            ("rename and delete folders", manage_folders),
            ("empty folder", empty_folder),
            ("article opened from bookmarks, guarded removal", article_from_bookmarks_and_removal),
            ("history and lookup lists look as before", other_lists_unchanged),
            ("the filter searches notes too", filter_searches_notes),
            ("restart keeps folders, notes and the folder shown", restart_keeps_everything),
            ("dark theme", dark_theme),
        ]
        for name, function in steps:
            if not step(name, function):
                # Get back to a known place so the following steps still tell something
                shell("am force-stop " + PACKAGE, check=False)
                step("recover after: " + name, lambda: (start_main(), open_tab("Bookmarks")))
        step("backup: export, delete, import", backup_round_trip, optional=True)
    step("no crash in the log", check_crashes)

    result = "FAILED: " + "; ".join(failures) if failures else "OK"
    if warnings:
        result += " (optional steps that failed: %s)" % "; ".join(warnings)
    log("RESULT: " + result)
    with open(os.path.join(out_dir, "report.txt"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(report_lines) + "\n")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
