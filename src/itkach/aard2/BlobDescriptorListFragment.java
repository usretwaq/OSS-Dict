package itkach.aard2;

import android.app.Activity;
import android.content.SharedPreferences;
import android.database.DataSetObserver;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.SearchView;
import androidx.core.content.ContextCompat;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import itkach.aard2.descriptor.BlobDescriptor;
import itkach.aard2.utils.ThreadUtils;


abstract class BlobDescriptorListFragment extends BaseListFragment implements ActionMode.Callback {
    protected ActionMode actionMode;

    private Drawable icClock;
    private Drawable icList;
    private Drawable icArrowUp;
    private Drawable icArrowDown;

    private BlobDescriptorListAdapter listAdapter;
    private AlertDialog deleteConfirmationDialog = null;

    private final static String PREF_SORT_ORDER = "sortOrder";
    private final static String PREF_SORT_DIRECTION = "sortDir";
    private final static String PREF_FOLDER = "folder";

    /** Tag of the folder selector entry that shows the entries of every folder. */
    private static final Object ALL_FOLDERS = new Object();

    private MenuItem miFilter = null;

    @Nullable
    private ChipGroup folderChips;
    /** What the folder selector currently lists, to rebuild it only when that changes. */
    @Nullable
    private String folderChipsContent;
    private final DataSetObserver folderObserver = new DataSetObserver() {
        @Override
        public void onChanged() {
            // Posted, as the list adapter does: the list also changes from background threads
            ThreadUtils.postOnMainThread(BlobDescriptorListFragment.this::refreshFolderSelector);
        }
    };

    abstract BlobDescriptorList getDescriptorList();

    abstract String getItemClickAction();

    abstract int getDeleteConfirmationItemCountResId();

    abstract String getPreferencesNS();

    /** Whether the entries of this list can be filed under folders and carry a note. */
    boolean supportsFolders() {
        return false;
    }

    @NonNull
    private SharedPreferences prefs() {
        return requireActivity().getSharedPreferences(getPreferencesNS(), Activity.MODE_PRIVATE);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final AppCompatActivity activity = (AppCompatActivity) requireActivity();

        BlobDescriptorList descriptorList = getDescriptorList();

        SharedPreferences p = this.prefs();

        String sortOrderStr = p.getString(PREF_SORT_ORDER,
                BlobDescriptorList.SortOrder.TIME.name());
        BlobDescriptorList.SortOrder sortOrder = BlobDescriptorList.SortOrder.valueOf(sortOrderStr);

        boolean sortDir = p.getBoolean(PREF_SORT_DIRECTION, false);

        descriptorList.setSort(sortOrder, sortDir);

        if (supportsFolders()) {
            descriptorList.setFolderFilter(p.getString(PREF_FOLDER, null));
            folderChips = view.findViewById(R.id.folder_chips);
            view.findViewById(R.id.folder_bar).setVisibility(View.VISIBLE);
            descriptorList.registerDataSetObserver(folderObserver);
            refreshFolderSelector();
        }

        listAdapter = new BlobDescriptorListAdapter(descriptorList, getItemClickAction());
        listAdapter.setOnSelectionStartedListener(new BlobDescriptorListAdapter.OnSelectionChangeListener() {
            @Override
            public void selectionStarted() {
                activity.startSupportActionMode(BlobDescriptorListFragment.this);
            }

            @Override
            public void selectionChanged(int selectionCount) {
                if (actionMode != null) {
                    actionMode.setTitle(getString(R.string.specified_number_of_items_selected, selectionCount));
                }
            }

            @Override
            public void selectionCanceled() {
                finishActionMode();
            }
        });

        icClock = ContextCompat.getDrawable(activity, R.drawable.ic_clock_time_nine);
        icList = ContextCompat.getDrawable(activity, R.drawable.ic_format_list_bulleted);
        icArrowUp = ContextCompat.getDrawable(activity, R.drawable.ic_sort_ascending);
        icArrowDown = ContextCompat.getDrawable(activity, R.drawable.ic_sort_descending);

        recyclerView.setAdapter(listAdapter);
    }

    @Override
    public void onDestroyView() {
        if (folderChips != null) {
            getDescriptorList().unregisterDataSetObserver(folderObserver);
            folderChips = null;
            folderChipsContent = null;
        }
        super.onDestroyView();
    }

    /**
     * Brings the folder selector in line with the folders that exist, the number of
     * entries in each and the folder being shown. Runs after every change of the list.
     */
    private void refreshFolderSelector() {
        if (folderChips == null || getContext() == null) {
            return;
        }
        BlobDescriptorList list = getDescriptorList();
        String shown = list.getFolderFilter();
        boolean showsUnfiled = BlobDescriptorList.FOLDER_UNFILED.equals(shown);
        List<String> names = BookmarkFolderDialogs.getAllFolderNames();
        if (shown != null && !showsUnfiled && !names.contains(shown)) {
            // Never hide the folder being shown, even if nothing else knows it any more
            names.add(shown);
        }
        Map<String, Integer> counts = list.getFolderCounts();
        int total = list.getTotalCount();
        int unfiled = list.getUnfiledCount();
        // "No folder" only helps to tell entries apart: with every entry in it, it repeats "All"
        boolean offerUnfiled = showsUnfiled || (unfiled > 0 && unfiled < total);

        StringBuilder content = new StringBuilder().append(total).append('/')
                .append(offerUnfiled ? unfiled : -1);
        for (String name : names) {
            content.append('\n').append(name).append('\t').append(counts.get(name));
        }
        if (!content.toString().equals(folderChipsContent)) {
            folderChipsContent = content.toString();
            folderChips.removeAllViews();
            addFolderChip(ALL_FOLDERS, getString(R.string.folders_all), total);
            for (String name : names) {
                Integer count = counts.get(name);
                addFolderChip(name, name, count == null ? 0 : count);
            }
            if (offerUnfiled) {
                addFolderChip(BlobDescriptorList.FOLDER_UNFILED, getString(R.string.folders_unfiled), unfiled);
            }
            Chip newFolderChip = new Chip(folderChips.getContext());
            newFolderChip.setText(R.string.folders_new);
            newFolderChip.setChipIconResource(R.drawable.ic_add);
            newFolderChip.setChipIconVisible(true);
            newFolderChip.setCheckable(false);
            newFolderChip.setOnClickListener(view -> BookmarkFolderDialogs.promptNewFolder(requireActivity()));
            folderChips.addView(newFolderChip);
            if (!names.isEmpty()) {
                // Renaming and deleting, also offered by a long press on a folder
                Chip manageChip = new Chip(folderChips.getContext());
                manageChip.setText(R.string.folders_manage);
                manageChip.setChipIconResource(R.drawable.ic_edit);
                manageChip.setChipIconVisible(true);
                manageChip.setCheckable(false);
                manageChip.setOnClickListener(view -> BookmarkFolderDialogs.showManageDialog(requireActivity()));
                folderChips.addView(manageChip);
            }
        }

        // Ticking the entry of the folder being shown unticks the others: single selection
        Object shownTag = shown == null ? ALL_FOLDERS : shown;
        for (int index = 0; index < folderChips.getChildCount(); index++) {
            View child = folderChips.getChildAt(index);
            if (child instanceof Chip && shownTag.equals(child.getTag())) {
                ((Chip) child).setChecked(true);
            }
        }

        // The folder being shown can change without a tap here: renamed, deleted
        SharedPreferences.Editor editor = prefs().edit();
        if (shown == null) {
            editor.remove(PREF_FOLDER);
        } else {
            editor.putString(PREF_FOLDER, shown);
        }
        editor.apply();

        TextView emptyText = emptyView.findViewById(R.id.empty_text);
        emptyText.setText(shown == null || showsUnfiled
                ? getEmptyText() : getString(R.string.folders_empty_folder));
    }

    /**
     * @param folder what tapping the entry shows: a folder name, {@link #ALL_FOLDERS} or
     *               {@link BlobDescriptorList#FOLDER_UNFILED}
     */
    private void addFolderChip(@NonNull Object folder, @NonNull String label, int count) {
        if (folderChips == null) {
            return;
        }
        Chip chip = (Chip) LayoutInflater.from(folderChips.getContext())
                .inflate(R.layout.folder_chip, folderChips, false);
        chip.setText(getString(R.string.folders_chip_label, label, count));
        chip.setTag(folder);
        chip.setOnClickListener(view ->
                getDescriptorList().setFolderFilter(folder == ALL_FOLDERS ? null : (String) folder));
        boolean isUserFolder = folder != ALL_FOLDERS && !BlobDescriptorList.FOLDER_UNFILED.equals(folder);
        if (isUserFolder) {
            chip.setOnLongClickListener(view -> {
                BookmarkFolderDialogs.showFolderActions(requireActivity(), (String) folder);
                return true;
            });
        }
        folderChips.addView(chip);
    }

    private List<BlobDescriptor> getSelectedItems() {
        List<BlobDescriptor> selected = new ArrayList<>();
        SparseBooleanArray checkedItems = listAdapter.getCheckedItemPositions();
        for (int index = 0; index < checkedItems.size(); index++) {
            int position = checkedItems.keyAt(index);
            if (checkedItems.valueAt(index) && position >= 0 && position < listAdapter.getItemCount()) {
                selected.add(listAdapter.getItem(position));
            }
        }
        return selected;
    }

    protected void deleteSelectedItems() {
        SparseBooleanArray checkedItems = listAdapter.getCheckedItemPositions();
        for (int i = checkedItems.size() - 1; i > -1; --i) {
            int position = checkedItems.keyAt(i);
            boolean checked = checkedItems.valueAt(i);
            if (checked) {
                getDescriptorList().remove(position);
            }
        }
    }

    public void finishActionMode() {
        if (actionMode != null) {
            actionMode.finish();
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.blob_descriptor_list, menu);
    }

    @Override
    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        BlobDescriptorList list = getDescriptorList();

        miFilter = menu.findItem(R.id.action_filter);

        View filterActionView = miFilter.getActionView();
        SearchView searchView = filterActionView
                .findViewById(R.id.search);
        searchView.setQueryHint(miFilter.getTitle());
        searchView.setQuery(list.getFilter(), true);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                BlobDescriptorList list = getDescriptorList();
                if (!newText.equals(list.getFilter())) {
                    getDescriptorList().setFilter(newText);
                }
                return true;
            }
        });
        setSortOrder(menu.findItem(R.id.action_sort_order), list.getSortOrder());
        setAscending(menu.findItem(R.id.action_sort_asc), list.isAscending());

        super.onPrepareOptionsMenu(menu);
    }

    private void setSortOrder(MenuItem mi, BlobDescriptorList.SortOrder order) {
        Drawable icon;
        int textRes;
        if (order == BlobDescriptorList.SortOrder.TIME) {
            icon = icClock;
            textRes = R.string.action_sort_by_time;
        } else {
            icon = icList;
            textRes = R.string.action_sort_by_title;
        }
        mi.setIcon(icon);
        mi.setTitle(textRes);
        SharedPreferences p = this.prefs();
        SharedPreferences.Editor editor = p.edit();
        editor.putString(PREF_SORT_ORDER, order.name());
        editor.apply();
    }

    private void setAscending(MenuItem mi, boolean ascending) {
        Drawable icon;
        int textRes;
        if (ascending) {
            icon = icArrowUp;
            textRes = R.string.action_ascending;
        } else {
            icon = icArrowDown;
            textRes = R.string.action_descending;
        }
        mi.setIcon(icon);
        mi.setTitle(textRes);
        SharedPreferences p = this.prefs();
        SharedPreferences.Editor editor = p.edit();
        editor.putBoolean(PREF_SORT_DIRECTION, ascending);
        editor.apply();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem mi) {
        BlobDescriptorList list = getDescriptorList();
        int itemId = mi.getItemId();
        if (itemId == R.id.action_sort_asc) {
            list.setSort(!list.isAscending());
            setAscending(mi, list.isAscending());
            return true;
        }
        if (itemId == R.id.action_sort_order) {
            if (list.getSortOrder() == BlobDescriptorList.SortOrder.TIME) {
                list.setSort(BlobDescriptorList.SortOrder.NAME);
            } else {
                list.setSort(BlobDescriptorList.SortOrder.TIME);
            }
            setSortOrder(mi, list.getSortOrder());
            return true;
        }
        return super.onOptionsItemSelected(mi);
    }


    @Override
    public void onPause() {
        super.onPause();
        if (deleteConfirmationDialog != null) {
            deleteConfirmationDialog.dismiss();
        }
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        actionMode = mode;
        if (mode != null) {
            mode.getMenuInflater().inflate(R.menu.blob_descriptor_selection, menu);
            menu.findItem(R.id.blob_descriptor_folders).setVisible(supportsFolders());
        }
        listAdapter.setSelectionMode(true);
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return false;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.blob_descriptor_delete) {
            int count = listAdapter.getCheckedItemCount();
            String countStr = getResources().getQuantityString(getDeleteConfirmationItemCountResId(), count, count);
            String message = getString(R.string.blob_descriptor_confirm_delete, countStr);
            deleteConfirmationDialog = new MaterialAlertDialogBuilder(requireActivity())
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setTitle("")
                    .setMessage(message)
                    .setPositiveButton(R.string.action_yes, (dialog, which) -> {
                        deleteSelectedItems();
                        mode.finish();
                        deleteConfirmationDialog = null;
                    })
                    .setNegativeButton(R.string.action_no, null)
                    .create();
            deleteConfirmationDialog.setOnDismissListener(dialogInterface -> deleteConfirmationDialog = null);
            deleteConfirmationDialog.show();
            return true;
        } else if (itemId == R.id.blob_descriptor_folders) {
            BookmarkFolderDialogs.editBookmarks(requireActivity(), getSelectedItems(), mode::finish);
            return true;
        } else if (itemId == R.id.blob_descriptor_select_all) {
            listAdapter.selectAll();
            return true;
        }
        return false;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        actionMode = null;
        listAdapter.setSelectionMode(false);
    }
}
