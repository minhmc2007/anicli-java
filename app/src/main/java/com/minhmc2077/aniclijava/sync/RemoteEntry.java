package com.minhmc2077.aniclijava.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RemoteEntry {
    public final int malId;
    public final int anilistId;
    public final int progress;
    public final int totalEpisodes;
    public final List<String> titles;

    public RemoteEntry(int malId, int anilistId, int progress, int totalEpisodes,
                       List<String> titles) {
        this.malId = malId;
        this.anilistId = anilistId;
        this.progress = progress;
        this.totalEpisodes = totalEpisodes;
        this.titles = titles == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(titles));
    }
}
