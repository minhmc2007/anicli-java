package com.minhmc2077.aniclijava.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.model.HistoryEntry;
import com.minhmc2077.aniclijava.ui.adapter.HistoryAdapter;
import com.minhmc2077.aniclijava.util.Ui;

import java.util.List;

public final class HistoryFragment extends Fragment implements Refreshable {
    private HistoryAdapter adapter;
    private SwipeRefreshLayout swipe;
    private RecyclerView recycler;
    private View stateView;
    private ProgressBar stateProgress;
    private TextView stateText;
    private TextView stateSubtext;
    private MaterialButton stateButton;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_anime_grid, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ((TextView) view.findViewById(R.id.header_title)).setText(R.string.history_title);
        ((TextView) view.findViewById(R.id.header_subtitle)).setText(R.string.history_subtitle);

        swipe = view.findViewById(R.id.swipe);
        recycler = view.findViewById(R.id.recycler);
        stateView = view.findViewById(R.id.state);
        stateProgress = view.findViewById(R.id.state_progress);
        stateText = view.findViewById(R.id.state_text);
        stateSubtext = view.findViewById(R.id.state_subtext);
        stateButton = view.findViewById(R.id.state_button);

        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new HistoryAdapter(App.get().imageLoader(), new HistoryAdapter.Listener() {
            @Override
            public void onHistoryClick(HistoryEntry entry) {
                PlayerActivity.start(requireContext(), entry);
            }

            @Override
            public void onRemove(HistoryEntry entry) {
                App.get().history().remove(entry.animeId);
                load();
                Ui.toast(requireContext(), getString(R.string.remove_entry));
            }
        });
        recycler.setAdapter(adapter);

        swipe.setOnRefreshListener(() -> {
            load();
            if (swipe != null) {
                swipe.setRefreshing(false);
            }
        });
        stateButton.setOnClickListener(v -> load());

        load();
    }

    private void load() {
        if (adapter == null) {
            return;
        }
        List<HistoryEntry> items = App.get().history().getAll();
        adapter.submit(items);
        if (items.isEmpty()) {
            showEmpty();
        } else {
            hideState();
        }
    }

    private void showEmpty() {
        if (stateView == null) {
            return;
        }
        stateView.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(View.GONE);
        stateText.setText(R.string.history_empty_title);
        stateSubtext.setVisibility(View.VISIBLE);
        stateSubtext.setText(R.string.history_empty_subtitle);
        stateButton.setVisibility(View.GONE);
    }

    private void hideState() {
        if (stateView != null) {
            stateView.setVisibility(View.GONE);
        }
    }

    public void confirmClear() {
        if (!isAdded()) {
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.history_clear_title)
                .setMessage(R.string.history_clear_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear, (dialog, which) -> {
                    App.get().history().clear();
                    load();
                    Ui.toast(requireContext(), getString(R.string.history_cleared));
                })
                .show();
    }

    @Override
    public void onShown() {
        load();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (recycler != null) {
            recycler.setAdapter(null);
        }
        adapter = null;
        swipe = null;
        recycler = null;
        stateView = null;
        stateProgress = null;
        stateText = null;
        stateSubtext = null;
        stateButton = null;
    }
}
