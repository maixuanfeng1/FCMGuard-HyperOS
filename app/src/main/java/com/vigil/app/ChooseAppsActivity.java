package com.vigil.app;

import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.os.BundleCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.DynamicColors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Full-screen Material 3 app picker with instant persistence and stable pinned ordering so
 * toggles never jump rows out from under the user's finger.
 */
public class ChooseAppsActivity extends AppCompatActivity {
    private static final String STATE_FILTER_MODE = "picker_filter_mode";
    private static final String STATE_PINNED_SELECTED = "picker_pinned_selected";
    private static final String STATE_LIST_STATE = "picker_list_state";

    private final ExecutorService loader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "vigil-app-picker");
        thread.setDaemon(true);
        return thread;
    });
    private Future<?> activeLoad;
    private int loadGeneration;

    private List<FcmAppScanner.AppEntry> catalog = Collections.emptyList();
    private boolean catalogLoaded;
    private boolean loadFailed;
    private Set<String> workingSelected = new HashSet<>();
    private Set<String> pinnedSelected = new HashSet<>();
    private AppPickerFilter.Mode filterMode = AppPickerFilter.Mode.ALL;
    private Parcelable restoredListState;
    private boolean syncingFilterChips;

    private TextView summaryText;
    private EditText searchInput;
    private View clearBtn;
    private HorizontalScrollView filterScroll;
    private ChipGroup filterGroup;
    private Chip allChip;
    private Chip fcmChip;
    private Chip selectedChip;
    private TextView loadingText;
    private TextView emptyText;
    private RecyclerView recycler;
    AppPickerAdapter adapter;

    <T extends View> T ui(int id) {
        return findViewById(id);
    }

    @Override
    protected void onCreate(Bundle state) {
        LocaleHelper.followSystem(this);
        String mode = ThemeHelper.getMode(this);
        getDelegate().setLocalNightMode(ThemeHelper.MODE_DARK.equals(mode) ? AppCompatDelegate.MODE_NIGHT_YES :
                ThemeHelper.MODE_LIGHT.equals(mode) ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_choose_apps);

        MaterialToolbar toolbar = ui(R.id.pickerToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        summaryText = ui(R.id.pickerSummaryText);
        searchInput = ui(R.id.pickerSearchInput);
        clearBtn = ui(R.id.pickerClearSearchBtn);
        filterScroll = ui(R.id.pickerFilterScroll);
        filterGroup = ui(R.id.pickerFilterGroup);
        allChip = ui(R.id.filterAllChip);
        fcmChip = ui(R.id.filterFcmChip);
        selectedChip = ui(R.id.filterSelectedChip);
        loadingText = ui(R.id.pickerLoadingText);
        emptyText = ui(R.id.pickerEmptyText);
        recycler = ui(R.id.pickerRecycler);

        applyWindowInsets();

        workingSelected = DiagnosticPreferences.selected(this);
        if (state != null) {
            filterMode = parseMode(state.getString(STATE_FILTER_MODE));
            ArrayList<String> savedPinned = state.getStringArrayList(STATE_PINNED_SELECTED);
            pinnedSelected = savedPinned != null ? new HashSet<>(savedPinned) : new HashSet<>(workingSelected);
            restoredListState = BundleCompat.getParcelable(state, STATE_LIST_STATE, Parcelable.class);
        } else {
            pinnedSelected = new HashSet<>(workingSelected);
        }

        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setSaveEnabled(false);
        recycler.setItemAnimator(null);
        adapter = new AppPickerAdapter(this, (packageName, checked) -> {
            if (checked) workingSelected.add(packageName);
            else workingSelected.remove(packageName);
            DiagnosticPreferences.selected(this, workingSelected);
            renderPicker();
        });
        recycler.setAdapter(adapter);
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView view, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    hideKeyboard();
                }
            }
        });

        clearBtn.setOnClickListener(v -> searchInput.setText(""));
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                return true;
            }
            return false;
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { renderPicker(); }
        });

        syncFilterChipSelection();
        filterGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (syncingFilterChips) return;
            int id = checkedIds.isEmpty() ? R.id.filterAllChip : checkedIds.get(0);
            filterMode = id == R.id.filterFcmChip ? AppPickerFilter.Mode.FCM_ONLY :
                    id == R.id.filterSelectedChip ? AppPickerFilter.Mode.SELECTED_ONLY : AppPickerFilter.Mode.ALL;
            pinnedSelected = new HashSet<>(workingSelected);
            restoredListState = null;
            renderPicker();
            recycler.scrollToPosition(0);
        });

        renderPicker();
        loadCatalog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(!ThemeHelper.isDark(this));
        bars.setAppearanceLightNavigationBars(!ThemeHelper.isDark(this));
        Set<String> latest = DiagnosticPreferences.selected(this);
        if (!latest.equals(workingSelected)) {
            workingSelected = latest;
            renderPicker();
        }
        if (loadFailed) {
            loadFailed = false;
            catalogLoaded = false;
            renderPicker();
            loadCatalog();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(STATE_FILTER_MODE, filterMode.name());
        outState.putStringArrayList(STATE_PINNED_SELECTED, new ArrayList<>(pinnedSelected));
        Parcelable listState = restoredListState != null
                ? restoredListState
                : (recycler != null && recycler.getLayoutManager() != null
                        ? recycler.getLayoutManager().onSaveInstanceState()
                        : null);
        if (listState != null) {
            outState.putParcelable(STATE_LIST_STATE, listState);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        loadGeneration++;
        if (activeLoad != null) {
            activeLoad.cancel(true);
            activeLoad = null;
        }
        loader.shutdownNow();
        super.onDestroy();
    }

    boolean isCatalogLoaded() {
        return catalogLoaded;
    }

    void setCatalogForTest(List<FcmAppScanner.AppEntry> entries) {
        loadGeneration++;
        if (activeLoad != null) {
            activeLoad.cancel(true);
            activeLoad = null;
        }
        catalog = entries == null ? Collections.emptyList() : new ArrayList<>(entries);
        catalogLoaded = true;
        loadFailed = false;
        workingSelected = DiagnosticPreferences.selected(this);
        pinnedSelected = new HashSet<>(workingSelected);
        renderPicker();
    }

    private void loadCatalog() {
        if (loader.isShutdown()) return;
        final int gen = ++loadGeneration;
        final Context app = getApplicationContext();
        activeLoad = loader.submit(() -> {
            List<FcmAppScanner.AppEntry> found;
            boolean failed = false;
            try {
                found = FcmAppScanner.scanAll(app);
            } catch (RuntimeException e) {
                Log.w("VigilAppPicker", "Failed to scan installed apps", e);
                found = Collections.emptyList();
                failed = true;
            }
            final List<FcmAppScanner.AppEntry> result = found;
            final boolean didFail = failed;
            runOnUiThread(() -> {
                if (gen != loadGeneration || isFinishing() || isDestroyed()) return;
                catalog = result;
                catalogLoaded = true;
                loadFailed = didFail;
                renderPicker();
            });
        });
    }

    private void renderPicker() {
        String query = searchInput.getText() == null ? "" : searchInput.getText().toString();
        clearBtn.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);

        if (!catalogLoaded) {
            allChip.setText(getString(R.string.filter_all, 0));
            fcmChip.setText(getString(R.string.filter_fcm, 0));
            selectedChip.setText(getString(R.string.filter_selected, workingSelected.size()));
            summaryText.setText(R.string.checking);
            loadingText.setVisibility(View.VISIBLE);
            emptyText.setVisibility(View.GONE);
            recycler.setVisibility(View.GONE);
            return;
        }

        int fcmCount = AppPickerFilter.countFcm(catalog);
        int chosenInCatalog = AppPickerFilter.countSelectedInCatalog(catalog, workingSelected);
        List<FcmAppScanner.AppEntry> filtered = AppPickerFilter.filter(
                catalog, workingSelected, pinnedSelected, filterMode, query);

        allChip.setText(getString(R.string.filter_all, catalog.size()));
        fcmChip.setText(getString(R.string.filter_fcm, fcmCount));
        selectedChip.setText(getString(R.string.filter_selected, chosenInCatalog));
        summaryText.setText(getString(R.string.picker_summary, chosenInCatalog, filtered.size()));

        loadingText.setVisibility(View.GONE);
        emptyText.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        recycler.setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.submit(filtered, workingSelected);
        maybeRestoreListState();
    }

    private void maybeRestoreListState() {
        if (restoredListState == null || !catalogLoaded) return;
        if (adapter.getItemCount() == 0) {
            restoredListState = null;
            return;
        }
        RecyclerView.LayoutManager lm = recycler.getLayoutManager();
        if (lm != null) {
            lm.onRestoreInstanceState(restoredListState);
        }
        restoredListState = null;
    }

    private void syncFilterChipSelection() {
        syncingFilterChips = true;
        final Chip targetChip;
        try {
            targetChip = filterMode == AppPickerFilter.Mode.FCM_ONLY ? fcmChip
                    : filterMode == AppPickerFilter.Mode.SELECTED_ONLY ? selectedChip
                    : allChip;
            filterGroup.check(targetChip.getId());
        } finally {
            syncingFilterChips = false;
        }
        if (filterScroll != null && targetChip != null) {
            filterScroll.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                int targetLeft = Math.max(0, targetChip.getLeft() - filterGroup.getPaddingStart());
                filterScroll.scrollTo(targetLeft, 0);
            });
        }
    }

    private void applyWindowInsets() {
        View toolbarContainer = ui(R.id.pickerToolbarContainer);
        final int toolbarLeft = toolbarContainer.getPaddingLeft();
        final int toolbarTop = toolbarContainer.getPaddingTop();
        final int toolbarRight = toolbarContainer.getPaddingRight();
        final int toolbarBottom = toolbarContainer.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(toolbarContainer, (v, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(toolbarLeft + safe.left, toolbarTop + safe.top, toolbarRight + safe.right, toolbarBottom);
            return insets;
        });

        View headerContainer = ui(R.id.pickerHeaderContainer);
        final int headerLeft = headerContainer.getPaddingLeft();
        final int headerTop = headerContainer.getPaddingTop();
        final int headerRight = headerContainer.getPaddingRight();
        final int headerBottom = headerContainer.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(headerContainer, (v, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(headerLeft + safe.left, headerTop, headerRight + safe.right, headerBottom);
            return insets;
        });

        final int listLeft = recycler.getPaddingLeft();
        final int listTop = recycler.getPaddingTop();
        final int listRight = recycler.getPaddingRight();
        final int listBottom = recycler.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(recycler, (v, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(listLeft + safe.left, listTop, listRight + safe.right,
                    listBottom + Math.max(safe.bottom, ime.bottom));
            return insets;
        });

        final int emptyLeft = emptyText.getPaddingLeft();
        final int emptyTop = emptyText.getPaddingTop();
        final int emptyRight = emptyText.getPaddingRight();
        final int emptyBottom = emptyText.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(emptyText, (v, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(emptyLeft + safe.left, emptyTop, emptyRight + safe.right, emptyBottom + safe.bottom);
            return insets;
        });

        final int loadingLeft = loadingText.getPaddingLeft();
        final int loadingTop = loadingText.getPaddingTop();
        final int loadingRight = loadingText.getPaddingRight();
        final int loadingBottom = loadingText.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(loadingText, (v, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(loadingLeft + safe.left, loadingTop, loadingRight + safe.right, loadingBottom + safe.bottom);
            return insets;
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null && searchInput != null) {
            imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        }
    }

    private static AppPickerFilter.Mode parseMode(String name) {
        if (name == null) return AppPickerFilter.Mode.ALL;
        try {
            return AppPickerFilter.Mode.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return AppPickerFilter.Mode.ALL;
        }
    }
}
