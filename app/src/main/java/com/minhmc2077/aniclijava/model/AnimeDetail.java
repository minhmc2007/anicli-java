package com.minhmc2077.aniclijava.model;

/**
 * Extra metadata from an anime detail page.
 */
public final class AnimeDetail {
    public final String title;
    public final String posterUrl;
    public final String description;
    public final String score;
    public final String status;
    public final String genres;

    public AnimeDetail(String title, String posterUrl, String description, String score,
                       String status, String genres) {
        this.title = title;
        this.posterUrl = posterUrl;
        this.description = description;
        this.score = score;
        this.status = status;
        this.genres = genres;
    }
}
