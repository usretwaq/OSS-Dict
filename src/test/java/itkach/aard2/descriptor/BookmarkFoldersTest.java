package itkach.aard2.descriptor;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link BookmarkFolders}, the folder membership and note logic of bookmarks.
 *
 * <p>Pure JVM: the class under test has no Android dependency.</p>
 */
public class BookmarkFoldersTest {

    private static BlobDescriptor bookmark(String key, String... folders) {
        BlobDescriptor descriptor = new BlobDescriptor();
        descriptor.id = "id-" + key;
        descriptor.slobId = "dict-a";
        descriptor.blobId = "blob-" + key;
        descriptor.key = key;
        descriptor.folders = new ArrayList<>(Arrays.asList(folders));
        return descriptor;
    }

    // ── names ────────────────────────────────────────────────────────────────

    @Test
    public void normalizeNameTrimsAndRejectsBlank() {
        assertEquals("Verbs", BookmarkFolders.normalizeName("  Verbs \n"));
        assertEquals("Two words", BookmarkFolders.normalizeName("Two\nwords"));
        assertNull(BookmarkFolders.normalizeName("   "));
        assertNull(BookmarkFolders.normalizeName(""));
        assertNull(BookmarkFolders.normalizeName(null));
    }

    @Test
    public void normalizeNameCapsTheLength() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < BookmarkFolders.MAX_NAME_LENGTH + 15; i++) {
            longName.append('x');
        }
        String normalized = BookmarkFolders.normalizeName(longName.toString());
        assertNotNull(normalized);
        assertEquals(BookmarkFolders.MAX_NAME_LENGTH, normalized.length());
    }

    @Test
    public void findIgnoreCaseReturnsTheStoredSpelling() {
        List<String> names = Arrays.asList("Verbs", "Глаголы");
        assertEquals("Verbs", BookmarkFolders.findIgnoreCase(names, "verbs"));
        assertEquals("Глаголы", BookmarkFolders.findIgnoreCase(names, "глаголы"));
        assertNull(BookmarkFolders.findIgnoreCase(names, "Nouns"));
    }

    // ── membership ───────────────────────────────────────────────────────────

    @Test
    public void newBookmarkIsUnfiled() {
        BlobDescriptor descriptor = new BlobDescriptor();
        assertNotNull(descriptor.folders);
        assertTrue(BookmarkFolders.isUnfiled(descriptor));
        assertFalse(BookmarkFolders.hasAnnotations(descriptor));
    }

    @Test
    public void bookmarkCanBeInSeveralFolders() {
        BlobDescriptor descriptor = bookmark("lopen");

        assertTrue(BookmarkFolders.update(descriptor, Arrays.asList("Verbs", "Week 1"),
                Collections.emptyList()));

        assertTrue(BookmarkFolders.isIn(descriptor, "Verbs"));
        assertTrue(BookmarkFolders.isIn(descriptor, "Week 1"));
        assertFalse(BookmarkFolders.isIn(descriptor, "Nouns"));
        assertFalse(BookmarkFolders.isUnfiled(descriptor));
        assertTrue(BookmarkFolders.hasAnnotations(descriptor));
    }

    @Test
    public void updateAddsAndRemovesAndLeavesOtherFoldersAlone() {
        BlobDescriptor descriptor = bookmark("lopen", "Verbs", "Week 1", "Hard");

        boolean changed = BookmarkFolders.update(descriptor,
                Collections.singletonList("Exam"), Collections.singletonList("Week 1"));

        assertTrue(changed);
        assertEquals(Arrays.asList("Verbs", "Hard", "Exam"), descriptor.folders);
    }

    @Test
    public void updateReportsNoChangeWhenNothingChanges() {
        BlobDescriptor descriptor = bookmark("lopen", "Verbs");

        assertFalse(BookmarkFolders.update(descriptor,
                Collections.singletonList("Verbs"), Collections.singletonList("Nouns")));
        assertEquals(Collections.singletonList("Verbs"), descriptor.folders);
    }

    @Test
    public void updateNeverStoresAFolderTwice() {
        BlobDescriptor descriptor = bookmark("lopen", "Verbs");

        BookmarkFolders.update(descriptor, Arrays.asList("Verbs", "Verbs", "Hard"),
                Collections.emptyList());

        assertEquals(Arrays.asList("Verbs", "Hard"), descriptor.folders);
    }

    @Test
    public void updateReplacesTheListInsteadOfChangingItInPlace() {
        BlobDescriptor descriptor = bookmark("lopen", "Verbs");
        List<String> before = descriptor.folders;

        BookmarkFolders.update(descriptor, Collections.singletonList("Hard"), Collections.emptyList());

        assertNotSame("a copy held by a running backup must not change", before, descriptor.folders);
        assertEquals(Collections.singletonList("Verbs"), before);
    }

    @Test
    public void updateCopesWithMissingFolderList() {
        BlobDescriptor descriptor = bookmark("lopen");
        descriptor.folders = null;

        assertTrue(BookmarkFolders.isUnfiled(descriptor));
        assertFalse(BookmarkFolders.isIn(descriptor, "Verbs"));
        assertTrue(BookmarkFolders.update(descriptor, Collections.singletonList("Verbs"),
                Collections.emptyList()));
        assertEquals(Collections.singletonList("Verbs"), descriptor.folders);
    }

    // ── whole-folder operations ──────────────────────────────────────────────

    @Test
    public void collectListsEveryFolderOnce() {
        List<BlobDescriptor> bookmarks = Arrays.asList(
                bookmark("lopen", "Verbs", "Week 1"), bookmark("huis", "Nouns", "Week 1"),
                bookmark("snel"));

        assertEquals(Arrays.asList("Verbs", "Week 1", "Nouns"),
                new ArrayList<>(BookmarkFolders.collect(bookmarks)));
    }

    @Test
    public void countCountsBookmarksPerFolder() {
        List<BlobDescriptor> bookmarks = Arrays.asList(
                bookmark("lopen", "Verbs", "Week 1"), bookmark("huis", "Nouns", "Week 1"),
                bookmark("snel"));

        Map<String, Integer> counts = BookmarkFolders.count(bookmarks);

        assertEquals(Integer.valueOf(1), counts.get("Verbs"));
        assertEquals(Integer.valueOf(2), counts.get("Week 1"));
        assertEquals(Integer.valueOf(1), counts.get("Nouns"));
        assertNull(counts.get("Empty"));
        assertEquals(1, BookmarkFolders.countUnfiled(bookmarks));
    }

    @Test
    public void renameChangesTheFolderOnEveryBookmarkInIt() {
        BlobDescriptor lopen = bookmark("lopen", "Verbs", "Week 1");
        BlobDescriptor huis = bookmark("huis", "Nouns");
        BlobDescriptor zijn = bookmark("zijn", "Verbs");

        List<BlobDescriptor> changed = BookmarkFolders.rename(
                Arrays.asList(lopen, huis, zijn), "Verbs", "Werkwoorden");

        assertEquals(Arrays.asList(lopen, zijn), changed);
        assertEquals(Arrays.asList("Werkwoorden", "Week 1"), lopen.folders);
        assertEquals(Collections.singletonList("Nouns"), huis.folders);
        assertEquals(Collections.singletonList("Werkwoorden"), zijn.folders);
    }

    @Test
    public void renameOntoAFolderTheBookmarkIsAlreadyInKeepsOneEntry() {
        BlobDescriptor lopen = bookmark("lopen", "Verbs", "Werkwoorden");

        BookmarkFolders.rename(Collections.singletonList(lopen), "Verbs", "Werkwoorden");

        assertEquals(Collections.singletonList("Werkwoorden"), lopen.folders);
    }

    @Test
    public void removeTakesBookmarksOutOfTheFolderButKeepsThem() {
        BlobDescriptor lopen = bookmark("lopen", "Verbs", "Week 1");
        BlobDescriptor huis = bookmark("huis", "Nouns");
        List<BlobDescriptor> bookmarks = new ArrayList<>(Arrays.asList(lopen, huis));

        List<BlobDescriptor> changed = BookmarkFolders.remove(bookmarks, "Verbs");

        assertEquals(Collections.singletonList(lopen), changed);
        assertEquals(2, bookmarks.size());
        assertEquals(Collections.singletonList("Week 1"), lopen.folders);
        assertEquals(Collections.singletonList("Nouns"), huis.folders);
    }

    // ── notes ────────────────────────────────────────────────────────────────

    @Test
    public void setNoteStoresTrimmedTextAndReportsChange() {
        BlobDescriptor descriptor = bookmark("lopen");

        assertTrue(BookmarkFolders.setNote(descriptor, "  to walk; irregular past tense\n"));
        assertEquals("to walk; irregular past tense", descriptor.note);
        assertTrue(BookmarkFolders.hasAnnotations(descriptor));
        assertFalse("same text is not a change",
                BookmarkFolders.setNote(descriptor, "to walk; irregular past tense"));
    }

    @Test
    public void setNoteKeepsLineBreaksInsideTheText() {
        BlobDescriptor descriptor = bookmark("lopen");

        BookmarkFolders.setNote(descriptor, "line one\nline two");

        assertEquals("line one\nline two", descriptor.note);
    }

    @Test
    public void blankNoteClearsIt() {
        BlobDescriptor descriptor = bookmark("lopen");
        descriptor.note = "old";

        assertTrue(BookmarkFolders.setNote(descriptor, "   "));
        assertNull(descriptor.note);
        assertFalse(BookmarkFolders.setNote(descriptor, null));
    }

    // ── merge on import ──────────────────────────────────────────────────────

    @Test
    public void mergeIntoAddsFoldersWithoutRemovingAny() {
        BlobDescriptor stored = bookmark("lopen", "Verbs");
        BlobDescriptor imported = bookmark("lopen", "Week 1");

        assertTrue(BookmarkFolders.mergeInto(stored, imported));
        assertEquals(Arrays.asList("Verbs", "Week 1"), stored.folders);
    }

    @Test
    public void mergeIntoReportsNoChangeWhenNothingIsNew() {
        BlobDescriptor stored = bookmark("lopen", "Verbs");
        stored.note = "Mine";
        BlobDescriptor imported = bookmark("lopen", "Verbs");
        imported.note = "Mine";

        assertFalse(BookmarkFolders.mergeInto(stored, imported));
    }
}
