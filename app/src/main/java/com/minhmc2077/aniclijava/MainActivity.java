package com.minhmc2077.aniclijava;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.minhmc2077.aniclijava.ui.FavouritesFragment;
import com.minhmc2077.aniclijava.ui.HistoryFragment;
import com.minhmc2077.aniclijava.ui.HomeFragment;
import com.minhmc2077.aniclijava.ui.Refreshable;
import com.minhmc2077.aniclijava.ui.SearchFragment;
import com.minhmc2077.aniclijava.util.Ui;

public final class MainActivity extends AppCompatActivity {
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
        Ui.applyVerticalInsets(findViewById(R.id.root));

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            getSupportActionBar().setTitle(R.string.app_name);
        }

        bottomNavigation = findViewById(R.id.bottom_nav);
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
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
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
        transaction.commitNow();

        updateToolbar(itemId);
        invalidateOptionsMenu();

        if (target instanceof Refreshable) {
            ((Refreshable) target).onShown();
        }
    }

    private Fragment createFragment(int itemId) {
        switch (itemId) {
            case R.id.nav_search:
                return new SearchFragment();
            case R.id.nav_favourites:
                return new FavouritesFragment();
            case R.id.nav_history:
                return new HistoryFragment();
            case R.id.nav_home:
            default:
                return new HomeFragment();
        }
    }

    private String tagFor(int itemId) {
        switch (itemId) {
            case R.id.nav_search:
                return TAG_SEARCH;
            case R.id.nav_favourites:
                return TAG_FAVOURITES;
            case R.id.nav_history:
                return TAG_HISTORY;
            case R.id.nav_home:
            default:
                return TAG_HOME;
        }
    }

    private void updateToolbar(int itemId) {
        if (getSupportActionBar() == null) {
            return;
        }
        switch (itemId) {
            case R.id.nav_search:
                getSupportActionBar().setSubtitle(R.string.nav_search);
                break;
            case R.id.nav_favourites:
                getSupportActionBar().setSubtitle(R.string.nav_favourites);
                break;
            case R.id.nav_history:
                getSupportActionBar().setSubtitle(R.string.nav_history);
                break;
            case R.id.nav_home:
            default:
                getSupportActionBar().setSubtitle(R.string.home_title);
                break;
        }
    }
}
