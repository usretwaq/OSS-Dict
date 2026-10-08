package itkach.aard2.prefs;

import androidx.annotation.NonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The bookmark folders the user created.
 *
 * <p>Which folders a bookmark is filed under is stored with the bookmark itself; this list
 * exists so that a folder with no bookmark in it yet is not forgotten.</p>
 */
public class BookmarkFolderPrefs extends Prefs {
    private static final String PREF_NAMES = "names";

    private static BookmarkFolderPrefs instance;

    private static BookmarkFolderPrefs getInstance() {
        if (instance == null) {
            instance = new BookmarkFolderPrefs();
        }
        return instance;
    }

    protected BookmarkFolderPrefs() {
        super("bookmarkFolders");
    }

    /** Returns a copy that the caller is free to modify. */
    @NonNull
    public static Set<String> getNames() {
        // The set handed out by SharedPreferences must not be modified, hence the copy
        return new HashSet<>(getInstance().prefs.getStringSet(PREF_NAMES, Collections.emptySet()));
    }

    public static void add(@NonNull String name) {
        addAll(Collections.singleton(name));
    }

    public static void addAll(@NonNull Collection<String> names) {
        Set<String> stored = getNames();
        if (stored.addAll(names)) {
            setNames(stored);
        }
    }

    public static void remove(@NonNull String name) {
        Set<String> stored = getNames();
        if (stored.remove(name)) {
            setNames(stored);
        }
    }

    public static void rename(@NonNull String oldName, @NonNull String newName) {
        Set<String> stored = getNames();
        stored.remove(oldName);
        stored.add(newName);
        setNames(stored);
    }

    private static void setNames(@NonNull Set<String> names) {
        getInstance().prefs.edit().putStringSet(PREF_NAMES, names).apply();
    }
}
