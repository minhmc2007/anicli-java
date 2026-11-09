package com.minhmc2077.aniclijava.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.ui.adapter.AnimeAdapter;

public final class SearchFragment extends Fragment implements Refreshable {
    private AnimeAdapter adapter;
    private EditText searchInput;
    private ImageButton clearButton;
    private ImageButton searchButton;
    private RecyclerView recycler;
    private View stateView;
    private ProgressBar stateProgress;
    private TextView stateText;
    private TextView stateSubtext;
    private MaterialButton stateButton;

    private String lastQuery = "";
    private boolean loading;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_search, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        searchInput = view.findViewById(R.id.search_input);
        clearButton = view.findViewById(R.id.clear_button);
        searchButton = view.findViewById(R.id.search_button);
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

        searchButton.setOnClickListener(v -> performSearch());
        clearButton.setOnClickListener(v -> {
            searchInput.setText("");
            adapter.submit(java.util.Collections.emptyList());
            showInitial();
        });
        stateButton.setOnClickListener(v -> performSearch());

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                // The watcher can fire after onDestroyView(); use a local copy.
                ImageButton button = clearButton;
                if (button != null) {
                    button.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                }
            }
        });

        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                performSearch();
                return true;
            }
            return false;
        });

        showInitial();
    }

    private void performSearch() {
        if (searchInput == null) {
            return;
        }
        String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            adapter.submit(java.util.Collections.emptyList());
            showInitial();
            return;
        }
        if (loading) {
            return;
        }
        lastQuery = query;
        hideKeyboard();
        loading = true;
        showLoading();

        App.get().async(
                () -> App.get().repository().search(query),
                list -> {
                    loading = false;
                    if (adapter != null) {
                        adapter.submit(list);
                    }
                    if (list.isEmpty()) {
                        showEmptyResults();
                    } else {
                        hideState();
                    }
                },
                error -> {
                    loading = false;
                    showError(App.get().friendlyError(error));
                }
        );
    }

    private void hideKeyboard() {
        View view = getView();
        if (view == null) {
            return;
        }
        InputMethodManager manager =
                (InputMethodManager) requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
        searchInput.clearFocus();
    }

    private void showInitial() {
        if (stateView == null) {
            return;
        }
        stateView.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(View.GONE);
        stateText.setText(R.string.search_empty_title);
        stateSubtext.setText(R.string.search_empty_subtitle);
        stateButton.setVisibility(View.GONE);
    }

    private void showLoading() {
        if (stateView == null) {
            return;
        }
        stateView.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(View.VISIBLE);
        stateText.setText(R.string.loading);
        stateSubtext.setText("");
        stateButton.setVisibility(View.GONE);
    }

    private void showEmptyResults() {
        if (stateView == null) {
            return;
        }
        stateView.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(View.GONE);
        stateText.setText(R.string.search_no_results);
        stateSubtext.setText(R.string.search_empty_subtitle);
        stateButton.setVisibility(View.GONE);
    }

    private void showError(String message) {
        if (stateView == null) {
            return;
        }
        stateView.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(View.GONE);
        stateText.setText(message);
        stateSubtext.setText("");
        stateButton.setVisibility(View.VISIBLE);
    }

    private void hideState() {
        if (stateView != null) {
            stateView.setVisibility(View.GONE);
        }
    }

    @Override
    public void onShown() {
        if (adapter != null && adapter.getItemCount() == 0 && lastQuery.isEmpty()) {
            showInitial();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Let later searches run even if a request was in flight.
        loading = false;
        if (recycler != null) {
            recycler.setAdapter(null);
        }
        adapter = null;
        searchInput = null;
        clearButton = null;
        searchButton = null;
        recycler = null;
        stateView = null;
        stateProgress = null;
        stateText = null;
        stateSubtext = null;
        stateButton = null;
    }
}
