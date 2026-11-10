package com.minhmc2077.aniclijava.ui;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.minhmc2077.aniclijava.util.Settings;

public abstract class BaseActivity extends AppCompatActivity {
    private String appliedAccent;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appliedAccent = Settings.accent(this);
        Settings.applyAccent(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Settings.accent(this).equals(appliedAccent)) {
            recreate();
        }
    }
}
