package com.minhmc2077.aniclijava.data;

import com.minhmc2077.aniclijava.model.Anime;
import com.minhmc2077.aniclijava.model.AnimeDetail;
import com.minhmc2077.aniclijava.model.Episode;
import com.minhmc2077.aniclijava.model.StreamInfo;

import java.io.IOException;
import java.util.List;

public interface AnimeSource {
    List<Anime> trending(boolean force) throws IOException;

    List<Anime> search(String query) throws IOException;

    AnimeDetail detail(String animeId, boolean force) throws IOException;

    List<Episode> episodes(String animeId, boolean force) throws IOException;

    StreamInfo resolveStream(String animeId, Episode episode, String mode) throws IOException;
}
