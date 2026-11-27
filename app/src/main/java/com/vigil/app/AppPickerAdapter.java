package com.vigil.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.checkbox.MaterialCheckBox;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Recycled multi-choice adapter for choosing watched notification apps. */
final class AppPickerAdapter extends RecyclerView.Adapter<AppPickerAdapter.Holder> {
    interface Listener {
        void onToggle(String packageName, boolean checked);
    }

    private final Context context;
    private final Listener listener;
    private List<FcmAppScanner.AppEntry> items = Collections.emptyList();
    private Set<String> selected = Collections.emptySet();

    AppPickerAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    void submit(List<FcmAppScanner.AppEntry> nextItems, Set<String> nextSelected) {
        List<FcmAppScanner.AppEntry> before = items;
        Set<String> oldSelected = selected;
        List<FcmAppScanner.AppEntry> copy = new ArrayList<>(nextItems);
        Set<String> selectedCopy = new HashSet<>(nextSelected);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return before.size(); }
            @Override public int getNewListSize() { return copy.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                return before.get(oldPos).packageName.equals(copy.get(newPos).packageName);
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                FcmAppScanner.AppEntry a = before.get(oldPos);
                FcmAppScanner.AppEntry b = copy.get(newPos);
                return a.label.equals(b.label)
                        && a.declaresFcm == b.declaresFcm
                        && oldSelected.contains(a.packageName) == selectedCopy.contains(b.packageName);
            }
        });
        items = copy;
        selected = selectedCopy;
        diff.dispatchUpdatesTo(this);
    }

    @Override public int getItemCount() { return items.size(); }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_picker_app, parent, false));
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
        FcmAppScanner.AppEntry app = items.get(position);
        boolean isChecked = selected.contains(app.packageName);
        holder.bound = app;
        holder.itemView.setTag("picker:" + app.packageName);
        holder.icon.setImageDrawable(AppIconCache.load(context, app.packageName));
        holder.title.setText(app.label);
        holder.subtitle.setText(app.declaresFcm
                ? context.getString(R.string.picker_fcm_subtitle, app.packageName)
                : app.packageName);
        holder.checkBox.setChecked(isChecked);
    }

    final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView subtitle;
        final MaterialCheckBox checkBox;
        FcmAppScanner.AppEntry bound;

        Holder(View view) {
            super(view);
            icon = view.findViewById(R.id.pickerAppIcon);
            title = view.findViewById(R.id.pickerAppTitle);
            subtitle = view.findViewById(R.id.pickerAppSubtitle);
            checkBox = view.findViewById(R.id.pickerAppCheck);
            view.setOnClickListener(v -> {
                if (bound == null) return;
                boolean next = !selected.contains(bound.packageName);
                listener.onToggle(bound.packageName, next);
            });
        }
    }
}
