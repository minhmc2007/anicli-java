package com.minhmc2077.aniclijava.model;

public final class AnimeDetail {
    public final String title;
    public final String posterUrl;
    public final String description;
    public final String score;
    public final String status;
    public final String genres;
    public final int subCount;
    public final int dubCount;

    public AnimeDetail(String title, String posterUrl, String description, String score,
                       String status, String genres, int subCount, int dubCount) {
        this.title = title;
        this.posterUrl = posterUrl;
        this.description = description;
        this.score = score;
        this.status = status;
        this.genres = genres;
        this.subCount = subCount;
        this.dubCount = dubCount;
    }
}
