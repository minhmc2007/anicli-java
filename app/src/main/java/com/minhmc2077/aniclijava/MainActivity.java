package com.minhmc2077.aniclijava;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.minhmc2077.aniclijava.data.AiringChecker;
import com.minhmc2077.aniclijava.ui.BaseActivity;
import com.minhmc2077.aniclijava.ui.FavouritesFragment;
import com.minhmc2077.aniclijava.ui.HistoryFragment;
import com.minhmc2077.aniclijava.ui.HomeFragment;
import com.minhmc2077.aniclijava.ui.Refreshable;
import com.minhmc2077.aniclijava.ui.SearchFragment;
import com.minhmc2077.aniclijava.ui.SettingsActivity;
import com.minhmc2077.aniclijava.ui.UpdatePrompt;
import com.minhmc2077.aniclijava.util.AiringAlarm;
import com.minhmc2077.aniclijava.util.Settings;
import com.minhmc2077.aniclijava.util.Ui;

public final class MainActivity extends BaseActivity {
    private static final String TAG_HOME = "tab_home";
    private static final String TAG_SEARCH = "tab_search";
    private static final String TAG_FAVOURITES = "tab_favourites";
    private static final String TAG_HISTORY = "tab_history";

    private MaterialToolbar toolbar;
    private BottomNavigationView bottomNavigation;
    private int currentTab = R.id.nav_home;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.setupEdgeToEdge(this);
        setContentView(R.layout.activity_main);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            getSupportActionBar().setTitle(R.string.app_name);
        }

        bottomNavigation = findViewById(R.id.bottom_nav);
        Ui.applyTopInset(toolbar);
        Ui.applyBottomInset(bottomNavigation);

        int startTab = savedInstanceState == null
                ? R.id.nav_home
                : bottomNavigation.getSelectedItemId();
        bottomNavigation.setSelectedItemId(startTab);
        switchTab(startTab);
        bottomNavigation.setOnItemSelectedListener(item -> {
            switchTab(item.getItemId());
            return true;
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (bottomNavigation.getSelectedItemId() != R.id.nav_home) {
                    bottomNavigation.setSelectedItemId(R.id.nav_home);
                } else {
                    // Disable for this dispatch only; restore afterwards so back
                    // still works on other tabs.
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });

        if (savedInstanceState == null && Settings.updateCheckEnabled(this)) {
            findViewById(R.id.root).post(() -> UpdatePrompt.check(this, false));
        }
        AiringChecker.refreshAsync(this, null);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem clear = menu.findItem(R.id.action_clear_history);
        if (clear != null) {
            clear.setVisible(currentTab == R.id.nav_history);
        }
        MenuItem settings = menu.findItem(R.id.action_settings);
        if (settings != null) {
            settings.setVisible(currentTab == R.id.nav_home);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_clear_history) {
            Fragment fragment = getSupportFragmentManager().findFragmentByTag(TAG_HISTORY);
            if (fragment instanceof HistoryFragment) {
                ((HistoryFragment) fragment).confirmClear();
            }
            return true;
        }
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void switchTab(int itemId) {
        currentTab = itemId;
        String tag = tagFor(itemId);
        FragmentManager manager = getSupportFragmentManager();
        Fragment target = manager.findFragmentByTag(tag);
        FragmentTransaction transaction = manager.beginTransaction();

        if (target == null) {
            target = createFragment(itemId);
            transaction.add(R.id.fragment_container, target, tag);
        }

        for (Fragment fragment : manager.getFragments()) {
            if (fragment != target && !fragment.isHidden()) {
                transaction.hide(fragment);
            }
        }
        transaction.show(target);
        // commitNow() throws after onSaveInstanceState(); fall back in that case.
        if (manager.isStateSaved()) {
            transaction.commitAllowingStateLoss();
        } else {
            transaction.commitNow();
        }

        updateToolbar(itemId);
        invalidateOptionsMenu();
        refreshCurrentTab();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCurrentTab();
    }

    private void refreshCurrentTab() {
        if (bottomNavigation == null) {
            return;
        }
        Fragment current = getSupportFragmentManager().findFragmentByTag(tagFor(currentTab));
        if (current instanceof Refreshable) {
            ((Refreshable) current).onShown();
        }
    }

    private Fragment createFragment(int itemId) {
        if (itemId == R.id.nav_search) {
            return new SearchFragment();
        }
        if (itemId == R.id.nav_favourites) {
            return new FavouritesFragment();
        }
        if (itemId == R.id.nav_history) {
            return new HistoryFragment();
        }
        return new HomeFragment();
    }

    private String tagFor(int itemId) {
        if (itemId == R.id.nav_search) {
            return TAG_SEARCH;
        }
        if (itemId == R.id.nav_favourites) {
            return TAG_FAVOURITES;
        }
        if (itemId == R.id.nav_history) {
            return TAG_HISTORY;
        }
        return TAG_HOME;
    }

    private void updateToolbar(int itemId) {
        if (getSupportActionBar() == null) {
            return;
        }
        if (itemId == R.id.nav_search) {
            getSupportActionBar().setSubtitle(R.string.nav_search);
        } else if (itemId == R.id.nav_favourites) {
            getSupportActionBar().setSubtitle(R.string.nav_favourites);
        } else if (itemId == R.id.nav_history) {
            getSupportActionBar().setSubtitle(R.string.nav_history);
        } else {
            getSupportActionBar().setSubtitle(R.string.home_title);
        }
    }
}
