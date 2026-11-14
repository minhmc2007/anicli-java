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
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.model.Anime;
import com.minhmc2077.aniclijava.ui.adapter.AnimeAdapter;
import com.minhmc2077.aniclijava.util.Settings;

import java.util.List;

public final class HomeFragment extends Fragment implements Refreshable {
    private AnimeAdapter adapter;
    private SwipeRefreshLayout swipe;
    private RecyclerView recycler;
    private View stateView;
    private ProgressBar stateProgress;
    private TextView stateText;
    private MaterialButton stateButton;

    private boolean loading;
    private boolean loaded;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_anime_grid, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ((TextView) view.findViewById(R.id.header_title)).setText(R.string.home_title);
        boolean mkissa = Settings.SOURCE_MKISSA.equals(Settings.animeSource(requireContext()));
        ((TextView) view.findViewById(R.id.header_subtitle)).setText(mkissa
                ? R.string.home_subtitle_mkissa
                : R.string.home_subtitle);

        swipe = view.findViewById(R.id.swipe);
        recycler = view.findViewById(R.id.recycler);
        stateView = view.findViewById(R.id.state);
        stateProgress = view.findViewById(R.id.state_progress);
        stateText = view.findViewById(R.id.state_text);
        stateButton = view.findViewById(R.id.state_button);

        int columns = Math.max(2, getResources().getConfiguration().screenWidthDp / 180);
        recycler.setLayoutManager(new GridLayoutManager(requireContext(), columns));
        adapter = new AnimeAdapter(App.get().imageLoader(),
                anime -> AnimeDetailActivity.start(requireContext(), anime, null));
        recycler.setAdapter(adapter);

        swipe.setOnRefreshListener(() -> load(true));
        stateButton.setOnClickListener(v -> load(true));

        load(false);
    }

    private void load(boolean force) {
        if (loading) {
            if (swipe != null) {
                swipe.setRefreshing(false);
            }
            return;
        }
        loading = true;
        if (force) {
            hideState();
        } else if (!loaded) {
            showLoading();
        }

        App.get().async(
                () -> App.get().animeSource().trending(force),
                list -> {
                    loading = false;
                    loaded = true;
                    if (swipe != null) {
                        swipe.setRefreshing(false);
                    }
                    if (adapter != null) {
                        adapter.submit(list);
                    }
                    if (list.isEmpty()) {
                        showError(getString(R.string.search_no_results));
                    } else {
                        hideState();
                    }
                },
                error -> {
                    loading = false;
                    if (swipe != null) {
                        swipe.setRefreshing(false);
                    }
                    showError(App.get().friendlyError(error));
                }
        );
    }

    private void showLoading() {
        if (stateView == null) {
            return;
        }
        stateView.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(View.VISIBLE);
        stateText.setText(R.string.loading);
        stateButton.setVisibility(View.GONE);
    }

    private void showError(String message) {
        if (stateView == null) {
            return;
        }
        stateView.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(View.GONE);
        stateText.setText(message);
        stateButton.setVisibility(View.VISIBLE);
    }

    private void hideState() {
        if (stateView != null) {
            stateView.setVisibility(View.GONE);
        }
    }

    @Override
    public void onShown() {
        if (!loaded && !loading) {
            load(false);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Don't keep the in-flight flag across view recreation.
        loading = false;
        if (recycler != null) {
            recycler.setAdapter(null);
        }
        adapter = null;
        swipe = null;
        recycler = null;
        stateView = null;
        stateProgress = null;
        stateText = null;
        stateButton = null;
    }
}
