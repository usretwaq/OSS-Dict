#!/usr/bin/env python3
"""Records short screen videos and takes screenshots of bookmark folders and notes.

For the description of the pull request: unlike smoke.py it checks little, it shows the
feature with plausible data, at a pace a person can follow.

Usage: tour.py <dir with the built APKs> <output dir>
"""
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import smoke  # noqa: E402
from smoke import (adb, expect, lookup, log, long_press, open_tab, shell, start_main, tap,  # noqa: E402
                   tap_chip, tap_text, type_text, wait_for)

media_dir = "media"


def pause(seconds=1.2):
    time.sleep(seconds)


def picture(name):
    with open(os.path.join(media_dir, name + ".png"), "wb") as handle:
        handle.write(adb("exec-out", "screencap", "-p", binary=True))
    log("  picture " + name)


class Recording:
    """A screen recording on the device, pulled into the media folder when stopped."""

    def __init__(self, name):
        self.name = name
        self.remote = "/sdcard/%s.mp4" % name
        shell("rm -f " + self.remote, check=False)
        self.process = subprocess.Popen(
            ["adb", "shell", "screenrecord", "--size", "720x1600", "--bit-rate", "6000000",
             self.remote], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        pause(2)

    def stop(self):
        pause(1.5)
        shell("pkill -INT screenrecord", check=False)
        try:
            output = self.process.communicate(timeout=30)[0].decode("utf-8", "replace")
        except subprocess.TimeoutExpired:
            self.process.kill()
            output = "(timed out)"
        pause(2)
        local = os.path.join(media_dir, self.name + ".mp4")
        adb("pull", self.remote, local, check=False)
        size = os.path.getsize(local) if os.path.exists(local) else 0
        log("  recording %s: %d bytes %s" % (self.name, size, output.strip()[-300:]))


def type_slowly(text):
    for character in text:
        type_text(character)


def file_bookmark(word, folder=None, new_folder=False, note=None):
    """Bookmarks an article and files it, off camera."""
    lookup(word)
    tap_text(text="Bookmark")
    wait_for(contains="Add a note or folders")
    if folder is None and note is None:
        return
    tap_text(contains="Add a note or folders")
    wait_for(text="New folder")
    if folder is not None:
        if new_folder:
            tap_text(text="New folder")
            wait_for(cls="EditText")
            type_text(folder)
            tap_text(text="Save")
            wait_for(text=folder, cls="CheckBox")
        else:
            tap(wait_for(text=folder, cls="CheckBox"))
    if note is not None:
        tap(wait_for(cls="EditText"))
        type_text(note)
    tap_text(text="Save")
    wait_for(contains="Folders: " if folder is not None else note)


def new_folder_from_list(name):
    tap_text(text="Folder list")
    wait_for(cls="ListView")
    tap_text(text="New folder")
    wait_for(cls="EditText")
    type_text(name)
    tap_text(text="Save")
    wait_for(text="Folder list")
    pause(0.8)


def article_clip():
    lookup("gezellig")
    recording = Recording("1-article")
    tap_text(text="Bookmark")
    wait_for(contains="Add a note or folders")
    pause(1.5)
    tap_text(contains="Add a note or folders")
    wait_for(text="New folder")
    pause()
    tap_text(text="New folder")
    wait_for(cls="EditText")
    pause(0.6)
    type_text("Untranslatable")
    pause(0.6)
    tap_text(text="Save")
    wait_for(text="Untranslatable", cls="CheckBox")
    pause()
    tap(wait_for(cls="EditText"))
    type_text("cosy, sociable, pleasant")
    pause()
    picture("folders-and-note-dialog")
    tap_text(text="Save")
    expect("cosy, sociable", "Folders: Untranslatable")
    pause(2.5)
    picture("article-note-bar")
    recording.stop()


def bookmarks_clip():
    file_bookmark("lopen", "Verbs", new_folder=True, note="to walk, liep, gelopen")
    file_bookmark("huis", "Nouns", new_folder=True, note="house, home")
    file_bookmark("fiets", "Nouns")
    file_bookmark("uitwaaien", "Untranslatable", note="to clear your head in the wind")
    file_bookmark("zuinig")
    start_main()
    open_tab("Bookmarks")
    wait_for(text="All (6)")
    pause()
    recording = Recording("2-bookmarks")
    picture("bookmarks-all")
    tap_chip("Nouns (2)")
    expect("huis", "fiets")
    pause(1.8)
    picture("bookmarks-one-folder")
    tap_text(text="Folder list")
    wait_for(cls="ListView")
    pause(1.8)
    picture("folder-list")
    tap_text(text="Untranslatable (2)")
    expect("gezellig", "uitwaaien")
    pause(1.8)
    tap_text(text="Folder list")
    wait_for(cls="ListView")
    pause()
    tap_text(text="All (6)")
    wait_for(text="zuinig")
    pause()
    long_press(wait_for(text="zuinig"))
    pause(0.8)
    tap_text(text="lopen")
    pause(0.8)
    tap_text(text="Folders & note")
    wait_for(text="New folder")
    pause()
    tap_text(text="New folder")
    wait_for(cls="EditText")
    type_text("Exam")
    tap_text(text="Save")
    wait_for(text="Exam", cls="CheckBox")
    pause()
    picture("folders-for-two-bookmarks")
    tap_text(text="Save")
    wait_for(text="Exam (2)")
    pause(2.5)
    picture("bookmarks-after-filing-two")
    recording.stop()


def search_clip():
    for name in ["Week 1", "Week 2", "Week 3", "Week 4"]:
        new_folder_from_list(name)
    recording = Recording("3-search")
    tap_text(text="Folder list")
    wait_for(cls="ListView")
    pause(1.8)
    picture("folder-list-with-search")
    tap_text(text="Search folders")
    wait_for(cls="EditText")
    pause()
    type_slowly("wee")
    pause(0.8)
    type_text(" 3")
    wait_for(text="Week 3 (0)")
    pause(1.5)
    picture("folder-search")
    tap_text(text="Week 3 (0)")
    expect("No bookmarks in this folder")
    pause(2.5)
    recording.stop()


def dark_pictures():
    shell("cmd uimode night yes", check=False)
    pause(2)
    shell("am force-stop " + smoke.PACKAGE)
    start_main()
    open_tab("Bookmarks")
    tap_chip("All (6)")
    wait_for(text="zuinig")
    pause()
    picture("dark-bookmarks")
    lookup("lopen")
    expect("Folders: Verbs, Exam")
    pause()
    picture("dark-article-note-bar")
    shell("cmd uimode night no", check=False)


def main():
    global media_dir
    apk_dir, out = sys.argv[1], sys.argv[2]
    media_dir = os.path.join(out, "media")
    smoke.out_dir = os.path.join(out, "steps")
    os.makedirs(media_dir, exist_ok=True)
    os.makedirs(smoke.out_dir, exist_ok=True)
    debug_apk, release_apk = smoke.find_apks(apk_dir)
    shell("settings put system show_touches 1", check=False)
    for name, function in [("install", lambda: (smoke.setup(debug_apk, release_apk),
                                                 smoke.update_to_release(release_apk))),
                           ("article", article_clip), ("bookmarks", bookmarks_clip),
                           ("search", search_clip), ("dark", dark_pictures)]:
        if not smoke.step(name, function):
            break
    with open(os.path.join(out, "report.txt"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(smoke.report_lines) + "\n")
    sys.exit(1 if smoke.failures else 0)


if __name__ == "__main__":
    main()
