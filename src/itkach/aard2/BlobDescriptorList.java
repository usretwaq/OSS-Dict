package itkach.aard2;

import android.database.DataSetObservable;
import android.database.DataSetObserver;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.icu.text.Collator;
import android.icu.text.RuleBasedCollator;
import android.icu.text.StringSearch;

import java.text.StringCharacterIterator;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import itkach.aard2.descriptor.BlobDescriptor;
import itkach.aard2.descriptor.BlobDescriptorBackup;
import itkach.aard2.descriptor.BookmarkFolders;
import itkach.aard2.descriptor.DescriptorStore;
import itkach.aard2.dictionary.Dictionary;
import itkach.aard2.dictionary.DictionaryEntry;
import itkach.aard2.dictionary.SlobDictionary;
import itkach.aard2.utils.ThreadUtils;
import itkach.aard2.utils.Utils;
import itkach.slob.Slob;

public class BlobDescriptorList extends AbstractList<BlobDescriptor> {
    private static final String TAG = BlobDescriptorList.class.getSimpleName();

    enum SortOrder {
        TIME, NAME
    }

    /**
     * Value for {@link #setFolderFilter(String)} that shows the entries filed under no
     * folder. Cannot clash with a real folder: folder names are never empty.
     */
    public static final String FOLDER_UNFILED = "";

    protected final DescriptorStore<BlobDescriptor> store;
    protected final List<BlobDescriptor> list;
    private final List<BlobDescriptor> filteredList;
    private final DataSetObservable dataSetObservable;
    private final Comparator<BlobDescriptor> nameComparatorAsc;
    private final Comparator<BlobDescriptor> nameComparatorDesc;
    private final Comparator<BlobDescriptor> timeComparatorAsc;
    private final Comparator<BlobDescriptor> timeComparatorDesc;
    protected final Comparator<BlobDescriptor> lastAccessComparator;
    private final Slob.KeyComparator keyComparator;
    protected final int maxSize;
    private final RuleBasedCollator  filterCollator;

    private String filter;
    /** Folder whose entries are shown, null to show the entries of every folder. */
    @Nullable
    private volatile String folderFilter;
    /**
     * Raised once {@link #load()} has filled the list. Loading runs on a background thread
     * while the screens are already being built, so until then the main thread must not
     * walk the list: the folder accessors report nothing and no filter is applied early.
     */
    private volatile boolean loadFinished;
    private SortOrder order;
    private boolean ascending;
    private Comparator<BlobDescriptor> comparator;

    BlobDescriptorList(DescriptorStore<BlobDescriptor> store) {
        this(store, 100);
    }

    BlobDescriptorList(DescriptorStore<BlobDescriptor> store, int maxSize) {
        this.store = store;
        this.maxSize = maxSize;
        this.list = new ArrayList<>();
        this.filteredList = new ArrayList<>();
        this.dataSetObservable = new DataSetObservable();
        this.filter = "";
        keyComparator = Slob.Strength.QUATERNARY.comparator;

        nameComparatorAsc = (b1, b2) -> keyComparator.compare(b1.key, b2.key);

        nameComparatorDesc = Collections.reverseOrder(nameComparatorAsc);

        timeComparatorAsc = (b1, b2) -> Long.compare(b1.createdAt, b2.createdAt);

        timeComparatorDesc = Collections.reverseOrder(timeComparatorAsc);

        lastAccessComparator = (b1, b2) -> Long.compare(b2.lastAccess, b1.lastAccess);

        order = SortOrder.TIME;
        ascending = false;
        setSort(order, ascending);

        try {
            filterCollator = (RuleBasedCollator) Collator.getInstance(Locale.ROOT).clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
        filterCollator.setStrength(Collator.PRIMARY);
        filterCollator.setAlternateHandlingShifted(false);
    }

    public void registerDataSetObserver(DataSetObserver observer) {
        this.dataSetObservable.registerObserver(observer);
    }

    public void unregisterDataSetObserver(DataSetObserver observer) {
        this.dataSetObservable.unregisterObserver(observer);
    }

    /**
     * Notifies the attached observers that the underlying data has been changed
     * and any View reflecting the data set should refresh itself.
     */
    @MainThread
    public synchronized void notifyDataSetChanged() {
        // Synchronized, like sortOrderChanged(): load() ends with a call from its background
        // thread while a screen being built can ask for a sort or a folder at the same time
        this.filteredList.clear();
        boolean hasTextFilter = !TextUtils.isEmpty(filter);
        for (BlobDescriptor bd : this.list) {
            if (!isInShownFolder(bd)) {
                continue;
            }
            if (!hasTextFilter) {
                this.filteredList.add(bd);
            } else if (bd != null && (matchesFilter(bd.key) || matchesFilter(bd.note))) {
                // The text filter looks at the title and at the user's note
                this.filteredList.add(bd);
            }
        }
        sortOrderChanged();
    }

    private boolean isInShownFolder(@Nullable BlobDescriptor bd) {
        if (folderFilter == null) {
            return true;
        }
        if (bd == null) {
            return false;
        }
        if (FOLDER_UNFILED.equals(folderFilter)) {
            return BookmarkFolders.isUnfiled(bd);
        }
        return BookmarkFolders.isIn(bd, folderFilter);
    }

    private boolean matchesFilter(@Nullable String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        StringSearch stringSearch = new StringSearch(
                filter, new StringCharacterIterator(text), filterCollator);
        return stringSearch.first() != StringSearch.DONE;
    }

    private synchronized void sortOrderChanged() {
        Utils.sort(this.filteredList, comparator);
        this.dataSetObservable.notifyChanged();
    }

    /**
     * Notifies the attached observers that the underlying data is no longer
     * valid or available. Once invoked this adapter is no longer valid and
     * should not report further data set changes.
     */
    @MainThread
    public void notifyDataSetInvalidated() {
        this.dataSetObservable.notifyInvalidated();
    }

    void load() {
        List<BlobDescriptor> loaded = this.store.load(BlobDescriptor.class);
        for (BlobDescriptor bd : loaded) {
            if (bd == null || TextUtils.isEmpty(bd.slobId) || TextUtils.isEmpty(bd.key)) {
                continue;
            }
            if (bd.folders == null) {
                // Stored as an explicit null: the rest of the app relies on a list
                bd.folders = new ArrayList<>();
            }
            this.list.add(bd);
        }
        loadFinished = true;
        notifyDataSetChanged();
    }

    private void doUpdateLastAccess(BlobDescriptor bd) {
        long t = System.currentTimeMillis();
        long dt = t - bd.lastAccess;
        if (dt < 2000) {
            return;
        }
        bd.lastAccess = t;
        store.save(bd);
    }

    void updateLastAccess(final BlobDescriptor bd) {
        ThreadUtils.postOnMainThread(() -> doUpdateLastAccess(bd));
    }

    @Nullable
    Dictionary resolveOwner(BlobDescriptor bd) {
        // First try by ID
        Dictionary dict = SlobHelper.getInstance().getDictionary(bd.slobId);
        if (dict == null) {
            dict = SlobHelper.getInstance().findDictionary(bd.slobUri);
        }
        return dict;
    }

    @Nullable
    public DictionaryEntry resolve(BlobDescriptor bd) {
        Dictionary dict = resolveOwner(bd);
        if (dict == null) {
            return null;
        }
        DictionaryEntry entry;
        String dictId = dict.getId();
        if (dictId.equals(bd.slobId)) {
            entry = new DictionaryEntry(dict, bd.blobId, bd.key, bd.fragment);
        } else {
            // Dictionary was replaced – re-find by key (use SECONDARY_PREFIX for case-insensitive and prefix)
            try {
                Iterator<DictionaryEntry> result = dict.find(bd.key, Slob.Strength.SECONDARY_PREFIX);
                if (result.hasNext()) {
                    entry = result.next();
                    bd.slobId = dictId;
                    bd.blobId = entry.id;
                } else {
                    entry = null;
                }
            } catch (Exception ex) {
                Log.w(TAG, String.format("Failed to resolve descriptor %s (%s) in %s",
                        bd.blobId, bd.key, dictId), ex);
                entry = null;
            }
        }
        if (entry != null) {
            updateLastAccess(bd);
        }
        return entry;
    }

    /** @deprecated Use {@link #resolveOwner(BlobDescriptor)} instead. */
    @Deprecated
    @Nullable
    Slob resolveSlobOwner(BlobDescriptor bd) {
        return SlobHelper.getInstance().getSlob(bd.slobId);
    }

    protected BlobDescriptor createDescriptor(Uri contentUri) {
        Log.d(TAG, "Create descriptor from content url: " + contentUri);
        BlobDescriptor bd = BlobDescriptor.fromUri(contentUri);
        if (bd != null) {
            String dictUri = SlobHelper.getInstance().getDictionaryUri(bd.slobId);
            if (dictUri == null) {
                // Fallback for legacy Slob descriptors
                dictUri = SlobHelper.getInstance().getSlobUri(bd.slobId);
            }
            Log.d(TAG, "Found dict uri for: " + bd.slobId + " " + dictUri);
            bd.slobUri = dictUri;
        }
        return bd;
    }

    public BlobDescriptor add(Uri contentUrl) {
        BlobDescriptor bd = createDescriptor(contentUrl);
        if (bd == null) {
            return null;
        }
        int index = this.list.indexOf(bd);
        if (index > -1) {
            return this.list.get(index);
        }
        this.list.add(bd);
        store.save(bd);
        trimToMaxSize();
        notifyDataSetChanged();
        return bd;
    }

    /**
     * Drops least recently accessed entries until the list fits {@link #maxSize}.
     *
     * <p>Loops because an import adds many entries at once, unlike {@link #add(Uri)}.</p>
     */
    protected void trimToMaxSize() {
        if (this.list.size() <= this.maxSize) {
            return;
        }
        Utils.sort(this.list, lastAccessComparator);
        while (this.list.size() > this.maxSize) {
            BlobDescriptor lru = this.list.remove(this.list.size() - 1);
            store.delete(lru.id);
        }
    }

    /**
     * Merges descriptors read from a backup file into this list.
     *
     * <p>Nothing is replaced or removed: entries already present are skipped
     * (see {@link BlobDescriptorBackup#selectNew}).</p>
     *
     * @return the number of entries actually added
     */
    @MainThread
    public int importDescriptors(@NonNull List<BlobDescriptor> imported) {
        // Entries already present are not replaced, but they gain the folders and note of
        // the backup, which would otherwise be dropped without a word.
        for (BlobDescriptor annotated : BlobDescriptorBackup.mergeAnnotations(this.list, imported)) {
            store.save(annotated);
        }
        List<BlobDescriptor> added = BlobDescriptorBackup.selectNew(this.list, imported);
        for (BlobDescriptor bd : added) {
            this.list.add(bd);
            store.save(bd);
        }
        trimToMaxSize();
        notifyDataSetChanged();
        return added.size();
    }

    public BlobDescriptor remove(Uri contentUrl) {
        int index = this.list.indexOf(createDescriptor(contentUrl));
        if (index > -1) {
            return removeByIndex(index);
        }
        return null;
    }

    public BlobDescriptor remove(int index) {
        //FIXME find exact item by uuid or using sorted<->unsorted mapping
        BlobDescriptor bd = this.filteredList.get(index);
        int realIndex = this.list.indexOf(bd);
        if (realIndex > -1) {
            return removeByIndex(realIndex);
        }
        return null;
    }

    private BlobDescriptor removeByIndex(int index) {
        BlobDescriptor bd = this.list.remove(index);
        if (bd != null) {
            boolean removed = store.delete(bd.id);
            Log.d(TAG, String.format("Item (%s) %s removed? %s", bd.key, bd.id, removed));
            if (removed) {
                notifyDataSetChanged();
            }
        }
        return bd;
    }

    public boolean contains(Uri contentUrl) {
        BlobDescriptor toFind = createDescriptor(contentUrl);
        if (toFind == null) {
            return false;
        }
        for (BlobDescriptor bd : this.list) {
            if (bd.equals(toFind)) {
                Log.d(TAG, "Found exact match, bookmarked");
                return true;
            }
            if (TextUtils.equals(bd.key, toFind.key) && TextUtils.equals(bd.slobUri, toFind.slobUri)) {
                Log.d(TAG, "Found approximate match, bookmarked");
                return true;
            }
        }
        Log.d(TAG, "not bookmarked");
        return false;
    }

    /**
     * Returns the stored entry for an article, null when there is none. Same matching rule
     * as {@link #contains(Uri)}.
     */
    @Nullable
    public BlobDescriptor find(Uri contentUrl) {
        BlobDescriptor toFind = createDescriptor(contentUrl);
        if (toFind == null) {
            return null;
        }
        for (BlobDescriptor bd : this.list) {
            if (bd.equals(toFind)) {
                return bd;
            }
            if (TextUtils.equals(bd.key, toFind.key) && TextUtils.equals(bd.slobUri, toFind.slobUri)) {
                return bd;
            }
        }
        return null;
    }

    /**
     * Applies what the user picked in the folders and note dialog to the given entries:
     * files them under {@code addFolders}, takes them out of {@code removeFolders} and,
     * when {@code setNote} is true, stores {@code note} (blank clears it).
     */
    @MainThread
    public void annotate(@NonNull Collection<BlobDescriptor> targets,
                         @NonNull Collection<String> addFolders,
                         @NonNull Collection<String> removeFolders,
                         boolean setNote, @Nullable String note) {
        boolean changed = false;
        for (BlobDescriptor bd : targets) {
            boolean foldersChanged = BookmarkFolders.update(bd, addFolders, removeFolders);
            boolean noteChanged = setNote && BookmarkFolders.setNote(bd, note);
            if (foldersChanged || noteChanged) {
                store.save(bd);
                changed = true;
            }
        }
        if (changed) {
            notifyDataSetChanged();
        }
    }

    /** Renames a folder on every entry filed under it. */
    @MainThread
    public void renameFolder(@NonNull String oldName, @NonNull String newName) {
        for (BlobDescriptor bd : BookmarkFolders.rename(this.list, oldName, newName)) {
            store.save(bd);
        }
        if (oldName.equals(folderFilter)) {
            folderFilter = newName;
        }
        notifyDataSetChanged();
    }

    /** Takes every entry out of a folder. The entries themselves are kept. */
    @MainThread
    public void deleteFolder(@NonNull String name) {
        for (BlobDescriptor bd : BookmarkFolders.remove(this.list, name)) {
            store.save(bd);
        }
        if (name.equals(folderFilter)) {
            folderFilter = null;
        }
        notifyDataSetChanged();
    }

    /** Folder names in use by the entries, whatever filter is currently applied. */
    @NonNull
    public Set<String> getFolderNames() {
        return BookmarkFolders.collect(loadedEntries());
    }

    /** Number of entries filed under each folder, whatever filter is currently applied. */
    @NonNull
    public Map<String, Integer> getFolderCounts() {
        return BookmarkFolders.count(loadedEntries());
    }

    /** Number of entries filed under no folder, whatever filter is currently applied. */
    public int getUnfiledCount() {
        return BookmarkFolders.countUnfiled(loadedEntries());
    }

    /** Number of entries, whatever filter is currently applied. */
    public int getTotalCount() {
        return loadedEntries().size();
    }

    /** All the entries, or none while {@link #load()} is still filling the list. */
    @NonNull
    private List<BlobDescriptor> loadedEntries() {
        return loadFinished ? this.list : Collections.<BlobDescriptor>emptyList();
    }

    /**
     * Shows only the entries filed under the given folder.
     *
     * @param folder a folder name, {@link #FOLDER_UNFILED} for the entries in no folder,
     *               or null to show everything
     */
    @MainThread
    public void setFolderFilter(@Nullable String folder) {
        if (TextUtils.equals(this.folderFilter, folder)) {
            return;
        }
        this.folderFilter = folder;
        if (loadFinished) {
            notifyDataSetChanged();
        }
        // Otherwise load() applies it: it notifies once the list is filled
    }

    @Nullable
    public String getFolderFilter() {
        return this.folderFilter;
    }

    public void setFilter(String filter) {
        this.filter = filter;
        notifyDataSetChanged();
    }

    public String getFilter() {
        return this.filter;
    }

    @Override
    public BlobDescriptor get(int location) {
        return this.filteredList.get(location);
    }

    public List<BlobDescriptor> getList() {
        return list;
    }

    @Override
    public int size() {
        return this.filteredList.size();
    }

    public void setSort(boolean ascending) {
        setSort(this.order, ascending);
    }

    public void setSort(SortOrder order) {
        setSort(order, this.ascending);
    }

    public SortOrder getSortOrder() {
        return this.order;
    }

    public boolean isAscending() {
        return this.ascending;
    }

    public void setSort(SortOrder order, boolean ascending) {
        this.order = order;
        this.ascending = ascending;
        Comparator<BlobDescriptor> c = null;
        if (order == SortOrder.NAME) {
            c = ascending ? nameComparatorAsc : nameComparatorDesc;
        }
        if (order == SortOrder.TIME) {
            c = ascending ? timeComparatorAsc : timeComparatorDesc;
        }
        if (c != comparator) {
            comparator = c;
            sortOrderChanged();
        }
    }

}
