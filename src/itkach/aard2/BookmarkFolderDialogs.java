package itkach.aard2;

import android.content.Context;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import itkach.aard2.descriptor.BlobDescriptor;
import itkach.aard2.descriptor.BookmarkFolders;
import itkach.aard2.prefs.BookmarkFolderPrefs;

/**
 * Dialogs for filing bookmarks under folders, writing a note on a bookmark and managing
 * the folders themselves.
 *
 * <p>A folder is a name shared by the bookmarks filed under it (see {@link BookmarkFolders});
 * {@link BookmarkFolderPrefs} additionally remembers the folders nothing is filed under
 * yet. Every change ends with the bookmark list notifying its observers, which is what the
 * folder selector of the bookmark tab and the note shown under an article listen to.</p>
 */
public final class BookmarkFolderDialogs {

    /** Receives a folder name typed by the user, cleaned up and never empty. */
    private interface OnFolderNamed {
        void onFolderNamed(@NonNull String name);
    }

    /** Receives what the user picked in the folders and note dialog. */
    private interface OnEdited {
        /**
         * @param addFolders    folders left ticked
         * @param removeFolders folders left unticked
         * @param note          the note text, null when the dialog had no note field
         */
        void onEdited(@NonNull Set<String> addFolders, @NonNull Set<String> removeFolders,
                      @Nullable String note);
    }

    private BookmarkFolderDialogs() {
    }

    /** Every folder, in alphabetical order: the ones in use and the still empty ones. */
    @NonNull
    public static List<String> getAllFolderNames() {
        Set<String> names = BookmarkFolderPrefs.getNames();
        names.addAll(SlobHelper.getInstance().bookmarks.getFolderNames());
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted, Collator.getInstance());
        return sorted;
    }

    /**
     * Edits the note and folders of an article. An article that is not bookmarked yet gets
     * bookmarked when the user saves a note or a folder for it.
     *
     * @param onSaved run after the user confirmed, may be null
     */
    @MainThread
    public static void editArticle(@NonNull Context context, @NonNull Uri articleUrl,
                                   @Nullable Runnable onSaved) {
        BlobDescriptorList bookmarks = SlobHelper.getInstance().bookmarks;
        BlobDescriptor bookmark = bookmarks.find(articleUrl);
        List<BlobDescriptor> current = new ArrayList<>();
        String title = null;
        if (bookmark != null) {
            current.add(bookmark);
            title = bookmark.key;
        } else {
            BlobDescriptor unsaved = BlobDescriptor.fromUri(articleUrl);
            if (unsaved != null) {
                title = unsaved.key;
            }
        }
        showEditDialog(context, title, current, true, (addFolders, removeFolders, note) -> {
            // Looked up again: the bookmark can have come or gone while the dialog was open
            BlobDescriptor target = bookmarks.find(articleUrl);
            if (target == null) {
                boolean nothingToKeep = addFolders.isEmpty() && (note == null || note.trim().isEmpty());
                if (nothingToKeep) {
                    return;
                }
                target = bookmarks.add(articleUrl);
                if (target == null) {
                    return;
                }
            }
            bookmarks.annotate(Collections.singletonList(target), addFolders, removeFolders, true, note);
        }, onSaved);
    }

    /**
     * Edits bookmarks picked in the bookmark list: note and folders for a single one, only
     * the folders for several, as a note belongs to one word.
     *
     * @param onSaved run after the user confirmed, may be null
     */
    @MainThread
    public static void editBookmarks(@NonNull Context context, @NonNull List<BlobDescriptor> targets,
                                     @Nullable Runnable onSaved) {
        if (targets.isEmpty()) {
            return;
        }
        boolean single = targets.size() == 1;
        String title = single ? targets.get(0).key
                : context.getString(R.string.specified_number_of_items_selected, targets.size());
        BlobDescriptorList bookmarks = SlobHelper.getInstance().bookmarks;
        showEditDialog(context, title, targets, single,
                (addFolders, removeFolders, note) ->
                        bookmarks.annotate(targets, addFolders, removeFolders, single, note),
                onSaved);
    }

    /**
     * @param current  the bookmarks being edited, used to pre-fill the dialog; may be empty
     * @param withNote whether to offer the note field, filled from the first of {@code current}
     */
    private static void showEditDialog(@NonNull Context context, @Nullable CharSequence title,
                                       @NonNull List<BlobDescriptor> current, boolean withNote,
                                       @NonNull OnEdited onEdited, @Nullable Runnable onSaved) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        View content = LayoutInflater.from(builder.getContext()).inflate(R.layout.dialog_bookmark_edit, null);
        EditText noteInput = content.findViewById(R.id.bookmark_note_input);
        ViewGroup folderContainer = content.findViewById(R.id.bookmark_folders_container);
        View noFoldersLabel = content.findViewById(R.id.bookmark_folders_empty);
        // Set when a folder is created from within the dialog, see the dismiss listener
        boolean[] folderCreated = {false};

        if (withNote) {
            String note = current.isEmpty() ? null : current.get(0).note;
            noteInput.setText(note == null ? "" : note);
        } else {
            content.findViewById(R.id.bookmark_note_layout).setVisibility(View.GONE);
        }

        for (String name : getAllFolderNames()) {
            addFolderCheckBox(folderContainer, name, getFolderState(current, name));
        }
        noFoldersLabel.setVisibility(folderContainer.getChildCount() == 0 ? View.VISIBLE : View.GONE);

        content.findViewById(R.id.bookmark_new_folder).setOnClickListener(view ->
                promptFolderName(context, R.string.folders_new, null, name -> {
                    MaterialCheckBox existing = findFolderCheckBox(folderContainer, name);
                    if (existing != null) {
                        existing.setChecked(true);
                        return;
                    }
                    BookmarkFolderPrefs.add(name);
                    folderCreated[0] = true;
                    addFolderCheckBox(folderContainer, name, MaterialCheckBox.STATE_CHECKED);
                    noFoldersLabel.setVisibility(View.GONE);
                }));

        AlertDialog editDialog = builder.setTitle(title)
                .setView(content)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    Set<String> addFolders = new LinkedHashSet<>();
                    Set<String> removeFolders = new LinkedHashSet<>();
                    for (int index = 0; index < folderContainer.getChildCount(); index++) {
                        MaterialCheckBox checkBox = (MaterialCheckBox) folderContainer.getChildAt(index);
                        String name = (String) checkBox.getTag();
                        int state = checkBox.getCheckedState();
                        if (state == MaterialCheckBox.STATE_CHECKED) {
                            addFolders.add(name);
                        } else if (state == MaterialCheckBox.STATE_UNCHECKED) {
                            removeFolders.add(name);
                        }
                        // Left indeterminate: only some of the bookmarks are in it, keep it so
                    }
                    Editable noteText = noteInput.getText();
                    onEdited.onEdited(addFolders, removeFolders,
                            withNote && noteText != null ? noteText.toString() : null);
                    if (onSaved != null) {
                        onSaved.run();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .setOnDismissListener(dialog -> {
                    if (folderCreated[0]) {
                        // A new folder exists whether or not the edit was saved: let the
                        // folder selector of the bookmark tab pick it up
                        SlobHelper.getInstance().bookmarks.notifyDataSetChanged();
                    }
                })
                .create();
        if (withNote) {
            noteInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence text, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable text) {
                    // A note being typed is not lost to a stray tap next to the dialog
                    editDialog.setCanceledOnTouchOutside(false);
                }
            });
        }
        editDialog.show();
    }

    /**
     * A folder is ticked when all the edited bookmarks are in it, unticked when none is and
     * indeterminate when only some are.
     */
    private static int getFolderState(@NonNull List<BlobDescriptor> current, @NonNull String folder) {
        int inFolder = 0;
        for (BlobDescriptor bookmark : current) {
            if (BookmarkFolders.isIn(bookmark, folder)) {
                inFolder++;
            }
        }
        if (inFolder == 0) {
            return MaterialCheckBox.STATE_UNCHECKED;
        }
        return inFolder == current.size()
                ? MaterialCheckBox.STATE_CHECKED : MaterialCheckBox.STATE_INDETERMINATE;
    }

    private static void addFolderCheckBox(@NonNull ViewGroup container, @NonNull String name, int state) {
        MaterialCheckBox checkBox = new MaterialCheckBox(container.getContext());
        checkBox.setText(name);
        checkBox.setTag(name);
        checkBox.setCheckedState(state);
        container.addView(checkBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Nullable
    private static MaterialCheckBox findFolderCheckBox(@NonNull ViewGroup container, @NonNull String name) {
        for (int index = 0; index < container.getChildCount(); index++) {
            View child = container.getChildAt(index);
            if (child instanceof MaterialCheckBox && name.equalsIgnoreCase((String) child.getTag())) {
                return (MaterialCheckBox) child;
            }
        }
        return null;
    }

    /** Lists the folders so that one can be picked to rename or delete it, or a new one created. */
    @MainThread
    public static void showManageDialog(@NonNull Context context) {
        List<String> names = getAllFolderNames();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.folders_manage)
                .setPositiveButton(R.string.folders_new, (dialog, which) -> promptNewFolder(context))
                .setNegativeButton(R.string.action_cancel, null);
        if (names.isEmpty()) {
            builder.setMessage(R.string.folders_none_yet);
        } else {
            builder.setItems(names.toArray(new String[0]),
                    (dialog, which) -> showFolderActions(context, names.get(which)));
        }
        builder.show();
    }

    /** Offers to rename or delete the given folder. */
    @MainThread
    public static void showFolderActions(@NonNull Context context, @NonNull String folder) {
        String[] actions = {context.getString(R.string.action_rename), context.getString(R.string.delete)};
        new MaterialAlertDialogBuilder(context)
                .setTitle(folder)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        promptRenameFolder(context, folder);
                    } else {
                        confirmDeleteFolder(context, folder);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Asks for a name and creates an empty folder with it. */
    @MainThread
    public static void promptNewFolder(@NonNull Context context) {
        promptFolderName(context, R.string.folders_new, null, name -> {
            if (BookmarkFolders.findIgnoreCase(getAllFolderNames(), name) != null) {
                Toast.makeText(context, R.string.folders_exists, Toast.LENGTH_SHORT).show();
                return;
            }
            BookmarkFolderPrefs.add(name);
            SlobHelper.getInstance().bookmarks.notifyDataSetChanged();
        });
    }

    private static void promptRenameFolder(@NonNull Context context, @NonNull String oldName) {
        promptFolderName(context, R.string.folders_rename_title, oldName, newName -> {
            if (newName.equals(oldName)) {
                return;
            }
            String clash = BookmarkFolders.findIgnoreCase(getAllFolderNames(), newName);
            // Changing only the case of a name clashes with nothing but the folder itself
            if (clash != null && !clash.equals(oldName)) {
                Toast.makeText(context, R.string.folders_exists, Toast.LENGTH_SHORT).show();
                return;
            }
            BookmarkFolderPrefs.rename(oldName, newName);
            SlobHelper.getInstance().bookmarks.renameFolder(oldName, newName);
        });
    }

    private static void confirmDeleteFolder(@NonNull Context context, @NonNull String folder) {
        new MaterialAlertDialogBuilder(context)
                .setMessage(context.getString(R.string.folders_confirm_delete, folder))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    BookmarkFolderPrefs.remove(folder);
                    SlobHelper.getInstance().bookmarks.deleteFolder(folder);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * Asks for a folder name. {@code onFolderNamed} is only called with a usable name:
     * confirming an empty field does nothing.
     */
    private static void promptFolderName(@NonNull Context context, @StringRes int title,
                                         @Nullable String initialName,
                                         @NonNull OnFolderNamed onFolderNamed) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        View content = LayoutInflater.from(builder.getContext()).inflate(R.layout.dialog_folder_name, null);
        EditText input = content.findViewById(R.id.folder_name_input);
        if (initialName != null) {
            input.setText(initialName);
            input.setSelection(input.getText().length());
        }
        AlertDialog dialog = builder.setTitle(title)
                .setView(content)
                .setPositiveButton(R.string.action_save, (dialogInterface, which) -> {
                    String name = BookmarkFolders.normalizeName(input.getText().toString());
                    if (name != null) {
                        onFolderNamed.onFolderNamed(name);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        // The name is all this dialog asks for: bring up the keyboard right away
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        input.requestFocus();
        dialog.show();
    }
}
