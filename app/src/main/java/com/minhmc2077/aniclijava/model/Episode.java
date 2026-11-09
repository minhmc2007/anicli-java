package com.minhmc2077.aniclijava.model;

/**
 * One episode of an anime. The number is the label shown to the user, the id is
 * the HiAnime episode id used by the servers endpoint.
 */
public final class Episode {
    public final String number;
    public final String id;

    public Episode(String number, String id) {
        this.number = number;
        this.id = id;
    }
}
