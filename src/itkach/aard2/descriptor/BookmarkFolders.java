package itkach.aard2.descriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Folder membership and notes of bookmarks.
 *
 * <p>A bookmark can be filed under any number of folders; a folder is nothing more than a
 * name shared by the bookmarks filed under it. Deliberately free of any Android
 * dependency so it can be unit tested on a plain JVM, like {@link BlobDescriptorBackup}.</p>
 *
 * <p>Every change assigns a new list to {@link BlobDescriptor#folders} rather than
 * modifying the existing one, so a list handed out earlier (to a backup being written on
 * another thread) never changes underneath its reader.</p>
 */
public final class BookmarkFolders {

    /** Longest folder name accepted from the user. */
    public static final int MAX_NAME_LENGTH = 40;

    /** Accents and the other marks that combine with the letter before them. */
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private BookmarkFolders() {
    }

    /**
     * Cleans up a folder name typed by the user: surrounding whitespace is dropped and line
     * breaks become spaces.
     *
     * @return the usable name, or null when nothing is left
     */
    @Nullable
    public static String normalizeName(@Nullable String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() > MAX_NAME_LENGTH) {
            int end = MAX_NAME_LENGTH;
            // Never cut between the two halves of a character outside the basic plane
            if (Character.isHighSurrogate(normalized.charAt(end - 1))) {
                end--;
            }
            normalized = normalized.substring(0, end).trim();
        }
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * Looks a name up ignoring case, so that "Verbs" and "verbs" cannot both be created.
     *
     * @return the name as it is spelled in {@code names}, or null when it is not there
     */
    @Nullable
    public static String findIgnoreCase(@NonNull Collection<String> names, @NonNull String name) {
        for (String candidate : names) {
            if (candidate != null && candidate.equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Picks the folder names that contain what the user typed, in the order given. Case and
     * accents make no difference, and words are looked for one by one, so that "ch 12" finds
     * "Chapter 12". Nothing typed keeps every name.
     */
    @NonNull
    public static List<String> search(@NonNull Collection<String> names, @Nullable String text) {
        String[] words = fold(text == null ? "" : text).trim().split("\\s+");
        List<String> found = new ArrayList<>();
        for (String name : names) {
            if (name == null) {
                continue;
            }
            String folded = fold(name);
            boolean hasEveryWord = true;
            for (String word : words) {
                if (!folded.contains(word)) {
                    hasEveryWord = false;
                    break;
                }
            }
            if (hasEveryWord) {
                found.add(name);
            }
        }
        return found;
    }

    /** Lower case and without accents: the form two texts are compared in when searching. */
    @NonNull
    private static String fold(@NonNull String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        return MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** True when the bookmark is filed under the given folder. */
    public static boolean isIn(@NonNull BlobDescriptor descriptor, @NonNull String folder) {
        return descriptor.folders != null && descriptor.folders.contains(folder);
    }

    /** True when the bookmark is filed under no folder at all. */
    public static boolean isUnfiled(@NonNull BlobDescriptor descriptor) {
        return descriptor.folders == null || descriptor.folders.isEmpty();
    }

    /** Folder names in use by the given bookmarks, each once, in the order first met. */
    @NonNull
    public static Set<String> collect(@NonNull Collection<BlobDescriptor> descriptors) {
        Set<String> names = new LinkedHashSet<>();
        for (BlobDescriptor descriptor : descriptors) {
            if (descriptor == null || descriptor.folders == null) {
                continue;
            }
            for (String folder : descriptor.folders) {
                if (folder != null && !folder.isEmpty()) {
                    names.add(folder);
                }
            }
        }
        return names;
    }

    /** How many of the given bookmarks are filed under each folder. */
    @NonNull
    public static Map<String, Integer> count(@NonNull Collection<BlobDescriptor> descriptors) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BlobDescriptor descriptor : descriptors) {
            if (descriptor == null || descriptor.folders == null) {
                continue;
            }
            // A set, so a name stored twice on one bookmark is still counted once
            for (String folder : new LinkedHashSet<>(descriptor.folders)) {
                if (folder == null || folder.isEmpty()) {
                    continue;
                }
                Integer current = counts.get(folder);
                counts.put(folder, current == null ? 1 : current + 1);
            }
        }
        return counts;
    }

    /** How many of the given bookmarks are filed under no folder. */
    public static int countUnfiled(@NonNull Collection<BlobDescriptor> descriptors) {
        int count = 0;
        for (BlobDescriptor descriptor : descriptors) {
            if (descriptor != null && isUnfiled(descriptor)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Files a bookmark under the folders in {@code add} and takes it out of those in
     * {@code remove}. Folders named in neither are left as they are.
     *
     * @return true when the bookmark changed
     */
    public static boolean update(@NonNull BlobDescriptor descriptor,
                                 @NonNull Collection<String> add,
                                 @NonNull Collection<String> remove) {
        List<String> current = descriptor.folders == null
                ? new ArrayList<>() : descriptor.folders;
        // A set drops duplicates while keeping the order the folders were assigned in
        Set<String> updated = new LinkedHashSet<>(current);
        updated.removeAll(remove);
        for (String folder : add) {
            if (folder != null && !folder.isEmpty()) {
                updated.add(folder);
            }
        }
        List<String> result = new ArrayList<>(updated);
        if (descriptor.folders != null && result.equals(descriptor.folders)) {
            return false;
        }
        descriptor.folders = result;
        return true;
    }

    /**
     * Gives a folder a new name on every bookmark filed under it. A bookmark already filed
     * under the new name keeps a single entry.
     *
     * @return the bookmarks that changed
     */
    @NonNull
    public static List<BlobDescriptor> rename(@NonNull Collection<BlobDescriptor> descriptors,
                                              @NonNull String oldName, @NonNull String newName) {
        List<BlobDescriptor> changed = new ArrayList<>();
        if (oldName.equals(newName)) {
            return changed;
        }
        for (BlobDescriptor descriptor : descriptors) {
            if (descriptor == null || !isIn(descriptor, oldName)) {
                continue;
            }
            Set<String> updated = new LinkedHashSet<>();
            for (String folder : descriptor.folders) {
                updated.add(oldName.equals(folder) ? newName : folder);
            }
            descriptor.folders = new ArrayList<>(updated);
            changed.add(descriptor);
        }
        return changed;
    }

    /**
     * Takes every bookmark out of a folder. The bookmarks themselves are kept.
     *
     * @return the bookmarks that changed
     */
    @NonNull
    public static List<BlobDescriptor> remove(@NonNull Collection<BlobDescriptor> descriptors,
                                              @NonNull String name) {
        List<BlobDescriptor> changed = new ArrayList<>();
        for (BlobDescriptor descriptor : descriptors) {
            if (descriptor == null || !isIn(descriptor, name)) {
                continue;
            }
            List<String> updated = new ArrayList<>(descriptor.folders);
            // removeAll rather than remove: drops the name even if it was stored twice
            updated.removeAll(Collections.singleton(name));
            descriptor.folders = updated;
            changed.add(descriptor);
        }
        return changed;
    }

    /**
     * Stores the user's note on a bookmark. Blank text clears it.
     *
     * @return true when the bookmark changed
     */
    public static boolean setNote(@NonNull BlobDescriptor descriptor, @Nullable String note) {
        String normalized = note == null ? null : note.trim();
        if (normalized != null && normalized.isEmpty()) {
            normalized = null;
        }
        if (normalized == null ? descriptor.note == null : normalized.equals(descriptor.note)) {
            return false;
        }
        descriptor.note = normalized;
        return true;
    }

    /** True when the bookmark carries something the user would lose with it. */
    public static boolean hasAnnotations(@NonNull BlobDescriptor descriptor) {
        return !isUnfiled(descriptor) || (descriptor.note != null && !descriptor.note.isEmpty());
    }

    /**
     * Brings the folders and note of an imported bookmark onto the one already stored for
     * the same article. What is stored wins: folders are added, never taken away, and the
     * imported note is only used when the stored bookmark has none.
     *
     * @return true when the stored bookmark changed
     */
    public static boolean mergeInto(@NonNull BlobDescriptor stored, @NonNull BlobDescriptor imported) {
        boolean changed = false;
        if (imported.folders != null && !imported.folders.isEmpty()) {
            changed = update(stored, imported.folders, new ArrayList<>());
        }
        boolean storedHasNote = stored.note != null && !stored.note.isEmpty();
        if (!storedHasNote && imported.note != null && !imported.note.trim().isEmpty()) {
            changed = setNote(stored, imported.note) || changed;
        }
        return changed;
    }
}
