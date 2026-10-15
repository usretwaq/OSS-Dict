## Summary

- A bookmark can be filed under any number of folders and carry a note. Both are stored with the bookmark and go into the backup file; older backup files still import.
- Article view: "Folders & note" in the toolbar opens a dialog with the note and a box per folder, where a folder can also be created. A bookmarked article shows its note and folders in a bar at the bottom, which opens the same dialog.
- Bookmarks tab: a row of folder chips with counts narrows the list. A button at the end of the row lists the folders top to bottom, starting with a search once there are 8 folders. A long press on a folder renames or deletes it; deleting keeps its bookmarks. Several selected bookmarks can be filed at once, and the filter also searches notes.
- Fix in the first commit: the article pages opened from the bookmarks or history list now work on a copy of the list. Removing the bookmark being read (and now, moving it out of the folder shown) shifted titles onto the wrong pages.

## Screenshots

| Article | Folders & note | Bookmarks |
|:-:|:-:|:-:|
| <img src="https://raw.githubusercontent.com/usretwaq/OSS-Dict/pr-126-media/article-note-bar.png" width="250"> | <img src="https://raw.githubusercontent.com/usretwaq/OSS-Dict/pr-126-media/folders-and-note-dialog.png" width="250"> | <img src="https://raw.githubusercontent.com/usretwaq/OSS-Dict/pr-126-media/bookmarks-all.png" width="250"> |
| **Folder list** | **Folder search** | **Dark theme** |
| <img src="https://raw.githubusercontent.com/usretwaq/OSS-Dict/pr-126-media/folder-list.png" width="250"> | <img src="https://raw.githubusercontent.com/usretwaq/OSS-Dict/pr-126-media/folder-search.png" width="250"> | <img src="https://raw.githubusercontent.com/usretwaq/OSS-Dict/pr-126-media/dark-bookmarks.png" width="250"> |

Videos from the emulator, played at double speed (full speed: [article](https://raw.githubusercontent.com/usretwaq/OSS-Dict/pr-126-media/1-article.mp4), [bookmarks](https://raw.githubusercontent.com/usretwaq/OSS-Dict/pr-126-media/2-bookmarks.mp4), [search](https://raw.githubusercontent.com/usretwaq/OSS-Dict/pr-126-media/3-search.mp4)):

| From an article | In the Bookmarks tab | Searching folders |
|:-:|:-:|:-:|
| <img src="https://raw.githubusercontent.com/usretwaq/OSS-Dict/pr-126-media/1-article.gif" width="250"> | <img src="https://raw.githubusercontent.com/usretwaq/OSS-Dict/pr-126-media/2-bookmarks.gif" width="250"> | <img src="https://raw.githubusercontent.com/usretwaq/OSS-Dict/pr-126-media/3-search.gif" width="250"> |

## Testing

- Every commit compiles (`assembleDebug`) and passes `./gradlew :testDebugUnitTest --tests 'itkach.aard2.descriptor.*'`: 57 tests, 32 of them new (folder logic, storage round trip including the legacy recovery path, backup round trip and merge on import). The root `testDebugUnitTest` fails on master in a clean checkout because the tests of the slobj submodule don't compile, hence the leading `:`.
- `lint` reports nothing new apart from MissingTranslation for the new strings.
- On an Android 16 emulator, the minified release build installed over the debug build and walked through with adb: filing from an article and from the list, chips, folder list and search, rename and delete, restart, dark theme, backup export, delete and import. No crash in logcat. An earlier build of the feature also runs fine on my Pixel 9.
- Written with Claude, as mentioned in #126.

On the size: about 1,250 lines of Java and 360 of layouts and strings, plus 440 of tests. The folder list button and the folder search are separate commits and can be dropped. If you would rather change less for people who don't use folders, the folder row could stay hidden until a first folder exists, and the bar under articles could show only for bookmarks that have a note or folders.

Closes #126
