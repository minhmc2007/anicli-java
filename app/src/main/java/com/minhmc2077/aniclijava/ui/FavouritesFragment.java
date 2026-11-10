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
import com.minhmc2077.aniclijava.data.AiringChecker;
import com.minhmc2077.aniclijava.model.Anime;
import com.minhmc2077.aniclijava.ui.adapter.AnimeAdapter;

import java.util.List;

public final class FavouritesFragment extends Fragment implements Refreshable {
    private AnimeAdapter adapter;
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

        ((TextView) view.findViewById(R.id.header_title)).setText(R.string.favourites_title);
        ((TextView) view.findViewById(R.id.header_subtitle)).setText(R.string.favourites_subtitle);

        swipe = view.findViewById(R.id.swipe);
        recycler = view.findViewById(R.id.recycler);
        stateView = view.findViewById(R.id.state);
        stateProgress = view.findViewById(R.id.state_progress);
        stateText = view.findViewById(R.id.state_text);
        stateSubtext = view.findViewById(R.id.state_subtext);
        stateButton = view.findViewById(R.id.state_button);

        int columns = Math.max(2, getResources().getConfiguration().screenWidthDp / 180);
        recycler.setLayoutManager(new GridLayoutManager(requireContext(), columns));
        adapter = new AnimeAdapter(App.get().imageLoader(),
                anime -> AnimeDetailActivity.start(requireContext(), anime, null));
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
        List<Anime> items = App.get().favourites().getAll();
        adapter.submit(items);
        adapter.setNewEpisodes(AiringChecker.badges());
        if (items.isEmpty()) {
            showEmpty();
        } else {
            hideState();
            AiringChecker.refreshAsync(requireContext(), ids -> {
                if (adapter != null) {
                    adapter.setNewEpisodes(ids);
                }
            });
        }
    }

    private void showEmpty() {
        if (stateView == null) {
            return;
        }
        stateView.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(View.GONE);
        stateText.setText(R.string.favourites_empty_title);
        stateSubtext.setVisibility(View.VISIBLE);
        stateSubtext.setText(R.string.favourites_empty_subtitle);
        stateButton.setVisibility(View.GONE);
    }

    private void hideState() {
        if (stateView != null) {
            stateView.setVisibility(View.GONE);
        }
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
