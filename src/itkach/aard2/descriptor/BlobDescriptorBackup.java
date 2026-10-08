package itkach.aard2.descriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Reads and writes the bookmarks/history backup file used to migrate to another device.
 *
 * <p>Deliberately free of any Android dependency: the app's unit tests run on a plain JVM
 * with {@code returnDefaultValues true}, so framework calls (including
 * {@code android.text.TextUtils}) would silently return defaults instead of working.</p>
 *
 * <p>Entries are written with the same field names {@link DescriptorStore} persists, so a
 * descriptor round-trips through a backup without any translation layer. The Jackson tree
 * API is used rather than data binding, mirroring {@code DescriptorStore.save}.</p>
 *
 * <p>Bookmark folders and notes travel inside each bookmark entry. The list of folder names
 * is written as well, so that a folder with nothing in it yet survives the move. All of these
 * are optional when reading: a file written before they existed stays readable.</p>
 */
public final class BlobDescriptorBackup {

    /** Marker identifying our backup files, written as the {@code format} property. */
    public static final String FORMAT = "oss-dict-backup";

    /** Layout version of the file this class writes. Older versions stay readable. */
    public static final int VERSION = 1;

    private static final String PROP_FORMAT = "format";
    private static final String PROP_VERSION = "version";
    private static final String PROP_EXPORTED_AT = "exportedAt";
    private static final String PROP_BOOKMARKS = "bookmarks";
    private static final String PROP_HISTORY = "history";
    private static final String PROP_FOLDERS = "folders";

    /** Parsed backup file content. */
    public static final class Content {
        @NonNull
        public final List<BlobDescriptor> bookmarks;
        @NonNull
        public final List<BlobDescriptor> history;
        /** Names of the bookmark folders, including the ones no bookmark is filed under. */
        @NonNull
        public final List<String> folders;

        public Content(@NonNull List<BlobDescriptor> bookmarks, @NonNull List<BlobDescriptor> history) {
            this(bookmarks, history, new ArrayList<>());
        }

        public Content(@NonNull List<BlobDescriptor> bookmarks, @NonNull List<BlobDescriptor> history,
                       @NonNull List<String> folders) {
            this.bookmarks = bookmarks;
            this.history = history;
            this.folders = folders;
        }
    }

    private BlobDescriptorBackup() {
    }

    /**
     * Writes both lists as a single backup document.
     *
     * @param exportedAt timestamp stored in the file, passed in so callers (and tests) control it
     */
    public static void write(@NonNull OutputStream out, @NonNull ObjectMapper mapper,
                             @NonNull List<BlobDescriptor> bookmarks,
                             @NonNull List<BlobDescriptor> history,
                             long exportedAt) throws IOException {
        write(out, mapper, bookmarks, history, new ArrayList<>(), exportedAt);
    }

    /**
     * Writes both lists and the bookmark folder names as a single backup document.
     *
     * @param folders    every bookmark folder name, so that empty folders are kept too
     * @param exportedAt timestamp stored in the file, passed in so callers (and tests) control it
     */
    public static void write(@NonNull OutputStream out, @NonNull ObjectMapper mapper,
                             @NonNull List<BlobDescriptor> bookmarks,
                             @NonNull List<BlobDescriptor> history,
                             @NonNull List<String> folders,
                             long exportedAt) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put(PROP_FORMAT, FORMAT);
        root.put(PROP_VERSION, VERSION);
        root.put(PROP_EXPORTED_AT, exportedAt);
        root.set(PROP_FOLDERS, toTextArray(mapper, folders));
        root.set(PROP_BOOKMARKS, toArray(mapper, bookmarks));
        root.set(PROP_HISTORY, toArray(mapper, history));
        mapper.writeValue(out, root);
    }

    /**
     * Parses a backup document.
     *
     * <p>Entries without a dictionary id or key are dropped, the same guard
     * {@code BlobDescriptorList.load()} applies to stored descriptors.</p>
     *
     * @throws IOException when the stream is not a readable backup of a known version
     */
    @NonNull
    public static Content read(@NonNull InputStream in, @NonNull ObjectMapper mapper) throws IOException {
        JsonNode root = mapper.readTree(in);
        if (root == null || !root.isObject()) {
            throw new IOException("Not a backup file: expected a JSON object");
        }
        String format = getText(root, PROP_FORMAT);
        if (!FORMAT.equals(format)) {
            throw new IOException("Not a backup file: format is " + format);
        }
        JsonNode versionNode = root.get(PROP_VERSION);
        int version = versionNode == null ? 0 : versionNode.asInt(0);
        if (version < 1 || version > VERSION) {
            throw new IOException("Unsupported backup version: " + version);
        }
        return new Content(toDescriptors(root.get(PROP_BOOKMARKS)), toDescriptors(root.get(PROP_HISTORY)),
                toTextList(root.get(PROP_FOLDERS)));
    }

    /**
     * Picks the entries of {@code imported} that are not already in {@code existing}, leaving
     * {@code existing} untouched (merge semantics: nothing is replaced or removed).
     *
     * <p>Accepted entries get a fresh id when theirs is missing or already taken - ids are
     * file names in {@link DescriptorStore}, so a collision would overwrite an existing entry.
     * That id is written on the imported descriptor itself, which callers are expected to have
     * just parsed from a backup file. Duplicates within {@code imported} are dropped too.</p>
     */
    @NonNull
    public static List<BlobDescriptor> selectNew(@NonNull List<BlobDescriptor> existing,
                                                 @NonNull List<BlobDescriptor> imported) {
        List<BlobDescriptor> accepted = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        for (BlobDescriptor descriptor : existing) {
            if (descriptor != null && descriptor.id != null) {
                usedIds.add(descriptor.id);
            }
        }
        for (BlobDescriptor candidate : imported) {
            if (candidate == null) {
                continue;
            }
            if (contains(existing, candidate) || contains(accepted, candidate)) {
                continue;
            }
            if (candidate.id == null || candidate.id.trim().isEmpty() || usedIds.contains(candidate.id)) {
                candidate.id = UUID.randomUUID().toString();
            }
            usedIds.add(candidate.id);
            accepted.add(candidate);
        }
        return accepted;
    }

    /**
     * Brings the folders and notes of {@code imported} onto the entries of {@code existing}
     * that {@link #selectNew} skips as already present, so that importing a backup does not
     * silently drop them. What is already stored wins, see {@link BookmarkFolders#mergeInto}.
     *
     * @return the entries of {@code existing} that changed and need to be persisted
     */
    @NonNull
    public static List<BlobDescriptor> mergeAnnotations(@NonNull List<BlobDescriptor> existing,
                                                        @NonNull List<BlobDescriptor> imported) {
        List<BlobDescriptor> changed = new ArrayList<>();
        for (BlobDescriptor candidate : imported) {
            if (candidate == null) {
                continue;
            }
            BlobDescriptor stored = find(existing, candidate);
            if (stored != null && BookmarkFolders.mergeInto(stored, candidate) && !changed.contains(stored)) {
                changed.add(stored);
            }
        }
        return changed;
    }

    private static boolean contains(@NonNull List<BlobDescriptor> list, @NonNull BlobDescriptor candidate) {
        return find(list, candidate) != null;
    }

    /**
     * Same rule as {@code BlobDescriptorList.contains}: an exact descriptor match, or the same
     * key in the same dictionary (the descriptor was recreated after the dictionary changed).
     */
    @Nullable
    private static BlobDescriptor find(@NonNull List<BlobDescriptor> list, @NonNull BlobDescriptor candidate) {
        for (BlobDescriptor descriptor : list) {
            if (descriptor == null) {
                continue;
            }
            if (descriptor.equals(candidate)) {
                return descriptor;
            }
            if (Objects.equals(descriptor.key, candidate.key)
                    && Objects.equals(descriptor.slobUri, candidate.slobUri)) {
                return descriptor;
            }
        }
        return null;
    }

    @NonNull
    private static ArrayNode toArray(@NonNull ObjectMapper mapper, @NonNull List<BlobDescriptor> descriptors) {
        ArrayNode array = mapper.createArrayNode();
        for (BlobDescriptor descriptor : descriptors) {
            if (descriptor == null) {
                continue;
            }
            ObjectNode node = mapper.createObjectNode();
            node.put("id", descriptor.id);
            node.put("createdAt", descriptor.createdAt);
            node.put("lastAccess", descriptor.lastAccess);
            node.put("slobId", descriptor.slobId);
            node.put("slobUri", descriptor.slobUri);
            node.put("blobId", descriptor.blobId);
            node.put("key", descriptor.key);
            node.put("fragment", descriptor.fragment);
            node.put("note", descriptor.note);
            node.set("folders", toTextArray(mapper,
                    descriptor.folders == null ? new ArrayList<>() : descriptor.folders));
            array.add(node);
        }
        return array;
    }

    @NonNull
    private static ArrayNode toTextArray(@NonNull ObjectMapper mapper, @NonNull List<String> values) {
        ArrayNode array = mapper.createArrayNode();
        for (String value : values) {
            if (!isEmpty(value)) {
                array.add(value);
            }
        }
        return array;
    }

    @NonNull
    private static List<String> toTextList(@Nullable JsonNode array) {
        List<String> result = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return result;
        }
        for (JsonNode node : array) {
            String value = node == null || node.isNull() ? null : node.asText(null);
            if (!isEmpty(value) && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    @NonNull
    private static List<BlobDescriptor> toDescriptors(@Nullable JsonNode array) {
        List<BlobDescriptor> result = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return result;
        }
        for (JsonNode node : array) {
            if (node == null || !node.isObject()) {
                continue;
            }
            BlobDescriptor descriptor = new BlobDescriptor();
            descriptor.id = getText(node, "id");
            descriptor.createdAt = getLong(node, "createdAt");
            descriptor.lastAccess = getLong(node, "lastAccess");
            descriptor.slobId = getText(node, "slobId");
            descriptor.slobUri = getText(node, "slobUri");
            descriptor.blobId = getText(node, "blobId");
            descriptor.key = getText(node, "key");
            descriptor.fragment = getText(node, "fragment");
            descriptor.note = getText(node, "note");
            descriptor.folders = toTextList(node.get("folders"));
            if (isEmpty(descriptor.slobId) || isEmpty(descriptor.key)) {
                continue;
            }
            result.add(descriptor);
        }
        return result;
    }

    private static boolean isEmpty(@Nullable String value) {
        return value == null || value.isEmpty();
    }

    @Nullable
    private static String getText(@NonNull JsonNode node, @NonNull String property) {
        JsonNode value = node.get(property);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText(null);
    }

    private static long getLong(@NonNull JsonNode node, @NonNull String property) {
        JsonNode value = node.get(property);
        return value == null || value.isNull() ? 0L : value.asLong(0L);
    }
}
