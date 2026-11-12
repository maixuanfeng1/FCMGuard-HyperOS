package com.reed.fcmguard;

import android.view.*;
import android.widget.TextView;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.color.MaterialColors;
import java.util.*;

/** Recycles app rows while retaining the dashboard and preference sections. */
final class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.Holder> {
    private final MainActivity activity;
    private List<AppDiagnostics> rows = Collections.emptyList();
    private Set<String> chosen = Collections.emptySet();
    AppListAdapter(MainActivity activity) { this.activity = activity; }
    AppDiagnostics row(int index) { return rows.get(index); }
    void submit(List<AppDiagnostics> next, Set<String> selection) {
        List<AppDiagnostics> before = rows;
        Set<String> oldSelection = chosen;
        List<AppDiagnostics> copy = new ArrayList<>(next);
        Set<String> selected = new HashSet<>(selection);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return before.size(); }
            @Override public int getNewListSize() { return copy.size(); }
            @Override public boolean areItemsTheSame(int oldIndex, int newIndex) {
                return before.get(oldIndex).app.packageName.equals(copy.get(newIndex).app.packageName);
            }
            @Override public boolean areContentsTheSame(int oldIndex, int newIndex) {
                AppDiagnostics a = before.get(oldIndex), b = copy.get(newIndex);
                return a.app.label.equals(b.app.label) && a.app.declaresFcm == b.app.declaresFcm &&
                        a.autostart == b.autostart && Objects.equals(a.inSystemList, b.inSystemList) &&
                        Objects.equals(a.notificationPermission, b.notificationPermission) &&
                        Objects.equals(a.ignoresDoze, b.ignoresDoze) && Objects.equals(a.stopped, b.stopped) &&
                        oldSelection.contains(a.app.packageName) == selected.contains(b.app.packageName);
            }
        });
        rows = copy; chosen = selected;
        diff.dispatchUpdatesTo(this);
    }
    @Override public int getItemCount() { return rows.size(); }
    @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.app_row, parent, false));
    }
    @Override public void onBindViewHolder(Holder holder, int position) {
        AppDiagnostics row = rows.get(position);
        holder.bound = row;
        holder.itemView.setTag("app:" + row.app.packageName);
        holder.title.setText(row.app.label + (chosen.contains(row.app.packageName) ? " ★" : ""));
        holder.packageName.setText(row.app.packageName);
        holder.fcm.setText(row.app.declaresFcm ? R.string.fcm_detected : R.string.fcm_not_detected);
        holder.autostart.setText(activity.getString(R.string.auto_label, activity.getString(activity.autoLabel(row.autostart))));
        holder.list.setText(activity.getString(R.string.list_label, activity.flag(row.inSystemList, R.string.listed, R.string.not_listed)));
        holder.notifications.setText(activity.getString(R.string.notifications_label, activity.getString(activity.notificationLabel(row))));
        int color = MaterialColors.getColor(holder.itemView, row.needsAttention() ?
                androidx.appcompat.R.attr.colorError : com.google.android.material.R.attr.colorOnSurface);
        holder.title.setTextColor(color);
        holder.autostart.setTextColor(color);
    }
    final class Holder extends RecyclerView.ViewHolder {
        final TextView title, packageName, fcm, autostart, list, notifications;
        AppDiagnostics bound;
        Holder(View view) {
            super(view);
            title = view.findViewById(R.id.appTitle); packageName = view.findViewById(R.id.appPackage);
            fcm = view.findViewById(R.id.appFcm); autostart = view.findViewById(R.id.appAutostart);
            list = view.findViewById(R.id.appSystemList); notifications = view.findViewById(R.id.appNotifications);
            view.setOnClickListener(v -> activity.showApp(bound));
        }
    }
    static RecyclerView.Adapter<RecyclerView.ViewHolder> section(View view) {
        return new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public int getItemCount() { return 1; }
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                return new RecyclerView.ViewHolder(view) { };
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) { }
        };
    }
}
